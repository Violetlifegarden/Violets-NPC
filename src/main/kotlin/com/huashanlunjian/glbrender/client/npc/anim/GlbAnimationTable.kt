package com.huashanlunjian.glbrender.client.npc.anim

import kotlin.math.abs

/**
 * How a selected clip repeats, mirroring TouhouLittleMaid's GeckoLib `ILoopType`.
 *
 * - [LOOP] replays forever (locomotion / idle).
 * - [PLAY_ONCE] plays to its end then the selector is free to fall through to a lower-priority state (one-shots
 *   such as a death or hurt reaction).
 * - [HOLD_ON_LAST_FRAME] plays once and freezes on the final pose (a terminal pose, e.g. a corpse).
 */
enum class GlbLoopMode { LOOP, PLAY_ONCE, HOLD_ON_LAST_FRAME }

/** Coarse vehicle kind, so the animation table stays a pure value type (no `net.minecraft` imports). */
enum class GlbVehicle { NONE, HORSE, PIG, BOAT, OTHER }

/**
 * A snapshot of everything an animation predicate needs, derived once per frame from the rendered entity. Keeping
 * the selector's inputs in a plain value type (rather than passing the `net.minecraft` entity) makes the priority
 * table pure and unit-testable off the render thread, exactly like TouhouLittleMaid's `AnimationEvent`.
 *
 * Field meanings follow the vanilla client `LivingEntity` conventions the TLM selector reads:
 * [limbSwing]/[limbSwingAmount] are `walkAnimation.position/speed(partialTick)`, [hurtTime] is the red-tint counter,
 * [isDeadOrDying] is `isDeadOrDying`. The life/pose fields mirror ArmorStand's `getBaseState` order.
 */
data class GlbFrameCtx(
    val gameTick: Long,
    val partialTick: Float,
    val limbSwing: Float,
    val limbSwingAmount: Float,
    val onGround: Boolean,
    val isInWater: Boolean,
    val isSprinting: Boolean,
    val isDeadOrDying: Boolean,
    val hurtTime: Int,
    val isSleeping: Boolean = false,
    val vehicle: GlbVehicle = GlbVehicle.NONE,
    val isCrouching: Boolean = false,
    val isSwimming: Boolean = false,
    val isCrawling: Boolean = false,
    val isClimbing: Boolean = false,
    val climbDirection: Int = 0,
) {
    /** Coarse "is locomoting" flag, matching TLM's `|limbSwingAmount| > 0.15`. */
    val isMoving: Boolean get() = abs(limbSwingAmount) > MOVE_THRESHOLD

    companion object {
        private const val MOVE_THRESHOLD = 0.15f
    }
}

/**
 * One row of the animation priority table: a clip (matched by name fragments), how it loops, its priority bucket,
 * and the predicate that decides whether it applies this frame. This is TouhouLittleMaid's `AnimationState` record
 * reshaped for glTF clips and a pure [GlbFrameCtx] input.
 *
 * [clipKeys] are lowercase name fragments; the wiring layer resolves them against the model's actual glTF clip
 * names with a first-contains match, so a rig missing an exact "run" clip can still fall back to "walk" without
 * the table hardcoding asset-specific names.
 */
data class GlbAnimState(
    val id: String,
    val clipKeys: List<String>,
    val loop: GlbLoopMode,
    val priority: Int,
    val test: (GlbFrameCtx) -> Boolean,
)

/**
 * A priority-bucketed, first-match-wins animation selector. Rows are ordered by ascending [GlbAnimState.priority]
 * (HIGHEST first); `sortedBy` is stable so rows sharing a bucket keep their registration order — the same rule
 * TouhouLittleMaid's `AnimationManager.predicateMain` uses when it scans each priority bucket in order and takes
 * the first predicate that matches.
 *
 * The table is re-evaluated every frame from a fresh [GlbFrameCtx]; it holds no state of its own. The wiring layer
 * is responsible for deduping (only re-seeking a clip when the selected [GlbAnimState.id] actually changes) and for
 * crossfading.
 */
class GlbAnimationTable(states: List<GlbAnimState>) {
    private val ordered: List<GlbAnimState> = states.sortedBy { it.priority }

    /** The first matching row by priority, or null if nothing matches (the caller then leaves the pose untouched). */
    fun select(ctx: GlbFrameCtx): GlbAnimState? = ordered.firstOrNull { it.test(ctx) }

    val states: List<GlbAnimState> get() = ordered
}

/**
 * The stock locomotion/life-state table. The state order and predicates follow ArmorStand's
 * `ModelController.LiveSwitched.getBaseState` (death > vehicle > sleep > swim/crawl > climb > crouch > sprint >
 * walk > idle), with TLM's `AnimationRegister` thresholds for "is moving".
 *
 * Clip names are conventional glTF/animation-set keys; a rig that only ships a single walk cycle (e.g. CesiumMan)
 * still resolves every state to that one clip via the first-contains fallback in the wiring layer, degrading
 * gracefully instead of failing to animate.
 */
object GlbAnimationTables {
    const val PRIORITY_HIGHEST = 0
    const val PRIORITY_HIGH = 1
    const val PRIORITY_NORMAL = 2
    const val PRIORITY_LOW = 3
    const val PRIORITY_LOWEST = 4

    /** TLM's `MIN_SPEED`: below this limbSwingAmount a grounded mob counts as standing, not walking. */
    private const val WALK_THRESHOLD = 0.05f

    /** ArmorStand's `walkAnimationSpeed > .01` test for the crouch/crawl moving variants. */
    private const val POSE_MOVE_THRESHOLD = 0.01f

    fun default(): GlbAnimationTable = GlbAnimationTable(
        listOf(
            GlbAnimState("death", listOf("death", "die"), GlbLoopMode.PLAY_ONCE, PRIORITY_HIGHEST) {
                it.isDeadOrDying
            },
            GlbAnimState("onHorse", listOf("horse"), GlbLoopMode.LOOP, PRIORITY_HIGH) {
                it.vehicle == GlbVehicle.HORSE
            },
            GlbAnimState("onPig", listOf("pig"), GlbLoopMode.LOOP, PRIORITY_HIGH) {
                it.vehicle == GlbVehicle.PIG
            },
            GlbAnimState("onBoat", listOf("boat"), GlbLoopMode.LOOP, PRIORITY_HIGH) {
                it.vehicle == GlbVehicle.BOAT
            },
            GlbAnimState("ride", listOf("ride", "sit"), GlbLoopMode.LOOP, PRIORITY_HIGH) {
                it.vehicle == GlbVehicle.OTHER
            },
            GlbAnimState("sleep", listOf("sleep", "lay", "lying"), GlbLoopMode.LOOP, PRIORITY_HIGH) {
                it.isSleeping
            },
            GlbAnimState("attack", listOf("attack", "hurt"), GlbLoopMode.PLAY_ONCE, PRIORITY_NORMAL) {
                it.hurtTime > 0
            },
            GlbAnimState("swim", listOf("swim"), GlbLoopMode.LOOP, PRIORITY_NORMAL) {
                it.isSwimming
            },
            GlbAnimState("crawl", listOf("crawl"), GlbLoopMode.LOOP, PRIORITY_NORMAL) {
                it.isCrawling && it.limbSwingAmount > POSE_MOVE_THRESHOLD
            },
            GlbAnimState("crawlIdle", listOf("crawl", "prone"), GlbLoopMode.LOOP, PRIORITY_NORMAL) {
                it.isCrawling
            },
            GlbAnimState("climbUp", listOf("climb", "climbing"), GlbLoopMode.LOOP, PRIORITY_NORMAL) {
                it.isClimbing && it.climbDirection > 0
            },
            GlbAnimState("climbDown", listOf("climb", "climbing"), GlbLoopMode.LOOP, PRIORITY_NORMAL) {
                it.isClimbing && it.climbDirection < 0
            },
            GlbAnimState("climb", listOf("climb", "climbing"), GlbLoopMode.LOOP, PRIORITY_NORMAL) {
                it.isClimbing
            },
            GlbAnimState("jump", listOf("jump"), GlbLoopMode.LOOP, PRIORITY_NORMAL) {
                it.vehicle == GlbVehicle.NONE && !it.onGround && !it.isInWater
            },
            GlbAnimState("sneak", listOf("sneak", "crouch", "sneaking"), GlbLoopMode.LOOP, PRIORITY_LOW) {
                it.isCrouching && it.limbSwingAmount > POSE_MOVE_THRESHOLD
            },
            GlbAnimState("sneakIdle", listOf("sneak", "crouch", "sneaking"), GlbLoopMode.LOOP, PRIORITY_LOW) {
                it.isCrouching
            },
            GlbAnimState("sprint", listOf("sprint", "run", "jog"), GlbLoopMode.LOOP, PRIORITY_LOW) {
                it.isSprinting
            },
            GlbAnimState("walk", listOf("walk"), GlbLoopMode.LOOP, PRIORITY_LOW) {
                it.limbSwingAmount > WALK_THRESHOLD
            },
            GlbAnimState("idle", listOf("idle", "stand", "breathing"), GlbLoopMode.LOOP, PRIORITY_LOWEST) {
                true
            },
        ),
    )
}
