package com.huashanlunjian.glbrender.client

import com.mojang.logging.LogUtils
import net.minecraft.world.entity.player.Player
import org.joml.Quaternionf
import org.joml.Vector3f
import top.fifthlight.blazerod.model.HumanoidTag
import top.fifthlight.blazerod.model.TransformId
import top.fifthlight.blazerod.render.api.resource.ModelInstance
import top.fifthlight.blazerod.render.api.resource.RenderNode
import top.fifthlight.blazerod.render.api.resource.RenderScene
import kotlin.math.PI
import kotlin.math.sin

/**
 * Drives GLB humanoid bones from vanilla Player animation state.
 *
 * Only walk, sprint and crouch are implemented. The rotations replicate the same sine/cosine
 * patterns HumanoidModel uses for limb swing, body bob and head tracking, but write them into
 * TransformId.RELATIVE_ANIMATION so they layer on top of the model's bind pose.
 */
object VanillaPlayerPoseDriver {
    private val LOGGER = LogUtils.getLogger()

    private data class BoneBinding(
        val tag: HumanoidTag,
        var nodeIndex: Int = -1,
    )

    private val bindings = listOf(
        BoneBinding(HumanoidTag.HIPS),
        BoneBinding(HumanoidTag.SPINE),
        BoneBinding(HumanoidTag.CHEST),
        BoneBinding(HumanoidTag.NECK),
        BoneBinding(HumanoidTag.HEAD),
        BoneBinding(HumanoidTag.LEFT_UPPER_LEG),
        BoneBinding(HumanoidTag.RIGHT_UPPER_LEG),
        BoneBinding(HumanoidTag.LEFT_LOWER_LEG),
        BoneBinding(HumanoidTag.RIGHT_LOWER_LEG),
        BoneBinding(HumanoidTag.LEFT_UPPER_ARM),
        BoneBinding(HumanoidTag.RIGHT_UPPER_ARM),
        BoneBinding(HumanoidTag.LEFT_LOWER_ARM),
        BoneBinding(HumanoidTag.RIGHT_LOWER_ARM),
        BoneBinding(HumanoidTag.LEFT_HAND),
        BoneBinding(HumanoidTag.RIGHT_HAND),
    )

    fun resolve(scene: RenderScene) {
        for (binding in bindings) {
            val node = scene.humanoidTagMap[binding.tag]
                ?: scene.nodeNameMap[binding.tag.vrmName]
                ?: scene.nodeNameMap[binding.tag.pmxEnglish]
                ?: mixamoNode(scene, binding.tag)
                ?: suffixNode(scene, binding.tag)
            binding.nodeIndex = node?.nodeIndex ?: -1
        }
        val bound = bindings.filter { it.nodeIndex >= 0 }
        val missing = bindings.filter { it.nodeIndex < 0 }.map { it.tag.name }
        LOGGER.info(
            "glbrender: pose driver bound {}/{} bones; unbound: {}",
            bound.size, bindings.size, missing.joinToString(","),
        )
    }

    /** Mixamo rigs name bones "mixamorig:LeftUpLeg" and friends, with no VRM humanoid extension. */
    private fun mixamoNode(scene: RenderScene, tag: HumanoidTag): RenderNode? {
        val suffix = MIXAMO_NAMES[tag] ?: return null
        return scene.nodeNameMap.entries.firstOrNull { (name, _) ->
            name.substringAfterLast(':').equals(suffix, ignoreCase = true)
        }?.value
    }

    /**
     * Fallback for rigs whose bone names are unrecoverable - deepseek-chan.glb ships node names that
     * are already U+FFFD-corrupted inside the GLB (export-time, not a loader bug), so the only stable
     * handle left is the "_<nodeIndex>" ASCII suffix each name still ends with. The table below maps
     * humanoid tags to those suffixes, read off the joint dump for this specific model.
     */
    private fun suffixNode(scene: RenderScene, tag: HumanoidTag): RenderNode? {
        val suffix = DEEPSEEK_SUFFIX[tag] ?: return null
        return scene.nodeNameMap.entries.firstOrNull { (name, _) -> name.endsWith(suffix) }?.value
    }

    fun apply(instance: ModelInstance, player: Player, partialTick: Float) {
        val walkPos = player.walkAnimation.position(partialTick)
        val speed = player.walkAnimation.speed(partialTick)
        // MC's HumanoidModel uses position * 0.6662 as the phase multiplier
        val phase = walkPos * 0.6662f
        val isSneaking = player.isCrouching
        val isSprinting = player.isSprinting

        // Sprint increases swing amplitude; speed controls how wide the limbs swing
        val swingAmp = speed * if (isSprinting) 1.4f else 1.0f

        // Crouch tilts the spine forward and lowers the head
        val crouchAngle = if (isSneaking) 0.5f else 0f

        // Legs alternate with sin/cos of the walk phase
        setRotation(instance, HumanoidTag.LEFT_UPPER_LEG, axisX(sin(phase) * swingAmp))
        setRotation(instance, HumanoidTag.RIGHT_UPPER_LEG, axisX(-sin(phase) * swingAmp))
        setRotation(instance, HumanoidTag.LEFT_LOWER_LEG, axisX(sin(phase + 0.3f) * swingAmp * 0.5f + 0.1f))
        setRotation(instance, HumanoidTag.RIGHT_LOWER_LEG, axisX(-sin(phase + 0.3f) * swingAmp * 0.5f + 0.1f))

        // Arms swing opposite to legs
        setRotation(instance, HumanoidTag.LEFT_UPPER_ARM, axisX(-sin(phase) * swingAmp))
        setRotation(instance, HumanoidTag.RIGHT_UPPER_ARM, axisX(sin(phase) * swingAmp))
        setRotation(instance, HumanoidTag.LEFT_LOWER_ARM, axisX(-sin(phase) * swingAmp * 0.3f - 0.1f))
        setRotation(instance, HumanoidTag.RIGHT_LOWER_ARM, axisX(sin(phase) * swingAmp * 0.3f - 0.1f))

        // Body follows legs with reduced amplitude + crouch tilt
        val bodyTilt = sin(phase) * 0.06f * speed + crouchAngle
        setRotation(instance, HumanoidTag.SPINE, axisX(bodyTilt))
        setRotation(instance, HumanoidTag.CHEST, axisX(crouchAngle * 0.5f))

        // Head pitch from player look + crouch offset
        val headPitch = player.xRot * (PI.toFloat() / 180f) + crouchAngle * 0.3f
        val headYaw = (player.yHeadRot - player.yBodyRot) * (PI.toFloat() / 180f)
        setRotation(instance, HumanoidTag.HEAD, Quaternionf().rotateYXZ(headYaw, headPitch, 0f))
        setRotation(instance, HumanoidTag.NECK, axisX(crouchAngle * 0.2f))

        // Hips bob vertically during walking
        if (speed > 0.01f) {
            val hipBob = sin(phase * 2f) * 0.02f * speed
            setTranslation(instance, HumanoidTag.HIPS, Vector3f(0f, hipBob, 0f))
        }
    }

    private fun setRotation(instance: ModelInstance, tag: HumanoidTag, rotation: Quaternionf) {
        val idx = findNode(tag) ?: return
        instance.setTransformDecomposed(idx, TransformId.RELATIVE_ANIMATION) {
            this.rotation.set(rotation)
        }
    }

    private fun setTranslation(instance: ModelInstance, tag: HumanoidTag, translation: Vector3f) {
        val idx = findNode(tag) ?: return
        instance.setTransformDecomposed(idx, TransformId.RELATIVE_ANIMATION) {
            this.translation.set(translation)
        }
    }

    private fun findNode(tag: HumanoidTag): Int? {
        return bindings.firstOrNull { it.tag == tag }?.nodeIndex?.takeIf { it >= 0 }
    }

    /** Resolved node index for a humanoid tag, or null if this rig has no such bone. Used by the held-item pass. */
    fun nodeIndex(tag: HumanoidTag): Int? = findNode(tag)

    private fun axisX(angleRad: Float): Quaternionf =
        Quaternionf().rotateX(angleRad)

    /** deepseek-chan.glb bone indices, read off the joint dump; matched via each name's "_<index>" tail. */
    private val DEEPSEEK_SUFFIX = mapOf(
        HumanoidTag.HIPS to "_264",
        HumanoidTag.SPINE to "_162",
        HumanoidTag.CHEST to "_161",
        HumanoidTag.NECK to "_56",
        HumanoidTag.HEAD to "_55",
        HumanoidTag.LEFT_UPPER_LEG to "_177",
        HumanoidTag.LEFT_LOWER_LEG to "_171",
        HumanoidTag.RIGHT_UPPER_LEG to "_192",
        HumanoidTag.RIGHT_LOWER_LEG to "_186",
        HumanoidTag.LEFT_UPPER_ARM to "_103",
        HumanoidTag.LEFT_LOWER_ARM to "_102",
        HumanoidTag.RIGHT_UPPER_ARM to "_152",
        HumanoidTag.RIGHT_LOWER_ARM to "_151",
        HumanoidTag.LEFT_HAND to "_101",
        HumanoidTag.RIGHT_HAND to "_150",
    )

    private val MIXAMO_NAMES = mapOf(
        HumanoidTag.HIPS to "Hips",
        HumanoidTag.SPINE to "Spine",
        HumanoidTag.CHEST to "Spine1",
        HumanoidTag.UPPER_CHEST to "Spine2",
        HumanoidTag.NECK to "Neck",
        HumanoidTag.HEAD to "Head",
        HumanoidTag.LEFT_SHOULDER to "LeftShoulder",
        HumanoidTag.LEFT_UPPER_ARM to "LeftArm",
        HumanoidTag.LEFT_LOWER_ARM to "LeftForeArm",
        HumanoidTag.LEFT_HAND to "LeftHand",
        HumanoidTag.RIGHT_SHOULDER to "RightShoulder",
        HumanoidTag.RIGHT_UPPER_ARM to "RightArm",
        HumanoidTag.RIGHT_LOWER_ARM to "RightForeArm",
        HumanoidTag.RIGHT_HAND to "RightHand",
        HumanoidTag.LEFT_UPPER_LEG to "LeftUpLeg",
        HumanoidTag.LEFT_LOWER_LEG to "LeftLeg",
        HumanoidTag.LEFT_FOOT to "LeftFoot",
        HumanoidTag.LEFT_TOES to "LeftToeBase",
        HumanoidTag.RIGHT_UPPER_LEG to "RightUpLeg",
        HumanoidTag.RIGHT_LOWER_LEG to "RightLeg",
        HumanoidTag.RIGHT_FOOT to "RightFoot",
        HumanoidTag.RIGHT_TOES to "RightToeBase",
    )
}
