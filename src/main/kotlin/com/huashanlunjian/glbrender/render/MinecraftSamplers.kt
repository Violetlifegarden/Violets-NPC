package com.huashanlunjian.glbrender.render

import com.huashanlunjian.glbrender.mixin.LightTextureAccessors
import com.huashanlunjian.glbrender.mixin.OverlayTextureAccessors
import net.minecraft.client.Minecraft
import top.fifthlight.blazerod.gl.GlbSamplers
import top.fifthlight.blazerod.gl.GpuTexture
import top.fifthlight.blazerod.gl.GpuTextureView
import top.fifthlight.blazerod.gl.TextureFormat
import top.fifthlight.blazerod.gl.WhiteSamplers

/**
 * The game's lightmap and overlay atlases, as samplers our pipelines can bind.
 *
 * 1.21.1 keeps both as plain GL texture names inside DynamicTexture objects that are only reachable
 * through private fields (see the accessor mixins), and it has no GPU texture wrapper to hand out, so the
 * ids are adopted read-only: Minecraft owns the lifetime and we must never delete them.
 */
object MinecraftSamplers : GlbSamplers {
    override val lightMap: GpuTextureView
        get() = view("SamplerLightMap", WhiteSamplers.lightMap) {
            val accessors = Minecraft.getInstance().gameRenderer.lightTexture() as LightTextureAccessors
            accessors.glbrenderLightMapTexture.id
        }

    override val overlay: GpuTextureView
        get() = view("SamplerOverlay", WhiteSamplers.overlay) {
            val accessors = Minecraft.getInstance().gameRenderer.overlayTexture() as OverlayTextureAccessors
            accessors.glbrenderOverlayAtlas.id
        }

    private val views = mutableMapOf<String, GpuTextureView>()

    private fun view(label: String, fallback: GpuTextureView, idGetter: () -> Int): GpuTextureView {
        val id = runCatching(idGetter).getOrNull() ?: return fallback
        val cached = views[label]
        if (cached != null && cached.texture.id == id) {
            return cached
        }
        val texture = GpuTexture.adopt(id, TextureFormat.RGBA8)
        texture.rename(label)
        return texture.createView(TextureFormat.RGBA8).also { views[label] = it }
    }
}
