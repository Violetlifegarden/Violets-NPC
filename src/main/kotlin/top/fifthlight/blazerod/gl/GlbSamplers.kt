package top.fifthlight.blazerod.gl

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The shared scene atlases the vanilla and unlit pipelines bind: the block+sky lightmap and the overlay
 * (block outline / hurt) atlas. Minecraft owns these textures, so glb-mc supplies the real ones; core
 * only names them and needs a stand-in for contexts with no running client.
 */
interface GlbSamplers {
    val lightMap: GpuTextureView
    val overlay: GpuTextureView
}

/**
 * Fully-lit stand-in atlases: solid-white 16x16 textures, so sampling them leaves color unchanged.
 * Used by the offscreen harness and any glb-core context that renders without the game.
 */
object WhiteSamplers : GlbSamplers {
    private const val SIZE = 16

    override val lightMap: GpuTextureView by lazy { white("SamplerLightMap") }
    override val overlay: GpuTextureView by lazy { white("SamplerOverlay") }

    private fun white(label: String): GpuTextureView {
        val device = Gl.device
        val texture = device.createTexture(
            labelGetter = { label },
            usage = GpuTexture.USAGE_TEXTURE_BINDING,
            format = TextureFormat.RGBA8,
            width = SIZE,
            height = SIZE,
            depth = 1,
            levels = 1,
        )
        val pixels = ByteBuffer
            .allocateDirect(SIZE * SIZE * 4)
            .order(ByteOrder.nativeOrder())
        repeat(SIZE * SIZE) { pixels.put(0xFF.toByte()).put(0xFF.toByte()).put(0xFF.toByte()).put(0xFF.toByte()) }
        pixels.rewind()
        device.createCommandEncoder()
            .writeToTexture(texture, pixels, TextureFormat.RGBA8, 0, 0, 0, 0, SIZE, SIZE)
        return texture.createView(TextureFormat.RGBA8)
    }
}
