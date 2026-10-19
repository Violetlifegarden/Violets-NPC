package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.client.npc.anim.GlbNpcAnimator
import com.huashanlunjian.glbrender.client.npc.anim.NpcAnimRuntime
import com.huashanlunjian.glbrender.npc.GlbModels
import com.huashanlunjian.glbrender.npc.GlbNpc
import com.huashanlunjian.glbrender.npc.GlbNpcConfig
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import top.fifthlight.blazerod.gl.Gl
import top.fifthlight.blazerod.gl.GpuTexture
import top.fifthlight.blazerod.gl.TextureFormat
import top.fifthlight.blazerod.model.HumanoidTag
import top.fifthlight.blazerod.model.rgbaFromArgb
import top.fifthlight.blazerod.render.api.render.GlbRenderTarget
import top.fifthlight.blazerod.render.api.render.ScheduledRenderer
import top.fifthlight.blazerod.render.api.render.drawScene
import top.fifthlight.blazerod.render.api.resource.MaterialMode
import top.fifthlight.blazerod.render.api.resource.ModelInstance
import top.fifthlight.blazerod.render.api.resource.RenderScene
import java.util.HashMap
import java.util.UUID

/** LightTexture.FULL_BRIGHT, redefined so we don't pull the client class in for the glow light arg. */
private const val FULL_BRIGHT = 15728880

/** Held-item placement, matching ArmorStand's defaults (and the player shim's): face the hand down, turn the item
 *  around, and shrink it slightly so a vanilla item reads at GLB-hand proportions. */
private const val HELD_ITEM_SCALE = 0.9f
private const val HELD_ITEM_ROT_X = -90f
private const val HELD_ITEM_ROT_Y = 180f
private const val HELD_ITEM_ROT_Z = 0f

/**
 * Renderer-side glue for the NPC draw: it owns the per-entity [ModelInstance] and the camera-relative body matrix,
 * and pulls the model (descriptor from the common [GlbModels], loaded scene + shared renderer from
 * [GlbModelRegistry]). Draw itself goes through the glb-core public [drawScene] API, exactly like the demoted player
 * shim — no NPC-specific render path.
 *
 * The instance is rebuilt when an entity's modelId changes (right-click cycle), and released once the entity leaves
 * the client level via [pruneRemoved], so the per-entity map does not leak.
 */
object GlbNpcModels {
    private val LOGGER = LogUtils.getLogger()

    /** Holds the entity alongside its instance so [pruneRemoved] can test [net.minecraft.world.entity.Entity.isRemoved]
     *  without a client-side UUID->entity lookup (ClientLevel only indexes by network id). */
    private class Entry(val entity: GlbNpc, var instance: ModelInstance) {
        val anim = NpcAnimRuntime()
    }

    private val instances = HashMap<UUID, Entry>()

    /** Whether [draw] queued at least one task this frame, so [executeScheduled] can skip the empty case. */
    private var scheduledAny = false

    /**
     * Flushes the tasks queued by [draw] in one instanced pass per primitive and scene. Runs at
     * `RenderLevelStageEvent.Stage.AFTER_ENTITIES`, where the world projection/modelview are still the ones the
     * entity pass used (the same matrices the immediate path would have drawn with). Same-scene NPCs therefore
     * share a pipeline bind and an instanced draw instead of one full draw per NPC.
     */
    fun executeScheduled() {
        if (!scheduledAny) return
        scheduledAny = false
        val scheduled = GlbModelRegistry.renderer() as? ScheduledRenderer<*, *> ?: return
        val target = Minecraft.getInstance().mainRenderTarget
        val device = Gl.device
        val color = GpuTexture.adopt(target.colorTextureId, TextureFormat.RGBA8)
        val depth = GpuTexture.adopt(target.depthTextureId, TextureFormat.DEPTH24)
        scheduled.executeTasks(device.createTextureView(color), device.createTextureView(depth))
    }

    fun draw(
        entity: GlbNpc,
        partialTick: Float,
        packedLight: Int,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
    ) {
        val modelId = entity.modelId.ifEmpty { GlbModels.defaultId } ?: return
        val descriptor = GlbModels.descriptor(modelId) ?: return
        val scene = GlbModelRegistry.sceneFor(modelId) ?: return
        val renderer = GlbModelRegistry.renderer() ?: return

        // Reuse the cached instance only while it still belongs to the current scene; a modelId switch hands us a
        // different scene, so drop the old reference (its refcount) and build a fresh instance for the new one.
        val cached = instances[entity.uuid]
        val entry = if (cached != null && cached.instance.scene === scene) {
            cached
        } else {
            cached?.instance?.decreaseReferenceCount()
            Entry(entity, ModelInstance.Factory.of(scene).also { it.increaseReferenceCount() })
                .also { instances[entity.uuid] = it }
        }
        val instance = entry.instance

        try {
            // Drive this frame's clip (priority table over the model's glTF clips) into the instance's bones BEFORE
            // updateRenderData recomputes world transforms from them — same ordering as the player clip path.
            val clips = GlbModelRegistry.clipsFor(modelId)
            if (!clips.isNullOrEmpty()) {
                val gameTick = Minecraft.getInstance().level?.gameTime ?: entity.tickCount.toLong()
                GlbNpcAnimator.animate(
                    entry.anim, entity, instance, clips,
                    GlbModelRegistry.animationTable, gameTick, partialTick, entity.config.clip,
                )
            }
            instance.updateRenderData(partialTick)

            // RenderSystem's modelview during entity rendering is the camera rotation only (vanilla folds the
            // -camera translation into entity geometry), so place the model camera-relative like the player shim.
            val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
            val pos = entity.getPosition(partialTick)
            val scale = entity.config.effectiveScale(descriptor)
            val groundOffsetY = entity.config.effectiveGroundOffsetY(descriptor)
            // The renderer's entityYaw argument is always 0 (vanilla LevelRenderer hands rotation to the renderer, not
            // the caller), so read facing off the entity. With a head bone the body follows yBodyRot and the animator
            // turns the head toward the look direction (ArmorStand's LiveSwitched split); rigs without one keep the
            // whole model following yHeadRot so look-around still turns them.
            val bodyYawDegrees = if (GlbNpcAnimator.hasHeadBone(entry.anim, scene)) {
                Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot)
            } else {
                Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot)
            }
            val yawRadians =
                Math.toRadians((180.0 - bodyYawDegrees + entity.config.effectiveYawOffset(descriptor)).toDouble()).toFloat()
            val bodyMatrix = Matrix4f()
                .translation(
                    (pos.x - camera.x).toFloat(),
                    (pos.y - camera.y).toFloat(),
                    (pos.z - camera.z).toFloat(),
                )
                .rotateY(yawRadians)
                .scale(scale)
                .translate(0f, groundOffsetY, 0f)
            // Glow = render at max light instead of the world's packed light (the TLM full-bright analogue; our
            // custom drawScene path bypasses vanilla's outline, so overriding the light arg is how glow is expressed).
            val light = if (entity.config.glowing) FULL_BRIGHT else packedLight
            // Overlay atlas coordinates, mirroring vanilla LivingEntityRenderer.getOverlayCoords: the red damage row
            // while hurtTime/deathTime is running, otherwise the neutral white texel. Our drawScene path binds the
            // real game overlay atlas (MinecraftSamplers) and the shaders mix this texel over the base color, so hurt
            // now flashes red exactly like a vanilla mob. (Fire and in-wall shading are separate vanilla layers /
            // lightmap effects, not overlay rows.)
            val overlay = OverlayTexture.pack(0.0f, entity.hurtTime > 0 || entity.deathTime > 0)
            val tint = rgbaFromArgb(entity.config.tint)
            // The shading model is a per-task choice: each primitive was built with all three materials at load time,
            // and TaskMap keys batches by this mode so an instanced pass never mixes them.
            val materialMode = when (entity.config.materialMode) {
                GlbNpcConfig.MATERIAL_VANILLA -> MaterialMode.VANILLA
                GlbNpcConfig.MATERIAL_UNLIT -> MaterialMode.UNLIT
                else -> MaterialMode.DEFAULT
            }
            // T2 batching: the shared renderer supports scheduling, which groups same-scene tasks and draws each
            // primitive once with all instances (see executeScheduled). `renderInstanced` carries light/overlay/model
            // matrix PER INSTANCE but has no per-instance tint, so tinted NPCs stay on the immediate single-task path
            // rather than silently losing their tint.
            val scheduled = renderer as? ScheduledRenderer<*, *>
            if (scheduled != null && entity.config.tint == GlbNpcConfig.NO_TINT) {
                scheduled.schedule(instance.createRenderTask(bodyMatrix, light, overlay, tint, materialMode))
                scheduledAny = true
            } else {
                val minecraft = Minecraft.getInstance()
                val target = minecraft.mainRenderTarget
                val device = Gl.device
                val color = GpuTexture.adopt(target.colorTextureId, TextureFormat.RGBA8)
                val depth = GpuTexture.adopt(target.depthTextureId, TextureFormat.DEPTH24)
                val colorView = device.createTextureView(color)
                val depthView = device.createTextureView(depth)
                renderer.drawScene(
                    instance,
                    GlbRenderTarget(colorView, depthView),
                    bodyMatrix,
                    light,
                    overlay = overlay,
                    tint = tint,
                    materialMode = materialMode,
                )
            }

            // Held items go through vanilla's item renderer at the animated hand bones. They are drawn now (entity
            // pass, into the level's buffer source) rather than with the model task: the depth buffer already holds
            // them when the scheduled model draw runs at AFTER_ENTITIES, so occlusion is correct either way.
            renderHeldItems(entity, instance, scene, bodyMatrix, poseStack, bufferSource, light)
        } catch (error: RuntimeException) {
            LOGGER.error("glbrender: npc draw failed", error)
        }
    }

    // Scratch matrices for the held-item pass; reused every frame to avoid per-draw allocation.
    private val handWorldMatrix = Matrix4f()
    private val handWorldPos = Vector3f()
    private val handWorldRot = Quaternionf()
    private val itemLocalMatrix = Matrix4f()
    private val itemNormalMatrix = Matrix3f()

    /**
     * Draws the NPC's main- and off-hand items, the same composition the player shim uses (ported from ArmorStand's
     * `PlayerRenderer.renderHeldItem`): body placement (already camera-relative) -> scene render transform -> hand
     * bone rotation/translation -> item rotation -> uniform item scale. Only the bone's rotation and translation are
     * taken, so a bone's baked scale does not stretch the item.
     */
    private fun renderHeldItems(
        entity: GlbNpc,
        instance: ModelInstance,
        scene: RenderScene,
        bodyMatrix: Matrix4f,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        light: Int,
    ) {
        val mainIsRight = entity.mainArm == HumanoidArm.RIGHT
        val mainHand = entity.getItemInHand(InteractionHand.MAIN_HAND)
        val offHand = entity.getItemInHand(InteractionHand.OFF_HAND)
        renderHeldItem(
            entity, instance, scene,
            if (mainIsRight) mainHand else offHand,
            ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, HumanoidTag.RIGHT_HAND,
            bodyMatrix, poseStack, bufferSource, light, false,
        )
        renderHeldItem(
            entity, instance, scene,
            if (mainIsRight) offHand else mainHand,
            ItemDisplayContext.THIRD_PERSON_LEFT_HAND, HumanoidTag.LEFT_HAND,
            bodyMatrix, poseStack, bufferSource, light, true,
        )
    }

    private fun renderHeldItem(
        entity: GlbNpc,
        instance: ModelInstance,
        scene: RenderScene,
        itemStack: ItemStack,
        displayContext: ItemDisplayContext,
        tag: HumanoidTag,
        bodyMatrix: Matrix4f,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        light: Int,
        leftHanded: Boolean,
    ) {
        if (itemStack.isEmpty) return
        // Rigs without a resolvable hand bone (no humanoid tag, VRM/PMX name, or Mixamo name) cannot hold items.
        val nodeIndex = GlbNpcAnimator.resolveBone(scene, tag) ?: return
        instance.copyNodeWorldTransform(nodeIndex, handWorldMatrix)
        handWorldMatrix.getTranslation(handWorldPos)
        handWorldMatrix.getUnnormalizedRotation(handWorldRot)
        handWorldRot.normalize()

        itemLocalMatrix.set(bodyMatrix)
        scene.renderTransform?.applyOnMatrix(itemLocalMatrix)
        itemLocalMatrix.translationRotate(
            handWorldPos.x, handWorldPos.y, handWorldPos.z,
            handWorldRot.x, handWorldRot.y, handWorldRot.z, handWorldRot.w,
        )
        itemLocalMatrix.rotateX(Math.toRadians(HELD_ITEM_ROT_X.toDouble()).toFloat())
        itemLocalMatrix.rotateY(Math.toRadians(HELD_ITEM_ROT_Y.toDouble()).toFloat())
        itemLocalMatrix.rotateZ(Math.toRadians(HELD_ITEM_ROT_Z.toDouble()).toFloat())
        itemLocalMatrix.scale(HELD_ITEM_SCALE)

        poseStack.pushPose()
        val pose = poseStack.last().pose()
        pose.set(itemLocalMatrix)
        poseStack.last().normal().set(itemNormalMatrix.set(pose).invert().transpose())
        Minecraft.getInstance().itemRenderer.renderStatic(
            entity, itemStack, displayContext, leftHanded,
            poseStack, bufferSource, entity.level(), light, OverlayTexture.NO_OVERLAY, entity.id,
        )
        poseStack.popPose()
    }

    /**
     * Releases the [ModelInstance] of every NPC that has been removed (despawned, unloaded, or the world closed).
     * Called once per frame from the AFTER_SKY hook; without it the per-entity map grows for the lifetime of the
     * client and leaks a refcounted instance per removed entity.
     */
    fun pruneRemoved() {
        if (instances.isEmpty()) return
        if (Minecraft.getInstance().level == null) {
            instances.values.forEach { it.instance.decreaseReferenceCount() }
            instances.clear()
            return
        }
        val iterator = instances.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.entity.isRemoved) {
                entry.instance.decreaseReferenceCount()
                iterator.remove()
            }
        }
    }
}
