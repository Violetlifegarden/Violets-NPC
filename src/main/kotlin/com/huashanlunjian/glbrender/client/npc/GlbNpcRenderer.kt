package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.npc.GlbNpc
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation

/**
 * Renders [GlbNpc] through the glbrender pipeline. It is a pure consumer of the glb-core draw API — it applies no
 * vanilla model geometry, so [getTextureLocation] is only a formality. All the actual work (scene load, per-entity
 * instance, camera-relative body matrix) lives in [GlbNpcModels].
 */
class GlbNpcRenderer(context: EntityRendererProvider.Context) : EntityRenderer<GlbNpc>(context) {

    override fun render(
        entity: GlbNpc,
        yaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
    ) {
        GlbNpcModels.draw(entity, yaw, partialTick, packedLight)
    }

    override fun getTextureLocation(entity: GlbNpc): ResourceLocation = TEXTURE

    companion object {
        private val TEXTURE = ResourceLocation.fromNamespaceAndPath("glbrender", "npc")
    }
}
