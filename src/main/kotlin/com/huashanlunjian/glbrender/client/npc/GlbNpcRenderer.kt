package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.npc.GlbModels
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
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
    ) {
        // entityYaw is intentionally ignored: vanilla always hands the renderer 0.0F and expects it to read rotation
        // off the entity itself (see GlbNpcModels.draw).
        GlbNpcModels.draw(entity, partialTick, packedLight, poseStack, bufferSource)
        // EntityRenderer.render's whole body is leash forwarding + shouldShowName/renderNameTag, so skipping it (as a
        // pure GLB renderer naturally would) silently drops the name tag. Call it after the model so the name tag
        // composites on top; the poseStack is already positioned at the entity by EntityRenderDispatcher.
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
    }

    /**
     * EntityRenderer leaves [getShadowRadius] at 0, and a GLB NPC has no vanilla model constructor to set it, so it
     * would cast no blob shadow at all. Vanilla draws that shadow from this radius; scale it with the NPC's visible
     * size so an enlarged model gets a proportionally larger shadow.
     */
    override fun getShadowRadius(entity: GlbNpc): Float {
        val modelId = entity.modelId.ifEmpty { GlbModels.defaultId } ?: return DEFAULT_SHADOW_RADIUS
        val descriptor = GlbModels.descriptor(modelId) ?: return DEFAULT_SHADOW_RADIUS
        return DEFAULT_SHADOW_RADIUS * entity.config.effectiveScale(descriptor)
    }

    override fun getTextureLocation(entity: GlbNpc): ResourceLocation = TEXTURE

    companion object {
        /** Vanilla's usual entity shadow radius (LivingEntityRenderer's default). */
        private const val DEFAULT_SHADOW_RADIUS = 0.5f
        private val TEXTURE = ResourceLocation.fromNamespaceAndPath("glbrender", "npc")
    }
}
