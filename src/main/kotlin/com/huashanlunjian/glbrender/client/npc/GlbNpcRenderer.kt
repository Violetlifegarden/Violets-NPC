package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.npc.GlbModelDescriptor
import com.huashanlunjian.glbrender.npc.GlbModels
import com.huashanlunjian.glbrender.npc.GlbNpc
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.AABB

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
        // Vanilla hides the body of an invisible living entity (LivingEntityRenderer.isBodyVisible); without this the
        // GLB keeps rendering through invisibility effects.
        if (!entity.isInvisible) {
            GlbNpcModels.draw(entity, partialTick, packedLight, poseStack, bufferSource)
        }
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
    override fun getShadowRadius(entity: GlbNpc): Float =
        DEFAULT_SHADOW_RADIUS * (descriptor(entity)?.let { entity.config.effectiveScale(it) } ?: 1f)

    /**
     * Vanilla places the name tag from the entity's bounding box (EntityAttachment.NAME_TAG), which knows nothing
     * about the GLB's render scale, so a scaled model's tag ends up inside its chest. Lift it by the extra height the
     * model gained; the pose stack is already translated to the entity, and the offset is along world Y.
     */
    override fun renderNameTag(
        entity: GlbNpc,
        displayName: Component,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        partialTick: Float,
    ) {
        val descriptor = descriptor(entity)
        val extraHeight = if (descriptor == null) {
            0f
        } else {
            val scale = entity.config.effectiveScale(descriptor)
            val modelTop = scale * (entity.config.effectiveGroundOffsetY(descriptor) + entity.bbHeight)
            modelTop - entity.bbHeight
        }
        if (extraHeight == 0f) {
            super.renderNameTag(entity, displayName, poseStack, bufferSource, packedLight, partialTick)
            return
        }
        poseStack.pushPose()
        poseStack.translate(0.0, extraHeight.toDouble(), 0.0)
        super.renderNameTag(entity, displayName, poseStack, bufferSource, packedLight, partialTick)
        poseStack.popPose()
    }

    /**
     * The base implementation frustum-tests the entity's vanilla bounding box, but the GLB is drawn scaled about its
     * feet (plus the per-model ground offset), so an enlarged model gets culled while still on screen. Test a box
     * grown the same way, keeping the base method's distance check and `noCulling` escape hatch.
     */
    override fun shouldRender(entity: GlbNpc, frustum: Frustum, camX: Double, camY: Double, camZ: Double): Boolean {
        if (!entity.shouldRender(camX, camY, camZ)) return false
        if (entity.noCulling) return true
        val descriptor = descriptor(entity) ?: return frustum.isVisible(entity.getBoundingBoxForCulling().inflate(0.5))
        val scale = entity.config.effectiveScale(descriptor)
        val halfWidth = entity.bbWidth * 0.5 * scale
        val bottom = entity.y + scale * entity.config.effectiveGroundOffsetY(descriptor)
        val aabb = AABB(
            entity.x - halfWidth, bottom, entity.z - halfWidth,
            entity.x + halfWidth, bottom + entity.bbHeight * scale, entity.z + halfWidth,
        )
        return frustum.isVisible(aabb.inflate(0.5))
    }

    override fun getTextureLocation(entity: GlbNpc): ResourceLocation = TEXTURE

    private fun descriptor(entity: GlbNpc): GlbModelDescriptor? {
        val modelId = entity.modelId.ifEmpty { GlbModels.defaultId } ?: return null
        return GlbModels.descriptor(modelId)
    }

    companion object {
        /** Vanilla's usual entity shadow radius (LivingEntityRenderer's default). */
        private const val DEFAULT_SHADOW_RADIUS = 0.5f
        private val TEXTURE = ResourceLocation.fromNamespaceAndPath("glbrender", "npc")
    }
}
