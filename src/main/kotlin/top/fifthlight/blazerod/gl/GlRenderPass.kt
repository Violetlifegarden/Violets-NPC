package top.fifthlight.blazerod.gl

import top.fifthlight.blazerod.render.format.GlbElementType
import top.fifthlight.blazerod.render.format.GlbElementUsage
import top.fifthlight.blazerod.render.format.GlbIndexType
import top.fifthlight.blazerod.render.format.GlbPrimitiveMode
import top.fifthlight.blazerod.render.format.GlbVertexFormat
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL32
import org.lwjgl.opengl.GL43
import java.util.OptionalDouble
import java.util.OptionalInt
import java.util.function.Supplier

/**
 * Stand-in for 1.21.2's `com.mojang.blaze3d.systems.RenderPass`. A pass is the whole of a
 * primitive's draw here: Minecraft 1.21.1 has no deferred command buffer, so state is applied
 * immediately and the state a pass touched is restored on [close], because Minecraft's own
 * `GlStateManager` caches those states and would not re-apply them otherwise.
 */
class RenderPass private constructor(
    private val label: String?,
    private val saved: SavedState,
) : AutoCloseable {
    private var pipeline: RenderPipeline? = null
    private var program: GlProgram? = null
    private var vertexFormat: GlbVertexFormat? = null
    private var vertexBuffer: GpuBufferSlice? = null
    private var indexBuffer: GpuBufferSlice? = null
    private var indexType: GlbIndexType? = null
    private var drawMode = GL11.GL_TRIANGLES
    private val texelUnits = HashMap<String, Int>()
    private var nextTexelUnit = 0

    private val currentPipeline: RenderPipeline
        get() = pipeline ?: throw IllegalStateException("Render pass ${label ?: "unnamed"} has no pipeline")

    private val currentProgram: GlProgram
        get() = program ?: throw IllegalStateException("Render pass ${label ?: "unnamed"} has no pipeline")

    fun setPipeline(pipeline: RenderPipeline) {
        this.pipeline = pipeline
        val program = pipeline.program
        this.program = program
        GL20.glUseProgram(program.id)
        vertexFormat = pipeline.vertexFormat
        texelUnits.clear()
        nextTexelUnit = pipeline.samplers.size

        setBlend(pipeline.blendFunction)
        if (pipeline.isCullEnabled) {
            GL11.glEnable(GL11.GL_CULL_FACE)
            GL11.glCullFace(GL11.GL_BACK)
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE)
        }
        Gl.checkError("set pipeline ${pipeline.label}")
    }

    fun setVertexFormat(format: GlbVertexFormat) {
        vertexFormat = format
    }

    fun setVertexFormatMode(mode: GlbPrimitiveMode) {
        drawMode = mode.asGLMode
    }

    /**
     * The ported renderers only ever use one vertex buffer for a whole format, which is also how
     * Minecraft 1.21.1's own immediate-mode attribute setup works, so [index] is accepted but the
     * format is applied from that one binding.
     */
    fun setVertexBuffer(index: Int, buffer: GpuBuffer) {
        vertexBuffer = buffer.slice()
    }

    fun setVertexBuffer(index: Int, slice: GpuBufferSlice) {
        vertexBuffer = slice
    }
    fun setIndexBuffer(buffer: GpuBuffer, type: GlbIndexType) {
        setIndexBuffer(buffer.slice(), type)
    }

    fun setIndexBuffer(slice: GpuBufferSlice, type: GlbIndexType) {
        indexBuffer = slice
        indexType = type
    }

    fun setUniform(name: String, slice: GpuBufferSlice) {
        val program = currentProgram
        val uniform = currentPipeline.uniform(name)
        when (uniform.type) {
            UniformType.UNIFORM_BUFFER -> {
                GL30.glBindBufferRange(
                    GL31.GL_UNIFORM_BUFFER,
                    program.uniformBlockBinding(name),
                    slice.buffer().id,
                    slice.offset().toLong(),
                    slice.length().toLong(),
                )
                Gl.checkError("set uniform $name")
            }

            UniformType.TEXEL_BUFFER -> bindTexelBuffer(name, uniform.format, slice)
        }
    }

    /** A texel-buffer uniform covers a whole buffer; the morph and skinning data use this form. */
    fun setUniform(name: String, buffer: GpuBuffer) {
        val uniform = currentPipeline.uniform(name)
        when (uniform.type) {
            UniformType.UNIFORM_BUFFER -> setUniform(name, buffer.slice())
            UniformType.TEXEL_BUFFER -> bindTexelBuffer(name, uniform.format, buffer.slice())
        }
    }

    fun setStorageBuffer(name: String, slice: GpuBufferSlice) {
        GL30.glBindBufferRange(
            GL43.GL_SHADER_STORAGE_BUFFER,
            currentProgram.storageBlockBinding(name),
            slice.buffer().id,
            slice.offset().toLong(),
            slice.length().toLong(),
        )
        Gl.checkError("set storage buffer $name")
    }

    fun bindSampler(name: String, view: GpuTextureView) {
        val unit = currentPipeline.samplerSlot(name)
        view.bind(unit)
        GL20.glUniform1i(currentProgram.uniformLocation(name), unit)
        GL13.glActiveTexture(saved.activeTexture)
        Gl.checkError("bind sampler $name")
    }

    fun draw(firstVertex: Int, count: Int) = draw(firstVertex, count, 1)

    fun draw(firstVertex: Int, count: Int, instanceCount: Int) {
        applyVertexState()
        if (instanceCount > 1) {
            GL31.glDrawArraysInstanced(drawMode, firstVertex, count, instanceCount)
        } else {
            GL11.glDrawArrays(drawMode, firstVertex, count)
        }
        Gl.checkError("draw $label")
    }

    fun drawIndexed(baseVertex: Int, firstIndex: Int, count: Int, instanceCount: Int) {
        val buffer = indexBuffer ?: throw IllegalStateException("Indexed draw without an index buffer")
        val type = indexType ?: throw IllegalStateException("Indexed draw without an index type")
        applyVertexState()
        val offset = buffer.offset() + firstIndex * type.bytes
        if (instanceCount > 1) {
            GL32.glDrawElementsInstanced(drawMode, count, type.asGLType, offset.toLong(), instanceCount)
        } else if (baseVertex != 0) {
            GL32.glDrawElementsBaseVertex(drawMode, count, type.asGLType, offset.toLong(), baseVertex)
        } else {
            GL11.glDrawElements(drawMode, count, type.asGLType, offset.toLong())
        }
        Gl.checkError("drawIndexed $label")
    }

    override fun close() {
        GL20.glUseProgram(0)
        saved.restore()
    }

    private fun bindTexelBuffer(name: String, format: TextureFormat?, slice: GpuBufferSlice) {
        val texelFormat = format
            ?: throw IllegalArgumentException("Uniform $name is a texel buffer without a format")
        val view = slice.buffer().texelView(texelFormat, 0, slice.buffer().size)
        val unit = texelUnits.getOrPut(name) { nextTexelUnit++ }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
        GL31.glBindTexture(GL31.GL_TEXTURE_BUFFER, view)
        GL20.glUniform1i(currentProgram.uniformLocation(name), unit)
        GL13.glActiveTexture(saved.activeTexture)
        Gl.checkError("bind texel buffer $name")
    }

    /**
     * Mirrors Minecraft 1.21.1's own `VertexFormat._setupBufferState` for the normalised-ness and integer
     * handling, but the locations come from the linked program by attribute name: nothing in the shader
     * source asks for a location, so assuming the element index would leave it to the driver.
     */
    private fun applyVertexState() {
        val format = vertexFormat ?: return
        val buffer = vertexBuffer ?: return
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer.buffer().id)
        // The vertex array object is shared by every pass, so arrays a previous format left enabled have to
        // go off before this one's attributes are read; a stale one points at another buffer.
        for (location in 0 until GlVertexArray.attributeCount) {
            GL20.glDisableVertexAttribArray(location)
        }
        val stride = format.vertexSize
        for (index in 0 until format.elements.size) {
            val element = format.elements[index]
            val offset = buffer.offset() + element.offset
            val location = currentProgram.attributeLocation(element.name)
            if (location < 0) {
                // The shader does not use this attribute, which is normal for a model without colors.
                continue
            }
            GL20.glEnableVertexAttribArray(location)
            when (element.usage) {
                // Joint indices are unsigned shorts read by an `ivec4` input, so an integer-typed
                // attribute has to go through glVertexAttribIPointer; feeding a float pointer to an
                // integer input is an invalid operation at draw time.
                GlbElementUsage.POSITION, GlbElementUsage.GENERIC ->
                    if (element.type == GlbElementType.FLOAT) {
                        GL20.glVertexAttribPointer(
                            location, element.count, element.type.glType, false, stride, offset.toLong()
                        )
                    } else {
                        GL30.glVertexAttribIPointer(
                            location, element.count, element.type.glType, stride, offset.toLong()
                        )
                    }

                GlbElementUsage.NORMAL, GlbElementUsage.COLOR ->
                    GL20.glVertexAttribPointer(location, element.count, element.type.glType, true, stride, offset.toLong())

                GlbElementUsage.UV -> if (element.type == GlbElementType.FLOAT) {
                    GL20.glVertexAttribPointer(location, element.count, element.type.glType, false, stride, offset.toLong())
                } else {
                    GL30.glVertexAttribIPointer(location, element.count, element.type.glType, stride, offset.toLong())
                }
            }
        }
        indexBuffer?.let { indices ->
            GL15.glBindBuffer(GL31.GL_ELEMENT_ARRAY_BUFFER, indices.buffer().id)
        }
        Gl.checkError("apply vertex format ${format.elements.size} attributes")
    }

    private fun setBlend(blend: BlendFunction?) {
        if (blend == null) {
            GL11.glDisable(GL11.GL_BLEND)
        } else {
            GL11.glEnable(GL11.GL_BLEND)
            GL14.glBlendFuncSeparate(
                blend.colorSourceFactor,
                blend.colorDestFactor,
                blend.alphaSourceFactor,
                blend.alphaDestFactor,
            )
        }
    }

    private class SavedState(
        val framebuffer: Int,
        val activeTexture: Int,
        val vertexArray: Int,
        private val viewport: IntArray,
        private val blend: Boolean,
        private val cull: Boolean,
        private val depthTest: Boolean,
        private val depthWrite: Boolean,
        private val srcRgb: Int,
        private val dstRgb: Int,
        private val srcAlpha: Int,
        private val dstAlpha: Int,
        private val arrayBuffer: Int,
        private val elementArrayBuffer: Int,
    ) {
        fun restore() {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
            GL30.glBindVertexArray(vertexArray)
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer)
            GL15.glBindBuffer(GL31.GL_ELEMENT_ARRAY_BUFFER, elementArrayBuffer)
            GL13.glActiveTexture(activeTexture)
            if (blend) {
                GL11.glEnable(GL11.GL_BLEND)
                GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha)
            } else {
                GL11.glDisable(GL11.GL_BLEND)
            }
            if (cull) GL11.glEnable(GL11.GL_CULL_FACE) else GL11.glDisable(GL11.GL_CULL_FACE)
            if (depthTest) GL11.glEnable(GL11.GL_DEPTH_TEST) else GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glDepthMask(depthWrite)
        }
    }

    companion object {
        /**
         * A pass that draws into the game's own target still needs a framebuffer object with those
         * textures attached, so one is created per texture pair and kept for the session.
         */
        internal fun open(
            labelGetter: Supplier<String>?,
            color: GpuTextureView,
            clearColor: OptionalInt,
            depth: GpuTextureView?,
            clearDepth: OptionalDouble,
        ): RenderPass {
            val label = Gl.label(labelGetter)
            val framebuffer = GlFramebuffers.framebufferFor(color, depth)
            val viewport = IntArray(4)
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport)
            val saved = SavedState(
                // Only the *_BINDING names are queryable; GL_FRAMEBUFFER itself is an attach target.
                framebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
                vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),
                viewport = viewport,
                blend = GL11.glIsEnabled(GL11.GL_BLEND),
                cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
                dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
                arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),
                elementArrayBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING),
            )
            Gl.checkError("save state for pass ${label ?: "unnamed"}")
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
            // Minecraft asks for a core-profile context, where attribute state may only be written into a
            // vertex array object of our own; there is no default one to poke at.
            GL30.glBindVertexArray(GlVertexArray.id)
            // The ported pipelines carry no depth state of their own, and the game leaves these two bits
            // wherever its last render type put them. A pass opened mid-frame (entity rendering, say)
            // would otherwise neither test against nor write the depth buffer, and everything the level
            // draws afterwards - translucent blocks, clouds - would land on top of the model.
            GL11.glEnable(GL11.GL_DEPTH_TEST)
            GL11.glDepthMask(true)
            // The pass owns the viewport of its target, the way 1.21.2's passes do; the ported
            // renderers never set one themselves.
            GL11.glViewport(0, 0, color.width, color.height)
            if (clearDepth.isPresent) {
                GL11.glDepthMask(true)
                GL11.glClearDepth(clearDepth.asDouble)
            }
            if (clearColor.isPresent || clearDepth.isPresent) {
                var mask = 0
                if (clearColor.isPresent) {
                    val argb = clearColor.asInt
                    GL11.glClearColor(
                        ((argb shr 16) and 0xFF) / 255.0f,
                        ((argb shr 8) and 0xFF) / 255.0f,
                        (argb and 0xFF) / 255.0f,
                        (argb ushr 24) / 255.0f,
                    )
                    mask = mask or GL11.GL_COLOR_BUFFER_BIT
                }
                if (clearDepth.isPresent) mask = mask or GL11.GL_DEPTH_BUFFER_BIT
                GL11.glClear(mask)
            }
            Gl.checkError("open pass ${label ?: "unnamed"}")
            return RenderPass(label, saved)
        }
    }
}

/** Framebuffer objects for the shim's own render targets, keyed by the textures they attach. */
internal object GlFramebuffers {
    private val cache = HashMap<Key, Int>()

    /**
     * The cache key has to carry the attachment *dimensions*, not just their ids. On a window resize this
     * Intel driver keeps the same texture ids (color=4, depth=6) and only reallocates their storage at the new
     * size; an id-only key would then hit an FBO that still reports COMPLETE but is bound to the freed,
     * wrong-sized storage, so every draw silently lands nowhere. Including width/height makes a resize miss the
     * cache and rebuild against the live textures. The completeness re-check below is left in as cheap insurance
     * for drivers that instead delete and id-reuse textures at identical dimensions.
     */
    private data class Key(
        val colorId: Int, val colorW: Int, val colorH: Int,
        val depthId: Int, val depthW: Int, val depthH: Int,
    )

    fun framebufferFor(color: GpuTextureView, depth: GpuTextureView?): Int {
        val key = Key(
            color.texture.id, color.texture.width, color.texture.height,
            depth?.texture?.id ?: 0, depth?.texture?.width ?: 0, depth?.texture?.height ?: 0,
        )
        cache[key]?.let { cached ->
            // Re-check completeness (restoring the ambient binding that the pass is about to save) and rebuild
            // if the attachments were dropped, e.g. a driver that freed and id-reused the textures in place.
            val previousBinding = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, cached)
            val complete = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousBinding)
            if (complete) return cached
            GL30.glDeleteFramebuffers(cached)
            cache.remove(key)
        }

        val id = GL30.glGenFramebuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, id)
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D,
            color.texture.id,
            0,
        )
        depth?.let {
            // Ask the texture instead of trusting the format it was adopted under: Minecraft's main target
            // keeps an unsized depth texture, the shim's own targets are DEPTH24 or DEPTH24_STENCIL8, and a
            // combined texture has to go on the combined attachment point or it is not read as depth at all.
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, it.texture.id)
            val internal = IntArray(1)
            GL11.glGetTexLevelParameteriv(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT, internal)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            val combined = internal[0] == GL30.GL_DEPTH_STENCIL ||
                    internal[0] == GL30.GL_DEPTH24_STENCIL8 ||
                    internal[0] == GL32.GL_DEPTH32F_STENCIL8
            GL30.glFramebufferTexture2D(
                GL30.GL_FRAMEBUFFER,
                if (combined) GL30.GL_DEPTH_STENCIL_ATTACHMENT else GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D,
                it.texture.id,
                0,
            )
        }
        val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        val colorAttachment = IntArray(1)
        val depthAttachment = IntArray(1)
        GL30.glGetFramebufferAttachmentParameteriv(
            GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE,
            colorAttachment,
        )
        GL30.glGetFramebufferAttachmentParameteriv(
            GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE,
            depthAttachment,
        )
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glDeleteFramebuffers(id)
            throw GLException(
                GL11.glGetError(),
                "framebuffer $id incomplete (status 0x${status.toString(16)}) for color ${color.texture.id} " +
                        "(${color.texture.width}x${color.texture.height}, attachment type 0x" +
                        colorAttachment[0].toString(16) + ") and depth ${depth?.texture?.id ?: "-"} " +
                        "(attachment type 0x${depthAttachment[0].toString(16)})",
            )
        }
        Gl.checkError("framebuffer of color texture ${color.texture.id}")
        cache[key] = id
        return id
    }

    /** Number of cached framebuffer objects; used by the in-game diagnostics. */
    val cacheSize: Int get() = cache.size

    /**
     * Drops every cached framebuffer. Called when the game's main render target changes (window resize,
     * fullscreen toggle, minimise/restore), which reallocates its textures and can leave cached FBOs
     * pointing at freed - and soon id-reused - attachments.
     */
    fun invalidateAll() {
        for (id in cache.values) {
            GL30.glDeleteFramebuffers(id)
        }
        cache.clear()
    }
}

/**
 * The one vertex array object the shim draws through. Minecraft asks for a core-profile context, where
 * vertex attribute state only lives inside a VAO, so 1.21.1's own direct `glVertexAttribPointer` setup has
 * nothing to write into unless one is bound.
 */
internal object GlVertexArray {
    val id: Int by lazy { GL30.glGenVertexArrays() }
    val attributeCount: Int by lazy { GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS).coerceAtMost(64) }
}
