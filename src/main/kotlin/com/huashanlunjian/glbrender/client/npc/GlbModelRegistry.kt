package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.client.npc.anim.GlbAnimationTable
import com.huashanlunjian.glbrender.client.npc.anim.GlbAnimationTables
import com.huashanlunjian.glbrender.npc.GlbModelDescriptor
import com.huashanlunjian.glbrender.npc.GlbModels
import com.mojang.logging.LogUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import top.fifthlight.blazerod.model.animation.Animation
import top.fifthlight.blazerod.model.loader.LoadContext
import top.fifthlight.blazerod.model.loader.LoadResult
import top.fifthlight.blazerod.model.loader.ModelFileLoader
import top.fifthlight.blazerod.render.api.animation.AnimationItem
import top.fifthlight.blazerod.render.api.loader.ModelLoader
import top.fifthlight.blazerod.render.api.render.Renderer
import top.fifthlight.blazerod.render.api.render.RendererTypeHolder
import top.fifthlight.blazerod.render.api.resource.RenderScene
import top.fifthlight.blazerod.render.runtime.animation.AnimationItemImpl
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

    /** The stock locomotion/life-state animation table shared by every NPC model. */
    val animationTable: GlbAnimationTable by lazy { GlbAnimationTables.default() }

    /**
     * The glTF clips resolved for [id], or null until its scene finishes loading. An empty list means the model
     * carries no clips (the animator then leaves the bind pose untouched). Kicks off the same async load as
     * [sceneFor], so callers usually hit [sceneFor] first and read this right after.
     */
    fun clipsFor(id: String): List<AnimationItemImpl>? {
        val state = loadStates[id] ?: return null
        return if (state.scene == null) null else state.clips
    }

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
                state.clips = buildClips(loadedScene, loaded.animations ?: emptyList())
                state.scene = loadedScene
                LOGGER.info(
                    "glbrender: npc scene ready for {} ({}) ({} nodes, {} clips)",
                    descriptor.id, descriptor.modelPath, loadedScene.nodes.size, state.clips.size,
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

    /** Builds an [AnimationItemImpl] per glTF clip the model carries, dropping (and logging) any that fail to load. */
    private fun buildClips(scene: RenderScene, animations: List<Animation>): List<AnimationItemImpl> =
        animations.mapNotNull { animation ->
            runCatching { AnimationItem.Factory.load(scene, animation) }
                .onFailure { LOGGER.warn("glbrender: could not load npc clip {}", animation.name, it) }
                .getOrNull()
        }.filterIsInstance<AnimationItemImpl>()

    private class LoadState {
        var started = false
        var failed = false

        @Volatile
        var clips: List<AnimationItemImpl> = emptyList()

        @Volatile
        var scene: RenderScene? = null
    }

    private object RenderThreadDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            Minecraft.getInstance().execute(block)
        }
    }
}
