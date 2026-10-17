package top.fifthlight.blazerod.gl

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL43
import java.nio.ByteBuffer

/**
 * Reimplementation of the `com.mojang.blaze3d.buffers` surface the ported renderer calls, on raw GL.
 * Member names mirror 1.21.2+ so ported call sites need only an import change.
 */
class GpuBuffer internal constructor(
    val id: Int,
    val size: Int,
    private val usageBits: Int,
    val extraUsage: Int,
    private val persistent: Boolean,
    private var label: String?,
) : AutoCloseable {
    fun usage(): Int = usageBits

    fun slice(): GpuBufferSlice = GpuBufferSlice(this, 0, size)

    fun slice(offset: Int, length: Int): GpuBufferSlice {
        require(offset >= 0 && length >= 0 && offset + length <= size) {
            "Slice $offset..${offset + length} out of bounds for buffer of $size bytes"
        }
        return GpuBufferSlice(this, offset, length)
    }

    fun rename(newLabel: String?) {
        label = newLabel
        Gl.setBufferLabel(id, newLabel)
    }

    private val texelViews = mutableMapOf<Triple<TextureFormat, Int, Int>, Int>()

    /**
     * A `samplerBuffer` view over this buffer's storage. GL needs one texture object per view, and the
     * cache belongs to the buffer because a deleted texture id would otherwise be recycled by an
     * unrelated texture and silently read the wrong memory.
     */
    internal fun texelView(format: TextureFormat, offset: Int, size: Int): Int {
        val key = Triple(format, offset, size)
        texelViews[key]?.let { return it }
        val view = GL11.glGenTextures()
        GL31.glBindTexture(GL31.GL_TEXTURE_BUFFER, view)
        GL43.glTexBufferRange(GL31.GL_TEXTURE_BUFFER, format.internalFormat, id, offset.toLong(), size.toLong())
        GL31.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0)
        Gl.setTextureLabel(view, "${label ?: "unnamed"} texel")
        Gl.checkError("texel view of ${label ?: "unnamed"}")
        texelViews[key] = view
        return view
    }

    override fun close() {
        texelViews.values.forEach { GL11.glDeleteTextures(it) }
        texelViews.clear()
        GL15.glDeleteBuffers(id)
        Gl.checkError("delete buffer ${label ?: "unnamed"}")
    }

    internal fun map(offset: Int, length: Int, readable: Boolean, writable: Boolean): MappedMemorySegment {
        val access = mapAccess(readable, writable)
        GL30.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, id)
        val data = GL30.glMapBufferRange(GL31.GL_COPY_WRITE_BUFFER, offset.toLong(), length.toLong(), access)
            ?: throw GLException(GL11.glGetError(), "glMapBufferRange ${label ?: "unnamed"}")
        GL30.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0)
        return MappedMemorySegment(data) {
            GL30.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, id)
            GL30.glUnmapBuffer(GL31.GL_COPY_WRITE_BUFFER)
            GL30.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0)
            Gl.checkError("unmap buffer ${label ?: "unnamed"}")
        }
    }

    private fun mapAccess(readable: Boolean, writable: Boolean): Int {
        var access = 0
        if (readable) access = access or GL30.GL_MAP_READ_BIT
        if (writable) {
            access = access or GL30.GL_MAP_WRITE_BIT
            if (!persistent) {
                access = access or GL30.GL_MAP_UNSYNCHRONIZED_BIT or GL30.GL_MAP_INVALIDATE_RANGE_BIT
            }
        }
        return access
    }

    companion object {
        const val USAGE_MAP_READ = 1
        const val USAGE_MAP_WRITE = 1 shl 1
        const val USAGE_VERTEX = 1 shl 2
        const val USAGE_INDEX = 1 shl 3
        const val USAGE_UNIFORM = 1 shl 4
        const val USAGE_UNIFORM_TEXEL_BUFFER = 1 shl 5
        const val USAGE_COPY_DST = 1 shl 6
        const val USAGE_COPY_SRC = 1 shl 7

        /** BlazeRod's own extra bit: 1.21.2 has no storage-buffer usage flag either. */
        const val EXTRA_USAGE_STORAGE_BUFFER = 1 shl 8

        /**
         * The target a buffer is allocated and specialised against. Generic reads and writes go
         * through COPY_WRITE_BUFFER, so only the specialised targets need to be picked out here.
         */
        internal fun targetOf(usage: Int, extraUsage: Int): Int = when {
            usage and USAGE_UNIFORM_TEXEL_BUFFER != 0 -> GL31.GL_TEXTURE_BUFFER
            usage and USAGE_INDEX != 0 -> GL15.GL_ELEMENT_ARRAY_BUFFER
            usage and USAGE_VERTEX != 0 -> GL15.GL_ARRAY_BUFFER
            usage and USAGE_UNIFORM != 0 -> GL31.GL_UNIFORM_BUFFER
            extraUsage and EXTRA_USAGE_STORAGE_BUFFER != 0 -> GL43.GL_SHADER_STORAGE_BUFFER
            else -> GL31.GL_COPY_WRITE_BUFFER
        }
    }
}

class GpuBufferSlice internal constructor(
    private val gpuBuffer: GpuBuffer,
    private val start: Int,
    private val bytes: Int,
) {
    fun buffer(): GpuBuffer = gpuBuffer

    fun offset(): Int = start

    fun length(): Int = bytes

    fun size(): Int = bytes

    fun slice(): GpuBufferSlice = GpuBufferSlice(gpuBuffer, start, bytes)

    fun slice(offset: Int, length: Int): GpuBufferSlice {
        require(offset >= 0 && length >= 0 && start + offset + length <= gpuBuffer.size) {
            "Slice $offset..${offset + length} out of bounds for $bytes byte view"
        }
        return GpuBufferSlice(gpuBuffer, start + offset, length)
    }

    /** A slice of nothing is a valid no-op binding, and MC hands these to setUniform regularly. */
    val isEmpty get() = bytes == 0
}

class MappedMemorySegment(
    private val data: ByteBuffer,
    private val release: () -> Unit,
) : AutoCloseable {
    fun data(): ByteBuffer = data

    override fun close() = release()
}
