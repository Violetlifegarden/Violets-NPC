package top.fifthlight.blazerod.render.util.minecraft

import com.huashanlunjian.glbrender.mixin.LightTextureAccessors
import com.huashanlunjian.glbrender.mixin.OverlayTextureAccessors
import net.minecraft.client.Minecraft
import top.fifthlight.blazerod.gl.Gl
import top.fifthlight.blazerod.gl.GpuTexture
import top.fifthlight.blazerod.gl.GpuTextureView
import top.fifthlight.blazerod.gl.TextureFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The game's lightmap and overlay atlases, as samplers our pipelines can bind.
 *
 * 1.21.1 keeps both as plain GL texture names inside DynamicTexture objects that are only reachable
 * through private fields (see the accessor mixins), and it has no GPU texture wrapper to hand out, so the
 * ids are adopted read-only: Minecraft owns the lifetime and we must never delete them.
 */
object MinecraftTextures {
    val lightMap: GpuTextureView
        get() = view("SamplerLightMap") {
            val accessors = Minecraft.getInstance().gameRenderer.lightTexture() as LightTextureAccessors
            accessors.glbrenderLightMapTexture.id
        }

    val overlay: GpuTextureView
        get() = view("SamplerOverlay") {
            val accessors = Minecraft.getInstance().gameRenderer.overlayTexture() as OverlayTextureAccessors
            accessors.glbrenderOverlayAtlas.id
        }

    private val views = mutableMapOf<String, GpuTextureView>()

    private fun view(label: String, idGetter: () -> Int): GpuTextureView {
        val id = runCatching(idGetter).getOrNull()
        val cached = views[label]
        if (cached != null && (id == null || cached.texture.id == id)) {
            return cached
        }
        val texture = if (id != null) {
            GpuTexture.adopt(id, TextureFormat.RGBA8)
        } else {
            // No running client: the offscreen render harness still needs a 16x16 atlas to sample.
            whiteFallback(label)
        }
        texture.rename(label)
        return texture.createView(TextureFormat.RGBA8).also { views[label] = it }
    }

    private fun whiteFallback(label: String): GpuTexture {
        val device = Gl.device
        val texture = device.createTexture(
            labelGetter = { label },
            usage = GpuTexture.USAGE_TEXTURE_BINDING,
            format = TextureFormat.RGBA8,
            width = 16,
            height = 16,
            depth = 1,
            levels = 1,
        )
        val pixels = ByteBuffer
            .allocateDirect(16 * 16 * 4)
            .order(ByteOrder.nativeOrder())
        repeat(16 * 16) { pixels.put(0xFF.toByte()).put(0xFF.toByte()).put(0xFF.toByte()).put(0xFF.toByte()) }
        pixels.rewind()
        device.createCommandEncoder()
            .writeToTexture(texture, pixels, TextureFormat.RGBA8, 0, 0, 0, 0, 16, 16)
        return texture
    }
}
