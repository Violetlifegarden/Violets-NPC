package com.huashanlunjian.glbrender.client.npc.anim

import net.minecraft.util.Mth
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.vehicle.Boat
import top.fifthlight.blazerod.model.HumanoidTag
import top.fifthlight.blazerod.model.TransformId
import top.fifthlight.blazerod.model.animation.AnimationState
import top.fifthlight.blazerod.model.animation.SimpleAnimationState
import top.fifthlight.blazerod.render.api.animation.AnimationItemInstance
import top.fifthlight.blazerod.render.api.animation.MaskableAnimationItemInstance
import top.fifthlight.blazerod.render.api.resource.ModelInstance
import top.fifthlight.blazerod.render.api.resource.RenderScene
import top.fifthlight.blazerod.render.runtime.animation.AnimationItemImpl
import top.fifthlight.blazerod.render.runtime.animation.AnimationItemInstanceImpl
import top.fifthlight.blazerod.render.runtime.animation.CrossfadeAnimationItemInstance
import top.fifthlight.blazerod.render.runtime.animation.CrossfadeAnimationState
import kotlin.math.PI

/**
 * Per-entity animation runtime, the NPC counterpart of ArmorStand's `ModelController.LiveSwitched` state.
 *
 * It holds the two layers the controller evaluates each frame: the base life/locomotion clip and the optional
 * upper-body action clip layered over it (swing). Both are cached so re-selecting the same row advances the clip
 * instead of re-seeking it. The bone/mask caches are per-scene and rebuilt when the model changes.
 *
 * Lifetime is tied to the NPC's [ModelInstance] cache in `GlbNpcModels`, which drops both when the entity is
 * removed.
 */
class NpcAnimRuntime {
    internal var baseClip: AnimationItemImpl? = null
    internal var baseLoop: GlbLoopMode? = null
    internal var baseItem: AnimationItemInstance? = null
    internal var baseState: AnimationState? = null

    /** Id of the table row the base clip came from; preview playback has none. Drives one-shot completion. */
    internal var baseStateId: String? = null

    /** In-flight transition from the previous base state; both are null once the blend has finished. */
    internal var fadeItem: CrossfadeAnimationItemInstance? = null
    internal var fadeState: CrossfadeAnimationState? = null

    /** A PLAY_ONCE row that ran to its end, skipped by selection until its predicate stops matching. */
    internal var suppressedOneShot: String? = null

    internal var actionClip: AnimationItemImpl? = null
    internal var actionItem: AnimationItemInstance? = null
    internal var actionState: AnimationState? = null
    internal var actionArm: HumanoidArm? = null

    internal var boneScene: RenderScene? = null
    internal var boneNodeCount: Int = -1
    internal var headNode: Int = -1
    internal var leftUpperBodyMask: BooleanArray = BooleanArray(0)
    internal var rightUpperBodyMask: BooleanArray = BooleanArray(0)

    fun reset() {
        baseClip = null
        baseLoop = null
        baseItem = null
        baseState = null
        baseStateId = null
        fadeItem = null
        fadeState = null
        suppressedOneShot = null
        actionClip = null
        actionItem = null
        actionState = null
        actionArm = null
        boneScene = null
        boneNodeCount = -1
        headNode = -1
        leftUpperBodyMask = BooleanArray(0)
        rightUpperBodyMask = BooleanArray(0)
    }
}

/**
 * Drives NPC bone animation by evaluating a [GlbAnimationTable] each frame and playing the winning clip through the
 * ported glb-core [AnimationItemInstance] layer — the same layer the player's `ClipStateMachine` uses, but keyed
 * per-entity so several NPCs on screen can each be at a different point in (or a different) clip.
 *
 * The controller follows ArmorStand's `LiveSwitched`: a base state machine (death > vehicle > sleep > swim/crawl >
 * climb > crouch > sprint > walk > idle), a transform reset on state switch so untouched bones return to bind pose,
 * a masked upper-body overlay for swing actions, and procedural head look on top of the clip.
 *
 * This object is stateless; all per-entity state lives in the caller-owned [NpcAnimRuntime]. Clip selection is
 * first-match-wins by priority (the table's job); clip *resolution* maps the selected row's name fragments onto the
 * model's actual glTF clips with a first-contains match, falling back to the model's first clip so a single-clip rig
 * (CesiumMan) still animates every state instead of going static.
 */
object GlbNpcAnimator {

    /** Swing overlay candidates, matched strictly (no first-clip fallback: a rig without a swing clip gets no overlay). */
    private val SWING_CLIP_KEYS = listOf("swing", "punch", "attack", "hit")

    private val TORSO_TAGS = arrayOf(
        HumanoidTag.SPINE,
        HumanoidTag.CHEST,
        HumanoidTag.UPPER_CHEST,
        HumanoidTag.NECK,
        HumanoidTag.HEAD,
    )

    /** Prefix every Mixamo joint carries, in either separator form. */
    private const val MIXAMO_PREFIX = "mixamorig_"

    /**
     * Seconds a base-state switch blends over. TLM/GeckoLib's stock transition is 0.2-0.3s; a quarter second reads
     * smooth at 20 TPS without delaying reactions noticeably.
     */
    private const val CROSSFADE_SECONDS = 0.25f

    /** Mixamo bone names for the tags the head/upper-body layers need. Mirrors the player pose driver's resolver. */
    private val MIXAMO_NAMES = mapOf(
        HumanoidTag.HEAD to "Head",
        HumanoidTag.NECK to "Neck",
        HumanoidTag.SPINE to "Spine",
        HumanoidTag.CHEST to "Spine1",
        HumanoidTag.UPPER_CHEST to "Spine2",
        HumanoidTag.LEFT_SHOULDER to "LeftShoulder",
        HumanoidTag.LEFT_UPPER_ARM to "LeftArm",
        HumanoidTag.LEFT_LOWER_ARM to "LeftForeArm",
        HumanoidTag.LEFT_HAND to "LeftHand",
        HumanoidTag.RIGHT_SHOULDER to "RightShoulder",
        HumanoidTag.RIGHT_UPPER_ARM to "RightArm",
        HumanoidTag.RIGHT_LOWER_ARM to "RightForeArm",
        HumanoidTag.RIGHT_HAND to "RightHand",
    )

    /**
     * Whether this scene has a resolvable head bone. The caller uses it to split yaw: with a head bone the body
     * follows `yBodyRot` and the head tracks the look direction; without one the whole model keeps following
     * `yHeadRot` (the pre-T3 behaviour), because there is nothing to turn independently.
     */
    fun hasHeadBone(runtime: NpcAnimRuntime, scene: RenderScene): Boolean {
        ensureBones(runtime, scene)
        return runtime.headNode >= 0
    }

    /**
     * Applies this frame's clip to [instance] for [entity]. Returns true when a clip wrote bone transforms (the
     * caller must then let [ModelInstance.updateRenderData] pick them up before drawing), false when the model has
     * no clips at all and the pose is left as-is.
     */
    fun animate(
        runtime: NpcAnimRuntime,
        entity: LivingEntity,
        instance: ModelInstance,
        clips: List<AnimationItemImpl>,
        table: GlbAnimationTable,
        gameTick: Long,
        partialTick: Float,
        forcedClip: String = "",
    ): Boolean {
        if (clips.isEmpty()) return false

        val ctx = frameCtx(entity, gameTick, partialTick)
        val previewClip = resolvePreviewClip(clips, forcedClip)
        latchFinishedOneShot(runtime)
        val auto = if (previewClip == null) selectState(runtime, table, ctx, clips) else null
        val clip: AnimationItemImpl
        val loop: GlbLoopMode
        if (previewClip != null) {
            // Editor preview: play the picked clip on a plain loop and nothing else (no swing overlay, no head look),
            // so what the user sees is the authored clip rather than the clip plus gameplay layers.
            clip = previewClip
            loop = GlbLoopMode.LOOP
        } else {
            auto ?: return false
            // A single-clip rig (e.g. CesiumMan, which only ships a walk cycle) resolves every state to that clip in
            // `selectState`; a multi-clip rig never falls back to its first clip, so a missing "idle" does not turn
            // into walking in place.
            clip = auto.second
            loop = auto.first.loop
        }

        // Restart only when the resolved clip or its loop mode actually changes; re-selecting the same walking clip
        // frame after frame must keep advancing, not re-seek to zero (name+loop dedup, mirroring TLM's setAnimation).
        if (clip !== runtime.baseClip || loop != runtime.baseLoop || runtime.baseState == null) {
            val previousItem = runtime.baseItem
            val previousState = runtime.baseState
            // Clear every animated transform on a state switch so bones that neither clip touches fall back to the
            // bind pose instead of keeping an older clip's last pose. Both endpoints rewrite their own bones from
            // the cleared state on this same frame, so starting the fade does not pop.
            instance.clearTransform()
            val context = NpcAnimationContext(entity, gameTick, partialTick)
            val itemInstance = AnimationItemInstance.Factory.of(clip)
            val animState = itemInstance.createState(context)
            (animState as? SimpleAnimationState)?.let { applyLoopMode(it, loop) }
            runtime.baseClip = clip
            runtime.baseLoop = loop
            runtime.baseItem = itemInstance
            runtime.baseState = animState
            runtime.fadeItem = null
            runtime.fadeState = null
            // Blend from the state that was on screen. Starting from the already-running source state (instead of
            // creating a fresh one) keeps its phase, so walk -> sprint does not restart the walk at frame zero.
            // Mid-fade, `previousState` is the interrupted fade's target, i.e. the pose it was heading to.
            if (previousItem is AnimationItemInstanceImpl && itemInstance is AnimationItemInstanceImpl &&
                previousState != null
            ) {
                runtime.fadeItem = CrossfadeAnimationItemInstance(previousItem, itemInstance, CROSSFADE_SECONDS)
                runtime.fadeState = CrossfadeAnimationState(previousState, animState, context, CROSSFADE_SECONDS)
            }
            runtime.actionClip = null
            runtime.actionItem = null
            runtime.actionState = null
            runtime.actionArm = null
        }
        runtime.baseStateId = if (previewClip == null) auto?.first?.id else null

        val itemInstance = runtime.baseItem ?: return false
        val animState = runtime.baseState ?: return false
        val playContext = NpcAnimationContext(entity, gameTick, partialTick)

        // Single-clip rigs (e.g. Cubism's one walk cycle): idle resolves to the same walk clip as walk, so the
        // restart block above never fires on a walk->idle transition and the clip would keep looping while
        // standing still. Freeze it on idle and resume otherwise, mirroring the player's ClipStateMachine.
        // Multi-clip rigs are unaffected — their idle row resolves to a real idle clip that applyLoopMode already
        // set to loop, so the walk->idle clip *name* change hits the restart block and re-seeks normally.
        if (previewClip == null && clips.size == 1) {
            (animState as? SimpleAnimationState)?.paused = auto?.first?.id == "idle"
        } else if (previewClip != null) {
            // A preview must always advance, including on a single-clip rig whose clip idle had frozen.
            (animState as? SimpleAnimationState)?.paused = false
        }

        val fadeItem = runtime.fadeItem
        val fadeState = runtime.fadeState
        if (fadeItem != null && fadeState != null) {
            // The fade state advances both endpoint clips, so the outgoing animation keeps moving while it fades.
            fadeState.updateTime(playContext)
            fadeItem.apply(instance, fadeItem.update(playContext, fadeState))
            if (fadeState.elapsedTime >= fadeState.duration) {
                runtime.fadeItem = null
                runtime.fadeState = null
            }
        } else {
            animState.updateTime(playContext)
            itemInstance.apply(instance, itemInstance.update(playContext, animState))
        }

        if (previewClip == null) {
            applyAction(runtime, entity, instance, clips, playContext)
            applyHeadTracking(runtime, entity, instance, partialTick)
        }
        return true
    }

    /**
     * Layers the swing overlay over the base clip, masked to the torso and the swinging arm (ArmorStand's
     * `applyMasked` path). Without a humanoid-tagged/mixamo arm the mask is empty and the overlay is applied in full,
     * which is the only sensible fallback for a rig that cannot isolate the upper body.
     */
    private fun applyAction(
        runtime: NpcAnimRuntime,
        entity: LivingEntity,
        instance: ModelInstance,
        clips: List<AnimationItemImpl>,
        context: NpcAnimationContext,
    ) {
        if (!entity.swinging) {
            runtime.actionClip = null
            runtime.actionItem = null
            runtime.actionState = null
            runtime.actionArm = null
            return
        }

        val clip = resolveClip(clips, SWING_CLIP_KEYS, fallbackToFirst = false) ?: return
        val arm = entity.mainArm
        if (clip !== runtime.actionClip || arm != runtime.actionArm || runtime.actionState == null) {
            val itemInstance = AnimationItemInstance.Factory.of(clip)
            val animState = itemInstance.createState(context)
            (animState as? SimpleAnimationState)?.let {
                it.loop = false
                it.paused = false
            }
            runtime.actionClip = clip
            runtime.actionItem = itemInstance
            runtime.actionState = animState
            runtime.actionArm = arm
        }

        val itemInstance = runtime.actionItem ?: return
        val animState = runtime.actionState ?: return
        animState.updateTime(context)
        val pendingValues = itemInstance.update(context, animState)

        val maskable = itemInstance as? MaskableAnimationItemInstance
        if (maskable == null) {
            itemInstance.apply(instance, pendingValues)
            return
        }
        ensureBones(runtime, instance.scene)
        val mask = if (runtime.actionArm == HumanoidArm.LEFT) {
            runtime.leftUpperBodyMask
        } else {
            runtime.rightUpperBodyMask
        }
        if (mask.any { it }) {
            maskable.applyMasked(instance, pendingValues, mask)
        } else {
            itemInstance.apply(instance, pendingValues)
        }
    }

    /**
     * Turns the head toward the look direction, on top of whatever the clip wrote, exactly like ArmorStand's
     * `LiveSwitched.apply`. Yaw is relative (head minus body) because the body matrix already carries `yBodyRot`.
     */
    private fun applyHeadTracking(
        runtime: NpcAnimRuntime,
        entity: LivingEntity,
        instance: ModelInstance,
        partialTick: Float,
    ) {
        ensureBones(runtime, instance.scene)
        val headIndex = runtime.headNode
        if (headIndex < 0) return

        val headYaw = Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot) -
            Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot)
        val headPitch = entity.getViewXRot(partialTick)
        val yawRadians = Mth.wrapDegrees(headYaw) * (PI.toFloat() / 180f)
        val pitchRadians = headPitch * (PI.toFloat() / 180f)
        instance.setTransformDecomposed(headIndex, TransformId.RELATIVE_ANIMATION) {
            rotation.rotationYXZ(yawRadians, pitchRadians, 0f)
        }
    }

    private fun frameCtx(entity: LivingEntity, gameTick: Long, partialTick: Float): GlbFrameCtx {
        val walk = entity.walkAnimation
        val climbing = entity.onClimbable()
        return GlbFrameCtx(
            gameTick = gameTick,
            partialTick = partialTick,
            limbSwing = walk.position(partialTick),
            limbSwingAmount = walk.speed(partialTick),
            onGround = entity.onGround(),
            isInWater = entity.isInWater,
            isSprinting = entity.isSprinting,
            isDeadOrDying = entity.isDeadOrDying,
            hurtTime = entity.hurtTime,
            isSleeping = entity.isSleeping,
            vehicle = vehicleKind(entity),
            isCrouching = entity.isCrouching,
            isSwimming = entity.isSwimming,
            // 1.21.1 has no separate crawling flag: swimming pose without the swimming flag is the crawl pose.
            isCrawling = entity.pose == Pose.SWIMMING && !entity.isSwimming,
            isClimbing = climbing,
            climbDirection = when {
                !climbing -> 0
                entity.deltaMovement.y > 0.1 -> 1
                entity.deltaMovement.y < -0.1 -> -1
                else -> 0
            },
        )
    }

    private fun vehicleKind(entity: LivingEntity): GlbVehicle = when (val vehicle = entity.vehicle) {
        null -> GlbVehicle.NONE
        is Boat -> GlbVehicle.BOAT
        else -> when (vehicle.type) {
            EntityType.HORSE,
            EntityType.DONKEY,
            EntityType.MULE,
            EntityType.LLAMA,
            EntityType.TRADER_LLAMA,
            EntityType.SKELETON_HORSE,
            EntityType.ZOMBIE_HORSE,
            EntityType.CAMEL,
            -> GlbVehicle.HORSE

            EntityType.PIG -> GlbVehicle.PIG
            else -> GlbVehicle.OTHER
        }
    }

    /**
     * Marks the current base row as exhausted once a PLAY_ONCE clip has reached its end. [selectState] then skips it
     * until its predicate stops matching, which turns "attack plays once" into "attack, then back to idle" instead
     * of "attack freezes on its last frame while hurtTime still counts down". A HOLD_ON_LAST_FRAME row (death) is a
     * terminal pose, not a state to leave, so it is never latched.
     */
    private fun latchFinishedOneShot(runtime: NpcAnimRuntime) {
        // Let an in-flight fade finish first, otherwise the follow-up state would cut it short.
        if (runtime.fadeState != null) return
        if (runtime.baseLoop != GlbLoopMode.PLAY_ONCE) return
        val stateId = runtime.baseStateId ?: return
        val state = runtime.baseState ?: return
        val duration = state.duration ?: return
        if (duration > 0f && state.getTime() >= duration) runtime.suppressedOneShot = stateId
    }

    /**
     * First row that both matches this frame and resolves to a clip the model actually ships. Two kinds of row are
     * skipped: a one-shot that already ran to its end (see [latchFinishedOneShot]), and any row whose clip this rig
     * lacks — falling through to a lower-priority state is better than a null selection, which would freeze the pose.
     */
    private fun selectState(
        runtime: NpcAnimRuntime,
        table: GlbAnimationTable,
        ctx: GlbFrameCtx,
        clips: List<AnimationItemImpl>,
    ): Pair<GlbAnimState, AnimationItemImpl>? {
        val raw = table.select(ctx)
        // The predicate stopped matching the suppressed row: that one-shot may be selected again later.
        if (runtime.suppressedOneShot != null && runtime.suppressedOneShot != raw?.id) {
            runtime.suppressedOneShot = null
        }
        val suppressed = runtime.suppressedOneShot
        for (state in table.states) {
            if (state.id == suppressed) continue
            if (!state.test(ctx)) continue
            // A single-clip rig must resolve every state to that clip — the documented graceful degradation.
            val clip = resolveClip(clips, state.clipKeys, fallbackToFirst = clips.size == 1) ?: continue
            return state to clip
        }
        return null
    }

    /**
     * Resolves the editor's forced clip override by exact (case-insensitive) name. Null means "no override, or the
     * name is not on this model" — both fall through to the normal priority table, so a stale value left behind by a
     * model switch degrades to automatic behavior instead of freezing the NPC.
     */
    private fun resolvePreviewClip(clips: List<AnimationItemImpl>, forcedName: String): AnimationItemImpl? {
        if (forcedName.isEmpty()) return null
        return clips.firstOrNull { (it.name ?: "").equals(forcedName, ignoreCase = true) }
    }

    /**
     * Resolves the first clip whose name contains one of [keys]. With [fallbackToFirst] a rig without any matching
     * clip still plays its first clip (graceful degradation for single-clip rigs); the swing overlay disables that
     * so a missing swing clip does not replay the locomotion clip on the arms.
     */
    private fun resolveClip(
        clips: List<AnimationItemImpl>,
        keys: List<String>,
        fallbackToFirst: Boolean,
    ): AnimationItemImpl? {
        for (key in keys) {
            val match = clips.firstOrNull { (it.name ?: "").lowercase().contains(key) }
            if (match != null) return match
        }
        return if (fallbackToFirst) clips.firstOrNull() else null
    }

    private fun applyLoopMode(state: SimpleAnimationState, loop: GlbLoopMode) {
        when (loop) {
            GlbLoopMode.LOOP -> {
                state.loop = true
                state.paused = false
            }
            // PLAY_ONCE / HOLD_ON_LAST_FRAME both stop at the clip's end; SimpleAnimationState coerces currentTime to
            // [0, duration] when loop is false, so it naturally freezes on the final frame once it runs out.
            GlbLoopMode.PLAY_ONCE, GlbLoopMode.HOLD_ON_LAST_FRAME -> {
                state.loop = false
                state.paused = false
            }
        }
    }

    /** Rebuilds the head index and the two upper-body masks when the scene changes (ArmorStand's ensureUpperBodyMasks). */
    private fun ensureBones(runtime: NpcAnimRuntime, scene: RenderScene) {
        val nodeCount = scene.nodes.size
        if (runtime.boneScene === scene && runtime.boneNodeCount == nodeCount) return
        runtime.boneScene = scene
        runtime.boneNodeCount = nodeCount

        runtime.headNode = resolveBone(scene, HumanoidTag.HEAD) ?: -1
        val left = BooleanArray(nodeCount)
        val right = BooleanArray(nodeCount)

        fun enable(mask: BooleanArray, tag: HumanoidTag) {
            val index = resolveBone(scene, tag) ?: return
            if (index in mask.indices) mask[index] = true
        }

        for (tag in TORSO_TAGS) {
            enable(left, tag)
            enable(right, tag)
        }
        for (tag in HumanoidTag.entries) {
            val name = tag.name
            if (!isArmTag(name)) continue
            when {
                name.startsWith("LEFT_") -> enable(left, tag)
                name.startsWith("RIGHT_") -> enable(right, tag)
            }
        }
        runtime.leftUpperBodyMask = left
        runtime.rightUpperBodyMask = right
    }

    /** Upper-body mask tags are the arm chains; legs/feet/toes are deliberately excluded. */
    private fun isArmTag(name: String) =
        name.contains("SHOULDER") ||
            name.contains("ARM") ||
            name.contains("HAND") ||
            name.contains("THUMB") ||
            name.contains("INDEX") ||
            name.contains("MIDDLE") ||
            name.contains("RING") ||
            name.contains("LITTLE")

    /**
     * Resolves a humanoid bone for rigs that do not carry GLB humanoid tags: humanoid tag first, then VRM/PMX bone
     * names, then the Mixamo naming scheme (`mixamorig:Head` etc.).
     */
    fun resolveBone(scene: RenderScene, tag: HumanoidTag): Int? {
        scene.humanoidTagMap[tag]?.let { return it.nodeIndex }
        tag.vrmName?.let { scene.nodeNameMap[it] }?.let { return it.nodeIndex }
        tag.pmxEnglish?.let { scene.nodeNameMap[it] }?.let { return it.nodeIndex }
        val mixamoName = MIXAMO_NAMES[tag] ?: return null
        return scene.nodeNameMap.entries.firstOrNull { (name, _) ->
            isMixamoBoneNamed(name, mixamoName)
        }?.value?.nodeIndex
    }

    /**
     * Matches a Mixamo bone in either separator form: a glTF straight out of Blender keeps `mixamorig:Head`, while a
     * rig that went through Godot's importer (and our clip baker therefore names its tracks that way) carries
     * `mixamorig_Head`. Only the prefix may contain the separator, so a plain `Left_Hand`-style name is not
     * mistaken for a Mixamo bone.
     */
    private fun isMixamoBoneNamed(name: String, want: String): Boolean {
        if (name.substringAfterLast(':').equals(want, ignoreCase = true)) return true
        val normalized = name.replace(':', '_')
        return normalized.startsWith(MIXAMO_PREFIX, ignoreCase = true) &&
            normalized.substring(MIXAMO_PREFIX.length).equals(want, ignoreCase = true)
    }
}
