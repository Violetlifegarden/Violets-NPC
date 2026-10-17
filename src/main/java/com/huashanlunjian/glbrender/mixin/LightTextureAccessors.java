package com.huashanlunjian.glbrender.mixin;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The ported renderer samples the game's lightmap, but 1.21.1 has no GPU texture object to hand out
 * and LightTexture keeps its atlas in a private field, so the field itself has to be read.
 */
@Mixin(LightTexture.class)
public interface LightTextureAccessors {
    @Accessor("lightTexture")
    DynamicTexture getGlbrenderLightMapTexture();
}
