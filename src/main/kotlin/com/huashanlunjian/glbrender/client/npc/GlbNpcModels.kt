package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.npc.GlbNpc
import com.mojang.logging.LogUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import net.neoforged.fml.loading.FMLPaths
import org.joml.Matrix4f
import top.fifthlight.blazerod.gl.Gl
import top.fifthlight.blazerod.gl.GpuTexture
import top.fifthlight.blazerod.gl.TextureFormat
import top.fifthlight.blazerod.model.loader.LoadContext
import top.fifthlight.blazerod.model.loader.LoadResult
import top.fifthlight.blazerod.model.loader.ModelFileLoader
import top.fifthlight.blazerod.render.api.loader.ModelLoader
import top.fifthlight.blazerod.render.api.render.Renderer
import top.fifthlight.blazerod.render.api.render.GlbRenderTarget
import top.fifthlight.blazerod.render.api.render.RendererTypeHolder
import top.fifthlight.blazerod.render.api.render.drawScene
import top.fifthlight.blazerod.render.api.resource.ModelInstance
import top.fifthlight.blazerod.render.api.resource.RenderScene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.extension
import kotlin.io.path.inputStream

/**
 * Shared GLB asset holder for the NPC renderer. Loads one model into a [RenderScene] once, on the render thread,
 * then hands each entity its own [ModelInstance] built from that scene (the ownership shape Step 3's data-driven
 * registration will key on). Draw itself goes through the glb-core public [drawScene] API, exactly like the demoted
 * player shim does — no NPC-specific render path.
 */
object GlbNpcModels {
    private val LOGGER = LogUtils.getLogger()

    // The player shim already faces correctly with a 180 offset baked into its formula; here the yaw passed to the
    // renderer is the entity rotation, so 0 keeps `rotateY(180 - yaw)` == vanilla's orientation. Exposed for tuning.
    private val yawOffset = System.getProperty("glbrender.npc.yawOffset", "0").toFloat()
    private val npcScale = System.getProperty("glbrender.npc.scale", "1.0").toFloat()

    private val modelPath: Path? = resolveModelPath()

    @Volatile
    private var scene: RenderScene? = null
    private var renderer: Renderer<*, *>? = null
    private var loadStarted = false
    private var loadFailed = false
    private val instances = HashMap<UUID, ModelInstance>()

    /**
     * Rewinds the renderer's ring-buffer data pool. MUST run exactly once per rendered frame, before any entity
     * draw: each [draw] allocates per-node upload space from a pool that only resets on [Renderer.rotate], so
     * without this the pool grows unbounded every frame and `growCapacity` eventually exceeds the GL max buffer
     * size, throwing GL_INVALID_VALUE (the "renders a few frames then dies every frame" symptom). Hooked to
     * RenderLevelStageEvent.AFTER_SKY by [GlbNpcClient].
     */
    fun rotateForFrame() {
        renderer?.rotate()
    }

    fun draw(entity: GlbNpc, yaw: Float, partialTick: Float, packedLight: Int) {
        if (loadFailed || modelPath == null) return
        if (!loadStarted) startLoad()
        val scene = this.scene ?: return
        val renderer = this.renderer ?: return
        val instance = instances.getOrPut(entity.uuid) {
            ModelInstance.Factory.of(scene).also { it.increaseReferenceCount() }
        }
        try {
            instance.updateRenderData(partialTick)
            val minecraft = Minecraft.getInstance()
            val target = minecraft.mainRenderTarget
            val device = Gl.device
            val color = GpuTexture.adopt(target.colorTextureId, TextureFormat.RGBA8)
            val depth = GpuTexture.adopt(target.depthTextureId, TextureFormat.DEPTH24)
            val colorView = device.createTextureView(color)
            val depthView = device.createTextureView(depth)

            // RenderSystem's modelview during entity rendering is the camera rotation only (vanilla folds the
            // -camera translation into entity geometry), so place the model camera-relative like the player shim.
            val camera = minecraft.gameRenderer.mainCamera.position
            val pos = entity.getPosition(partialTick)
            val yawRadians = Math.toRadians((180.0 - yaw + yawOffset)).toFloat()
            val bodyMatrix = Matrix4f()
                .translation(
                    (pos.x - camera.x).toFloat(),
                    (pos.y - camera.y).toFloat(),
                    (pos.z - camera.z).toFloat(),
                )
                .rotateY(yawRadians)
                .scale(npcScale)
            renderer.drawScene(instance, GlbRenderTarget(colorView, depthView), bodyMatrix, packedLight)
        } catch (error: RuntimeException) {
            LOGGER.error("glbrender: npc draw failed", error)
        }
    }

    private fun startLoad() {
        loadStarted = true
        val path = modelPath ?: return
        CoroutineScope(RenderThreadDispatcher).launch {
            try {
                val loaded = parse(path)
                val model = requireNotNull(loaded.model) { "Loader returned no model" }
                val loadedScene = ModelLoader.Factory.create().loadModel(model)
                    ?: error("Model loaded into no scene")
                scene = loadedScene
                renderer = RendererTypeHolder.vertexShaderTransform.create()
                LOGGER.info("glbrender: npc scene ready for {} ({} nodes)", path, loadedScene.nodes.size)
            } catch (error: Throwable) {
                loadFailed = true
                LOGGER.error("glbrender: could not load npc model $path", error)
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

    private fun resolveModelPath(): Path? {
        val gameDir = FMLPaths.GAMEDIR.get()
        val configured = System.getProperty("glbrender.npcModel")?.takeIf { it.isNotBlank() }
        if (configured != null) {
            val given = Path.of(configured)
            return sequenceOf(given, gameDir.resolve(configured), gameDir.resolve("models").resolve(configured))
                .firstOrNull { it.toFile().isFile }
                ?: run {
                    LOGGER.warn("glbrender: -Dglbrender.npcModel={} not found under {}", configured, gameDir)
                    null
                }
        }
        return gameDir.resolve("models").resolve("luka.glb").takeIf { it.toFile().isFile }
            ?: gameDir.resolve("luka.glb").takeIf { it.toFile().isFile }
    }

    private object RenderThreadDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            Minecraft.getInstance().execute(block)
        }
    }
}
