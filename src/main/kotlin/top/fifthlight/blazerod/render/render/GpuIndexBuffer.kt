package top.fifthlight.blazerod.render.render

import com.mojang.blaze3d.vertex.VertexFormat
import top.fifthlight.blazerod.gl.RenderPass
import top.fifthlight.blazerod.render.common.util.refcount.AbstractRefCount

class GpuIndexBuffer(
    val type: VertexFormat.IndexType,
    val length: Int,
    val buffer: RefCountedGpuBuffer,
) : AbstractRefCount() {
    override val typeId: String
        get() = "index_buffer"

    init {
        buffer.increaseReferenceCount()
        require(buffer.inner.size == length * type.bytes) { "Index buffer size mismatch: ${buffer.inner.size} != $length * ${type.bytes}" }
    }

    override fun onClosed() {
        buffer.decreaseReferenceCount()
    }
}

fun RenderPass.setIndexBuffer(indexBuffer: GpuIndexBuffer) = setIndexBuffer(indexBuffer.buffer.inner, indexBuffer.type)
