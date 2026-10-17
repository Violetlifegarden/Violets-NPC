package top.fifthlight.blazerod.render.render

import top.fifthlight.blazerod.gl.GpuBuffer
import top.fifthlight.blazerod.render.common.util.refcount.AbstractRefCount

class RefCountedGpuBuffer(val inner: GpuBuffer) : AbstractRefCount() {
    override val typeId: String
        get() = "gpu_buffer"

    override fun onClosed() {
        inner.close()
    }
}
