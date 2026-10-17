package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.npc.GlbModelDescriptor
import com.huashanlunjian.glbrender.npc.GlbModels
import com.mojang.logging.LogUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import top.fifthlight.blazerod.model.loader.LoadContext
import top.fifthlight.blazerod.model.loader.LoadResult
import top.fifthlight.blazerod.model.loader.ModelFileLoader
import top.fifthlight.blazerod.render.api.loader.ModelLoader
import top.fifthlight.blazerod.render.api.render.Renderer
import top.fifthlight.blazerod.render.api.render.RendererTypeHolder
import top.fifthlight.blazerod.render.api.resource.RenderScene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.extension
import kotlin.io.path.inputStream

/**
 * Client-side scene cache for the NPC models registered in the common [GlbModels] table: the lazily/async-loaded
 * [RenderScene] per model id, plus the single shared [Renderer] they all draw through. The renderer is
 * scene-agnostic (`drawScene` reads the scene off the [top.fifthlight.blazerod.render.api.resource.ModelInstance]),
 * so one instance — rewound once per frame via [rotateForFrame] — serves every model.
 */
object GlbModelRegistry {
    private val LOGGER = LogUtils.getLogger()

    private val loadStates = HashMap<String, LoadState>()

    @Volatile
    private var sharedRenderer: Renderer<*, *>? = null

    /** The shared renderer, or null until the first model finishes loading. */
    fun renderer(): Renderer<*, *>? = sharedRenderer

    /**
     * Returns the loaded scene for [id], kicking off an async load on first request and returning null while it is
     * still in flight (the renderer stays invisible until it resolves). Unknown ids and failed loads also return
     * null, so callers just skip the draw.
     */
    fun sceneFor(id: String): RenderScene? {
        val descriptor = GlbModels.descriptor(id) ?: return null
        val state = loadStates.getOrPut(id) { LoadState() }
        if (state.failed) return null
        if (!state.started) startLoad(descriptor, state)
        return state.scene
    }

    /**
     * Rewinds the shared renderer's ring-buffer data pool. MUST run exactly once per rendered frame, before any
     * draw: each `drawScene` allocates per-node upload space from a pool that only resets on [Renderer.rotate], so
     * skipping this grows the pool unbounded until `growCapacity` exceeds the GL max buffer size and throws
     * GL_INVALID_VALUE every frame (the Step 2 crash). Hooked to RenderLevelStageEvent.AFTER_SKY by [GlbNpcClient].
     */
    fun rotateForFrame() {
        sharedRenderer?.rotate()
    }

    private fun startLoad(descriptor: GlbModelDescriptor, state: LoadState) {
        state.started = true
        CoroutineScope(RenderThreadDispatcher).launch {
            try {
                val loaded = parse(descriptor.modelPath)
                val model = requireNotNull(loaded.model) { "Loader returned no model" }
                val loadedScene = ModelLoader.Factory.create().loadModel(model)
                    ?: error("Model loaded into no scene")
                if (sharedRenderer == null) sharedRenderer = RendererTypeHolder.vertexShaderTransform.create()
                // The cache OWNS one reference on the scene for as long as it stays cached. Each ModelInstance takes
                // its own ref (ModelInstanceImpl.init -> scene.increaseReferenceCount) and drops it on close, so
                // without this the scene's refcount hits 0 when the last NPC leaves render distance, RenderSceneImpl
                // auto-closes, and the next NPC that walks back in gets a closed scene -> "already closed" crash. A
                // 3c reload must decreaseReferenceCount the evicted scene to balance this.
                loadedScene.increaseReferenceCount()
                state.scene = loadedScene
                LOGGER.info(
                    "glbrender: npc scene ready for {} ({}) ({} nodes)",
                    descriptor.id, descriptor.modelPath, loadedScene.nodes.size,
                )
            } catch (error: Throwable) {
                state.failed = true
                LOGGER.error(
                    "glbrender: could not load npc model {} ({})",
                    descriptor.id, descriptor.modelPath, error,
                )
            }
        }
    }

    private suspend fun parse(path: Path): LoadResult =
        withContext(Dispatchers.Default) {
            val fileLoader = ServiceLoader
                .load(ModelFileLoader::class.java, ModelFileLoader::class.java.classLoader)
                .toList()
                .firstOrNull { path.extension.lowercase() in it.extensions.keys }
                ?: error("No ModelFileLoader for ${path.extension}")
            val header = ByteBuffer.wrap(path.inputStream().use { it.readNBytes(fileLoader.probeLength ?: 4) })
                .order(ByteOrder.LITTLE_ENDIAN)
            check(fileLoader.probe(header)) { "${fileLoader.javaClass.simpleName} rejected $path" }
            fileLoader.load(path, LoadContext.File(path.parent))
        }

    private class LoadState {
        var started = false
        var failed = false

        @Volatile
        var scene: RenderScene? = null
    }

    private object RenderThreadDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            Minecraft.getInstance().execute(block)
        }
    }
}
