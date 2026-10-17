package com.huashanlunjian.glbrender.tools

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexSorting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.lwjgl.glfw.Callbacks
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL43
import org.lwjgl.system.MemoryUtil
import top.fifthlight.blazerod.gl.Gl
import top.fifthlight.blazerod.gl.GlFramebuffers
import com.huashanlunjian.glbrender.render.RenderSystemFrameContext
import top.fifthlight.blazerod.gl.GpuTexture
import top.fifthlight.blazerod.gl.TextureFormat
import top.fifthlight.blazerod.model.loader.LoadContext
import top.fifthlight.blazerod.model.loader.ModelFileLoader
import top.fifthlight.blazerod.model.animation.Animation
import top.fifthlight.blazerod.render.api.animation.AnimationItem
import top.fifthlight.blazerod.render.api.animation.AnimationItemInstance
import top.fifthlight.blazerod.render.api.loader.ModelLoader
import top.fifthlight.blazerod.render.api.render.RendererTypeHolder
import top.fifthlight.blazerod.render.api.render.GlbRenderTarget
import top.fifthlight.blazerod.render.api.render.drawScene
import top.fifthlight.blazerod.render.api.resource.ModelInstance
import top.fifthlight.blazerod.render.api.resource.RenderScene
import top.fifthlight.blazerod.render.runtime.animation.AnimationItemImpl
import top.fifthlight.blazerod.render.common.BlazeRod
import top.fifthlight.blazerod.render.runtime.BlazeRodRuntime
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.concurrent.Executors
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Draws a GLB into an offscreen framebuffer with the ported renderer and writes a PNG, without starting
 * Minecraft, so a renderer bug is never confused with a game-integration bug.
 * Usage: gradlew offscreenRender -Pglb=test-models/deepseek-chan.glb
 */
fun main(args: Array<String>) {
    val modelPath = Path.of(args[0])
    val outputPath = Path.of(args.getOrNull(1) ?: "build/offscreen-render.png")
    val size = 512

    GLFWErrorCallback.createPrint(System.err).set()
    check(GLFW.glfwInit()) { "glfwInit failed" }
    GLFW.glfwDefaultWindowHints()
    GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
    GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE)
    GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_DEBUG_CONTEXT, GLFW.GLFW_TRUE)
    val window = GLFW.glfwCreateWindow(size, size, "glbrender offscreen test", MemoryUtil.NULL, MemoryUtil.NULL)
    check(window != MemoryUtil.NULL) { "glfwCreateWindow failed" }

    BlazeRodRuntime.register()
    // A GL context is current on exactly one thread, and the loader pins its GPU stage to
    // BlazeRod.mainDispatcher, so that dispatcher has to be the thread holding this context. In the game
    // the same role is played by the render thread.
    val glExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable).apply { name = "gl" }
    }
    val glDispatcher = glExecutor.asCoroutineDispatcher()
    BlazeRod.mainDispatcher = glDispatcher

    try {
        runBlocking {
            CoroutineScope(glDispatcher).async { render(window, modelPath, outputPath, size) }.await()
        }
    } finally {
        glExecutor.shutdown()
        Callbacks.glfwFreeCallbacks(window)
        GLFW.glfwDestroyWindow(window)
        GLFW.glfwTerminate()
        GLFW.glfwSetErrorCallback(null)?.free()
    }
}

private suspend fun render(window: Long, modelPath: Path, outputPath: Path, size: Int) = coroutineScope {
    GLFW.glfwMakeContextCurrent(window)
    GL.createCapabilities()
    // The renderer reads its matrices through RenderSystem, which insists on being used from the render
    // thread; this thread is that one here, exactly like the render thread is in the game.
    RenderSystem.initRenderThread()
    GL11.glEnable(GL43.GL_DEBUG_OUTPUT)
    GL11.glEnable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS)
    GL43.glDebugMessageCallback({ source, type, _, severity, length, message, _ ->
        if (severity != GL43.GL_DEBUG_SEVERITY_NOTIFICATION) {
            println(
                "gl debug    = src=0x${source.toString(16)} type=0x${type.toString(16)} " +
                        MemoryUtil.memUTF8(message, length.toInt())
            )
        }
    }, MemoryUtil.NULL)

    val fileLoader = ServiceLoader.load(ModelFileLoader::class.java).toList()
        .firstOrNull { modelPath.extension.lowercase() in it.extensions.keys }
        ?: error("No ModelFileLoader for ${modelPath.extension}")
    val header = ByteBuffer.wrap(modelPath.inputStream().use { it.readNBytes(fileLoader.probeLength ?: 4) })
        .order(ByteOrder.LITTLE_ENDIAN)
    check(fileLoader.probe(header)) { "${fileLoader.javaClass.simpleName} rejected $modelPath" }
    val loadResult = fileLoader.load(modelPath, LoadContext.File(modelPath.parent))
    val model = loadResult.model ?: error("Loader returned no model")

    val scene = ModelLoader.Factory.create().loadModel(model) ?: error("Model loaded into no scene")
    println("model       = ${modelPath.fileName}")
    println("nodes       = ${scene.nodes.size}")

    val instance = ModelInstance.Factory.of(scene)
    instance.updateRenderData(0f)

    // Probe clips first, on throwaway instances of its own: the render below releases its task, which can
    // drop the scene refcount to zero and close it, and a closed scene cannot back new instances.
    probeAnimations(scene, loadResult.animations ?: emptyList())

    val device = Gl.device
    val color = device.createTexture(
        labelGetter = { "offscreen color" },
        usage = GpuTexture.USAGE_RENDER_TARGET,
        format = TextureFormat.RGBA8,
        width = size,
        height = size,
        depth = 1,
        levels = 1,
    )
    val depth = device.createTexture(
        labelGetter = { "offscreen depth" },
        usage = GpuTexture.USAGE_RENDER_TARGET,
        format = TextureFormat.DEPTH24,
        width = size,
        height = size,
        depth = 1,
        levels = 1,
    )

    val colorView = device.createTextureView(color)
    val depthView = device.createTextureView(depth)
    // The renderer opens a pass per primitive against these views, and a pass that asks for no clear
    // expects the target to be cleared already, so the framebuffer is prepared here.
    val framebuffer = GlFramebuffers.framebufferFor(colorView, depthView)
    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
    GL11.glViewport(0, 0, size, size)
    GL11.glClearColor(0.1f, 0.1f, 0.15f, 1f)
    GL11.glClearDepth(1.0)
    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT)
    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)

    // A character rig stands at the origin with +y up and is about head height tall, so the camera looks
    // at it from a couple of meters away.
    RenderSystem.setProjectionMatrix(
        Matrix4f().perspective(Math.toRadians(40.0).toFloat(), 1f, 0.05f, 100f),
        VertexSorting.DISTANCE_TO_ORIGIN,
    )
    RenderSystem.getModelViewStack().set(
        Matrix4f().lookAt(0f, 0.9f, 2.5f, 0f, 0.9f, 0f, 0f, 1f, 0f)
    )
    // The camera is a couple of meters away, so keep the fog beyond it or everything renders fogged.
    RenderSystem.setShaderFogColor(0.1f, 0.1f, 0.15f)
    RenderSystem.setShaderFogStart(20f)
    RenderSystem.setShaderFogEnd(40f)
    Gl.frameContext = RenderSystemFrameContext

    val renderer = RendererTypeHolder.vertexShaderTransform.create()
    renderer.drawScene(instance, GlbRenderTarget(colorView, depthView), Matrix4f(), FULL_BRIGHT)
    GL11.glFinish()

    val pixels = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer)
    GL11.glReadPixels(0, 0, size, size, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
    Gl.checkError("read pixels")
    pixels.rewind()

    val image = NativeImage(NativeImage.Format.RGBA, size, size, false)
    // glReadPixels starts at the bottom left, PNG at the top left.
    val firstPixel = IntArray(4) { pixels.get(it).toInt() and 0xFF }
    var drawn = 0
    for (y in 0 until size) {
        val row = (size - 1 - y) * size * 4
        for (x in 0 until size) {
            val o = row + x * 4
            if ((0 until 3).any { pixels.get(o + it).toInt() and 0xFF != firstPixel[it] }) {
                drawn++
            }
            image.setPixelRGBA(
                x, y,
                (pixels.get(o).toInt() and 0xFF) or
                        ((pixels.get(o + 1).toInt() and 0xFF) shl 8) or
                        ((pixels.get(o + 2).toInt() and 0xFF) shl 16) or
                        ((pixels.get(o + 3).toInt() and 0xFF) shl 24),
            )
        }
    }
    outputPath.toAbsolutePath().parent?.toFile()?.mkdirs()
    image.writeToFile(outputPath.toFile())
    image.close()

    println("written     = ${outputPath.toAbsolutePath()}")
    println("pixels      = $drawn / ${size * size}")
}

/** LightTexture.FULL_BRIGHT, without pulling in the client class. */
private const val FULL_BRIGHT = 15728880

/**
 * Drives every clip across five samples of its duration and reports how much each bone's world matrix
 * changes, so clip playback can be proven on a machine with no player. Each clip is sampled on a pristine
 * instance of its own: clips write into the ABSOLUTE slot, which clearTransform leaves alone, so a shared
 * instance would make an earlier clip's leftover value look like the next clip's motion. A clip that
 * resolves no channel target moves nothing, which is the failure this catches.
 */
private fun probeAnimations(scene: RenderScene, animations: List<Animation>) {
    if (animations.isEmpty()) {
        println("anim probe  = no clips (this model uses the procedural-only path)")
        return
    }
    println("anim probe  = ${animations.size} clip(s)")

    val nodeCount = scene.nodes.size
    val samples = 13
    val m = Matrix4f()
    val t = Vector3f()
    val s = Vector3f()
    val q = Quaternionf()

    // A scalar fingerprint that reacts to all three glTF animation paths (translation, scale, rotation);
    // the decomposed world matrix is enough to tell whether an animated bone actually changed.
    fun fingerprint(instance: ModelInstance, index: Int): Float {
        instance.copyNodeWorldTransform(index, m)
        m.getTranslation(t)
        m.getScale(s)
        m.getUnnormalizedRotation(q)
        return kotlin.math.abs(t.x) + kotlin.math.abs(t.y) + kotlin.math.abs(t.z) +
                kotlin.math.abs(s.x) + kotlin.math.abs(s.y) + kotlin.math.abs(s.z) +
                kotlin.math.abs(q.x) + kotlin.math.abs(q.y) + kotlin.math.abs(q.z) + kotlin.math.abs(q.w)
    }

    animations.forEachIndexed { clipIndex, animation ->
        val instance = ModelInstance.Factory.of(scene)
        instance.updateRenderData(0f)
        val baseline = FloatArray(nodeCount) { fingerprint(instance, it) }

        val item = AnimationItem.Factory.load(scene, animation)
        val itemInstance = AnimationItemInstance.Factory.of(item)
        val context = TimedAnimationContext()
        val state = itemInstance.createState(context)

        val duration = animation.duration ?: 2f
        val ticksPerSample = (duration * 20f / samples).toInt().coerceAtLeast(1)

        val moved = FloatArray(nodeCount)
        for (sample in 0 until samples) {
            context.tick = (sample * ticksPerSample).toLong()
            state.updateTime(context)
            itemInstance.apply(instance, itemInstance.update(context, state))
            instance.updateRenderData(0f)
            for (i in 0 until nodeCount) {
                val delta = kotlin.math.abs(fingerprint(instance, i) - baseline[i])
                if (delta > moved[i]) moved[i] = delta
            }
        }

        val movedNodes = (0 until nodeCount).count { moved[it] > 1e-4f }
        val maxDelta = moved.maxOrNull() ?: 0f
        val impl = item as AnimationItemImpl
        val resolved = impl.channels.size
        if (movedNodes == 0 && resolved > 0) {
            // dump the resolved target bone's live world scale/translation so a flat clip can be told apart
            // from a channel that silently failed to apply
            fun Float.f3() = "%.2f".format(this)
            val targets = impl.channels.mapNotNull { it.targetNodeIndex }
            val diag = targets.joinToString { idx ->
                instance.copyNodeWorldTransform(idx, m)
                m.getTranslation(t); m.getScale(s)
                "node$idx t=(${t.x.f3()},${t.y.f3()},${t.z.f3()}) s=(${s.x.f3()},${s.y.f3()},${s.z.f3()})"
            }
            println("      diag clip[$clipIndex] targets=[$diag] baselineTop=%.3f".format(baseline.maxOrNull() ?: 0f))
        }
        val verdict = when {
            resolved == 0 -> "NOT RESOLVED"
            movedNodes == 0 -> "resolved but static"
            else -> "ok"
        }
        println(
            "  clip[$clipIndex] ${animation.name ?: "<unnamed>"}: " +
                    "channels=${animation.channels.size} resolved=$resolved movedNodes=$movedNodes " +
                    "maxDelta=%.4f %s".format(maxDelta, verdict)
        )
    }
}
