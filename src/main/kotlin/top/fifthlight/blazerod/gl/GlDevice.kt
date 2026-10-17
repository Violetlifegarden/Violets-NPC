package top.fifthlight.blazerod.gl

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL32
import org.lwjgl.opengl.GL42
import org.lwjgl.opengl.GL43
import org.lwjgl.opengl.GL44
import java.nio.ByteBuffer
import java.util.OptionalDouble
import java.util.OptionalInt
import java.util.function.Supplier

/**
 * Stand-in for 1.21.2+'s `com.mojang.blaze3d.systems.GpuDevice`, reached through [Gl.device]
 * instead of `RenderSystem.getDevice()`.
 */
class GpuDevice {
    val isDebugContext: Boolean = false

    val debugLabels: Boolean get() = Gl.isDebugLabelsSupported

    val uniformOffsetAlignment: Int get() = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT)
    val ssboOffsetAlignment: Int get() = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT)
    val textureBufferOffsetAlignment: Int get() = GL11.glGetInteger(GL43.GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT)

    val maxSsboBindings: Int get() = GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS)
    val maxSsboInVertexShader: Int get() = GL11.glGetInteger(GL43.GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS)
    val maxSsboInFragmentShader: Int get() = GL11.glGetInteger(GL43.GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS)

    val supportSsbo: Boolean get() = Gl.isSsboSupported && maxSsboInVertexShader > 0
    val supportTextureBufferSlice: Boolean get() = Gl.isTextureBufferSupported && Gl.isBufferStorageSupported
    val supportComputeShader: Boolean get() = Gl.isComputeSupported
    val supportMemoryBarrier: Boolean get() = Gl.isMemoryBarrierSupported
    val supportShaderPacking: Boolean get() = Gl.isAtLeast(4, 0)

    fun createBuffer(labelGetter: Supplier<String>?, usage: Int, size: Int): GpuBuffer =
        createBuffer(labelGetter, usage, 0, size)

    fun createBuffer(labelGetter: Supplier<String>?, usage: Int, extraUsage: Int, size: Int): GpuBuffer =
        allocate(labelGetter, usage, extraUsage, size, null)

    fun createBuffer(labelGetter: Supplier<String>?, usage: Int, data: ByteBuffer): GpuBuffer =
        createBuffer(labelGetter, usage, 0, data)

    fun createBuffer(
        labelGetter: Supplier<String>?,
        usage: Int,
        extraUsage: Int,
        data: ByteBuffer,
    ): GpuBuffer = allocate(labelGetter, usage, extraUsage, data.remaining(), data)

    private fun allocate(
        labelGetter: Supplier<String>?,
        usage: Int,
        extraUsage: Int,
        size: Int,
        data: ByteBuffer?,
    ): GpuBuffer {
        Gl.init()
        val name = Gl.label(labelGetter)
        // glBufferData/glBufferStorage act on the buffer bound to <target>, and buffer store is
        // per object rather than per binding point, so allocation always runs through one target.
        val target = GL31.GL_COPY_WRITE_BUFFER
        val id = GL15.glGenBuffers()
        GL30.glBindBuffer(target, id)
        // Texel buffers need immutable storage; below GL 4.4 glBufferData is the only option.
        val usesPersistentStorage =
            GpuBuffer.targetOf(usage, extraUsage) == GL31.GL_TEXTURE_BUFFER && Gl.isBufferStorageSupported
        if (usesPersistentStorage) {
            var flags = GL44.GL_DYNAMIC_STORAGE_BIT
            if (usage and GpuBuffer.USAGE_MAP_WRITE != 0) {
                flags = flags or GL44.GL_MAP_WRITE_BIT or GL44.GL_MAP_PERSISTENT_BIT
            }
            if (usage and GpuBuffer.USAGE_MAP_READ != 0) {
                flags = flags or GL44.GL_MAP_READ_BIT or GL44.GL_MAP_PERSISTENT_BIT
            }
            if (data != null) {
                GL44.glBufferStorage(target, data, flags)
                Gl.checkError("glBufferStorage(data)")
            } else {
                GL44.glBufferStorage(target, size.toLong(), flags)
                Gl.checkError("glBufferStorage($size)")
            }
        } else {
            val glUsage = if (usage and (GpuBuffer.USAGE_MAP_WRITE or GpuBuffer.USAGE_MAP_READ) != 0) {
                GL15.GL_DYNAMIC_DRAW
            } else {
                GL15.GL_STATIC_DRAW
            }
            if (data != null) GL15.glBufferData(target, data, glUsage)
            else GL15.glBufferData(target, size.toLong(), glUsage)
        }
        GL30.glBindBuffer(target, 0)
        Gl.setBufferLabel(id, name)
        Gl.checkError("create buffer ${name ?: "unnamed"}")
        return GpuBuffer(id, size, usage, extraUsage, usesPersistentStorage, name)
    }

    fun createTexture(
        labelGetter: Supplier<String>?,
        usage: Int,
        format: TextureFormat,
        width: Int,
        height: Int,
        depth: Int,
        levels: Int,
    ): GpuTexture = GpuTexture.create(labelGetter, usage, format, width, height, depth, levels)

    fun createTextureView(texture: GpuTexture): GpuTextureView =
        texture.createView(texture.format)

    /** [vertexSource] and [fragmentSource] still carry `#moj_import` and `#blazerod_*` directives. */
    fun createShaderProgram(
        labelGetter: Supplier<String>?,
        defines: ShaderDefines,
        vertexSource: String,
        fragmentSource: String,
    ): GlProgram {
        Gl.init()
        val preprocessor = GlslPreprocessor(Gl.shaderSources)
        return GlProgram.create(
            labelGetter,
            preprocessor.process(vertexSource, defines),
            preprocessor.process(fragmentSource, defines),
        )
    }

    fun createCommandEncoder(): CommandEncoder = CommandEncoder()
}

class CommandEncoder internal constructor() {
    fun mapBuffer(slice: GpuBufferSlice, allowRead: Boolean, allowWrite: Boolean): MappedMemorySegment =
        slice.buffer().map(slice.offset(), slice.length(), allowRead, allowWrite)

    /**
     * Opens a pass against [color]: the ported renderers draw into the game's own target views rather
     * than an offscreen texture, matching 1.21.2's `createRenderPass` argument order.
     */
    fun createRenderPass(
        label: Supplier<String>,
        color: GpuTextureView,
        clearColor: OptionalInt,
        depth: GpuTextureView?,
        clearDepth: OptionalDouble,
    ): RenderPass = RenderPass.open(label, color, clearColor, depth, clearDepth)

    fun createFence(): GpuFence = GpuFence.create()

    /**
     * Uploads [width] x [height] texels into a 2D texture. 1.21.1 has no buffer-to-texture blit, so this
     * reads client memory directly; [data] must be direct, and the texture's own format decides how the
     * bytes are interpreted.
     */
    fun writeToTexture(
        texture: GpuTexture,
        data: ByteBuffer,
        format: TextureFormat,
        dataOffset: Int,
        dataStride: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        Gl.init()
        val pixelSize = format.pixelSize
        // A short buffer is an out-of-bounds read inside the driver, which dies as an access violation in
        // native code with nothing to show, so the size is checked while it is still a Java exception.
        val needed = width.toLong() * height * pixelSize
        if (data.remaining().toLong() < needed) {
            throw IllegalArgumentException(
                "Upload of ${width}x$height $format needs $needed bytes, buffer has ${data.remaining()}"
            )
        }
        val oldPosition = data.position()
        val oldAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.id)
        // glPixelStorei only accepts 1, 2, 4 and 8, and RGB is 3, so a tight row needs alignment 1.
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, if (pixelSize in intArrayOf(1, 2, 4, 8)) pixelSize else 1)
        val rowLength = if (dataStride > 0) dataStride / pixelSize else width
        if (rowLength != width) {
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLength)
        }
        data.position(dataOffset)
        GL11.glTexSubImage2D(
            GL11.GL_TEXTURE_2D, 0, x, y, width, height,
            texture.format.externalFormat, texture.format.externalType, data,
        )
        data.position(oldPosition)
        if (rowLength != width) {
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0)
        }
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, oldAlignment)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        Gl.checkError("upload to texture ${texture.id}")
    }

    fun memoryBarrier(barriers: Int) {
        GL42.glMemoryBarrier(barriers)
        Gl.checkError("glMemoryBarrier")
    }

    /** 1.21.1 has no command buffers, so a "finish" is only a flush of the client side. */
    fun finish() {
        GL11.glFlush()
    }

    companion object {
        const val BARRIER_VERTEX_BUFFER_BIT =
            GL42.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT or GL42.GL_ELEMENT_ARRAY_BARRIER_BIT
        const val BARRIER_STORAGE_BUFFER_BIT = GL43.GL_SHADER_STORAGE_BARRIER_BIT
    }
}

/** GL sync objects stand in for `GpuFence`; Minecraft 1.21.1 has no fence abstraction. */
class GpuFence private constructor(private var sync: Long) : AutoCloseable {
    fun awaitCompletion(timeout: Long): Int {
        if (sync == 0L) return GL32.GL_ALREADY_SIGNALED
        val result = GL32.glClientWaitSync(sync, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, timeout)
        if (result == GL32.GL_WAIT_FAILED) {
            throw GLException(GL11.glGetError(), "glClientWaitSync")
        }
        return result
    }

    override fun close() {
        if (sync != 0L) {
            GL32.glDeleteSync(sync)
            sync = 0L
        }
    }

    internal companion object {
        fun create(): GpuFence = GpuFence(GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0))
    }
}
