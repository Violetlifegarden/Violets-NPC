package com.huashanlunjian.glbrender.client.npc.preview

import com.mojang.blaze3d.systems.RenderSystem
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import top.fifthlight.blazerod.gl.FilterMode
import top.fifthlight.blazerod.gl.Gl
import top.fifthlight.blazerod.gl.GlbFrameContext
import top.fifthlight.blazerod.gl.GlFramebuffers
import top.fifthlight.blazerod.gl.GpuTexture
import top.fifthlight.blazerod.gl.GpuTextureView
import top.fifthlight.blazerod.gl.TextureFormat
import top.fifthlight.blazerod.model.RgbaColor
import top.fifthlight.blazerod.render.api.render.GlbRenderTarget
import top.fifthlight.blazerod.render.api.render.Renderer
import top.fifthlight.blazerod.render.api.render.drawScene
import top.fifthlight.blazerod.render.api.resource.ModelInstance

/**
 * The offscreen color+depth target the preview screen draws the GLB into before blitting it into the GUI.
 * 1.21.1 has no `RenderSystem.outputColorTextureOverride` (ArmorStand's trick on newer versions), so the
 * renderer draws against textures of our own and the screen samples the color texture as a plain quad.
 *
 * The textures are deliberately owned by this singleton for the whole session: the screen opens and closes
 * a lot while somebody tunes an NPC, and `GlFramebuffers` keys its cache by texture id with no eviction, so
 * rebuilding per open would leak a framebuffer each time. Only the render state is transient.
 */
object GlbPreviewTarget {
    /** Square render size; shown in a 180x180 box, so it stays sharp at GUI scale 2. */
    const val SIZE = 384

    private var color: GpuTexture? = null
    private var depth: GpuTexture? = null
    private var colorView: GpuTextureView? = null
    private var depthView: GpuTextureView? = null

    private val previousClear = FloatArray(4)

    /** The color texture id, for sampling the rendered frame in the GUI. Creates the target on first use. */
    fun textureId(): Int {
        ensureTargets()
        return color!!.id
    }

    /**
     * Draws [instance] into the target with an explicit camera, leaving the target bound with the rendered
     * frame for [textureId] to sample. Everything the pass touches is restored: the renderer's own passes
     * save/restore their state, and the framebuffer, viewport, scissor, depth mask and clear color are saved
     * here because the GUI keeps rendering into the main target right after.
     */
    fun draw(
        instance: ModelInstance,
        renderer: Renderer<*, *>,
        modelMatrix: Matrix4f,
        light: Int,
        tint: RgbaColor,
        materialMode: Int,
        projection: Matrix4f,
        view: Matrix4f,
    ) {
        ensureTargets()
        val colorView = colorView!!
        val depthView = depthView!!

        val savedFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
        val savedViewport = IntArray(4).also { GL11.glGetIntegerv(GL11.GL_VIEWPORT, it) }
        val scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
        val depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        // glGetFloatv is safe on this driver (unlike glGetTexImage/glGetVertexAttrib, which crash it).
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, previousClear)

        if (scissorEnabled) GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, GlFramebuffers.framebufferFor(colorView, depthView))
        GL11.glViewport(0, 0, SIZE, SIZE)
        GL11.glDepthMask(true)
        // Transparent so the panel behind the viewport shows through.
        RenderSystem.clearColor(0f, 0f, 0f, 0f)
        GL11.glClearDepth(1.0)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT)

        val savedContext = Gl.frameContext
        previewContext.projection.set(projection)
        previewContext.view.set(view)
        Gl.frameContext = previewContext
        try {
            renderer.drawScene(
                instance,
                GlbRenderTarget(colorView, depthView),
                modelMatrix,
                light,
                tint = tint,
                materialMode = materialMode,
            )
        } finally {
            Gl.frameContext = savedContext
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFramebuffer)
            GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3])
            GL11.glDepthMask(depthWrite)
            // RenderSystem owns the clear-color cache, so restoring through it keeps the GUI consistent.
            RenderSystem.clearColor(previousClear[0], previousClear[1], previousClear[2], previousClear[3])
            if (scissorEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST)
        }
    }

    private fun ensureTargets() {
        if (color != null) return
        val device = Gl.device
        val newColor = device.createTexture(
            labelGetter = { "glb preview color" },
            usage = GpuTexture.USAGE_RENDER_TARGET,
            format = TextureFormat.RGBA8,
            width = SIZE,
            height = SIZE,
            depth = 1,
            levels = 1,
        )
        // Created textures default to NEAREST; the preview quad is scaled, so sample it linearly.
        newColor.setTextureFilter(FilterMode.LINEAR, FilterMode.LINEAR, false)
        val newDepth = device.createTexture(
            labelGetter = { "glb preview depth" },
            usage = GpuTexture.USAGE_RENDER_TARGET,
            format = TextureFormat.DEPTH24,
            width = SIZE,
            height = SIZE,
            depth = 1,
            levels = 1,
        )
        color = newColor
        depth = newDepth
        colorView = device.createTextureView(newColor)
        depthView = device.createTextureView(newDepth)
    }

    /**
     * The preview camera is passed in explicitly instead of going through [RenderSystem], whose projection
     * and model-view belong to the GUI being drawn around this frame.
     */
    private object PreviewFrameContext : GlbFrameContext {
        val projection = Matrix4f()
        val view = Matrix4f()

        override fun projectionMatrix(): Matrix4fc = projection
        override fun modelViewMatrix(): Matrix4fc = view

        override fun fogColor(dest: Vector3f) {
            dest.set(0f, 0f, 0f)
        }

        override fun fogShape(): Float = 0f
        override fun fogStart(): Float = Float.MAX_VALUE
        override fun fogEnd(): Float = Float.MAX_VALUE
    }

    private val previewContext = PreviewFrameContext
}
