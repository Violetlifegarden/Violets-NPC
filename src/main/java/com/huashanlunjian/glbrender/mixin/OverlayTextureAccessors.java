package com.huashanlunjian.glbrender.mixin;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Same problem as {@link LightTextureAccessors}: the block/pack overlay atlas is only reachable through
 * a private field in 1.21.1.
 */
@Mixin(OverlayTexture.class)
public interface OverlayTextureAccessors {
    @Accessor("texture")
    DynamicTexture getGlbrenderOverlayAtlas();
}
