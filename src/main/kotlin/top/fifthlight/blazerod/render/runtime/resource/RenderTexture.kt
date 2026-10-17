package top.fifthlight.blazerod.render.runtime.resource

import top.fifthlight.blazerod.gl.Gl
import top.fifthlight.blazerod.gl.GpuTexture
import top.fifthlight.blazerod.gl.GpuTextureView
import top.fifthlight.blazerod.gl.TextureFormat
import top.fifthlight.blazerod.render.common.util.refcount.AbstractRefCount
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RenderTexture(
    val texture: GpuTexture,
    val view: GpuTextureView,
) : AbstractRefCount() {

    override fun onClosed() {
        // 1.21.1 has no view objects to release; a view is only a reinterpretation of its texture.
        texture.close()
    }

    override val typeId: String
        get() = "gpu_texture"

    companion object {
        val WHITE_RGBA_TEXTURE by lazy {
            // Four white texels; the upload only ever reads the first one, the rest is slack.
            val buffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).apply {
                asIntBuffer().run { repeat(4) { put(0xFFFFFFFFu.toInt()) } }
            }
            Gl.device.let { device ->
                val texture = device.createTexture(
                    { "White RGBA texture" },
                    GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST,
                    TextureFormat.RGBA8,
                    1,
                    1,
                    1,
                    1,
                )
                device.createCommandEncoder().writeToTexture(
                    texture = texture,
                    data = buffer,
                    format = TextureFormat.RGBA8,
                    dataOffset = 0,
                    dataStride = 0,
                    x = 0,
                    y = 0,
                    width = 1,
                    height = 1,
                )
                RenderTexture(texture, device.createTextureView(texture))
            }.apply {
                // Increase ref count to keep it from being closed
                increaseReferenceCount()
            }
        }
    }
}
