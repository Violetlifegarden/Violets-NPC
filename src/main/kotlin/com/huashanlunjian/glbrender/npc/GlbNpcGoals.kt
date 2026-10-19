package com.huashanlunjian.glbrender.npc

import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.LeapAtTargetGoal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.entity.ai.goal.RestrictSunGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.EnumSet
import kotlin.math.sqrt

/**
 * Self-gating NPC goals. Each checks the live [GlbNpcConfig] inside [canUse] rather than being added/removed from the
 * [net.minecraft.world.entity.ai.goal.GoalSelector], so toggling `wander`/`followOwner` at runtime (from the Step 5
 * editor) takes effect on the next goal evaluation with no selector teardown.
 *
 * This matters because `Mob`'s constructor calls `registerGoals()` before the Kotlin `config` property is initialized;
 * goal constructors therefore must NOT read config — only the per-tick `canUse`/`canContinueToUse` may.
 */

/**
 * Wanders randomly, but only while [GlbNpcConfig.wander] is on. Targets are clamped into the `walkingRange` circle
 * around the home anchor (CNPC `EntityAIWander.getVec`), so the NPC does not rely on a hard path restriction to stay
 * near home — a hard restriction makes it reverse the moment it steps one block past the radius, which looks like
 * inching along in a corridor.
 */
class GlbWanderGoal(private val npc: GlbNpc) : WaterAvoidingRandomStrollGoal(npc, 1.0) {
    override fun canUse(): Boolean = npc.config.wander && super.canUse()

    override fun getPosition(): Vec3? {
        val position = super.getPosition() ?: return null
        val home = npc.homeAnchor() ?: return position
        val range = npc.config.ai.walkingRange.toDouble()
        val dx = position.x - home.x
        val dz = position.z - home.z
        val distSq = dx * dx + dz * dz
        if (distSq <= range * range) return position
        val scale = range / sqrt(distSq)
        return Vec3(home.x + dx * scale, position.y, home.z + dz * scale)
    }
}

/**
 * Follows the nearest player within [FOLLOW_RANGE] while [GlbNpcConfig.followOwner] is on, stopping once inside
 * [STOP_DISTANCE]. Re-paths periodically rather than every tick to avoid thrashing the pathfinder. There is no
 * owner/tame concept on this NPC yet, so "owner" is approximated by the closest player — a real ownership binding can
 * replace [nearestPlayer] later without touching the goal's shape.
 */
class GlbFollowPlayerGoal(private val npc: GlbNpc) : Goal() {
    init {
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK))
    }

    private var target: Player? = null
    private var repathCooldown = 0

    override fun canUse(): Boolean {
        if (!npc.config.followOwner) return false
        val player = nearestPlayer() ?: return false
        if (player.distanceToSqr(npc) < STOP_DISTANCE * STOP_DISTANCE) return false
        target = player
        return true
    }

    override fun canContinueToUse(): Boolean {
        if (!npc.config.followOwner) return false
        val player = target ?: return false
        return !player.isRemoved && player.distanceToSqr(npc) > STOP_DISTANCE * STOP_DISTANCE
    }

    override fun start() {
        repathCooldown = 0
        moveToTarget()
    }

    override fun tick() {
        if (repathCooldown-- <= 0) {
            repathCooldown = REPATH_INTERVAL
            moveToTarget()
        }
    }

    override fun stop() {
        target = null
        npc.navigation.stop()
    }

    private fun moveToTarget() {
        val player = target ?: return
        npc.navigation.moveTo(player, SPEED)
        npc.lookControl.setLookAt(player)
    }

    private fun nearestPlayer(): Player? {
        var best: Player? = null
        var bestDist = FOLLOW_RANGE * FOLLOW_RANGE
        for (player in npc.level().players()) {
            if (player.isSpectator || player.isRemoved) continue
            val dist = player.distanceToSqr(npc)
            if (dist < bestDist) {
                bestDist = dist
                best = player
            }
        }
        return best
    }

    companion object {
        private const val FOLLOW_RANGE = 16.0
        private const val STOP_DISTANCE = 3.0
        private const val SPEED = 1.0
        private const val REPATH_INTERVAL = 10
    }
}

/**
 * Holds the MOVE + JUMP goal flags while [GlbNpcConfig.sit] is on, so it outranks wander/follow (which also want MOVE)
 * and they cannot start — the vanilla wolf `SitDownGoal` mechanism, self-gated like our other goals so toggling sit
 * needs no selector teardown. Registered above the move goals but below `FloatGoal`, so an NPC told to sit in water
 * still swims out.
 */
class GlbSitGoal(private val npc: GlbNpc) : Goal() {
    init {
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP))
    }

    override fun canUse(): Boolean = npc.config.sit
    override fun canContinueToUse(): Boolean = npc.config.sit
    override fun start() {
        npc.navigation.stop()
    }

    override fun tick() {
        // Keep navigation stopped each tick: a wander goal that started before sit was set could otherwise leave a
        // running path. Cheaper than tearing the selector down and safe because sit outranks the move goals.
        npc.navigation.stop()
    }
}

/** Looks at nearby players only while [GlbNpcConfig.lookPlayer] is on (a self-gated wrapper over the vanilla goal). */
class GlbLookPlayerGoal(private val npc: GlbNpc) : LookAtPlayerGoal(npc, Player::class.java, 8.0f) {
    override fun canUse(): Boolean = npc.config.lookPlayer && super.canUse()
}

/**
 * Continuously avoids nearby players while [GlbNpcConfig.onAttack] is [GlbNpcConfig.RESPONSE_FLEE] — the vanilla
 * `AvoidEntityGoal` (flees within a radius, sprinting when cornered), gated so switching the response takes effect on
 * the next evaluation with no selector teardown.
 */
class GlbFleeGoal(private val npc: GlbNpc) : AvoidEntityGoal<Player>(npc, Player::class.java, 8.0f, 1.6, 1.6) {
    override fun canUse(): Boolean = npc.config.onAttack == GlbNpcConfig.RESPONSE_FLEE && super.canUse()
}

/**
 * Flees from the nearest player only AFTER taking damage, while [GlbNpcConfig.onAttack] is
 * [GlbNpcConfig.RESPONSE_PANIC]: the same `AvoidEntityGoal` mechanism, but [canUse] also requires `hurtTime > 0`
 * (set for a few ticks right after [net.minecraft.world.entity.LivingEntity.hurt]), so an undamaged panicked NPC
 * stays put. Uses a wider radius + faster escape than [GlbFleeGoal] to read as panic rather than casual wariness.
 */
class GlbPanicGoal(private val npc: GlbNpc) : AvoidEntityGoal<Player>(npc, Player::class.java, 16.0f, 2.0, 2.5) {
    override fun canUse(): Boolean =
        npc.config.onAttack == GlbNpcConfig.RESPONSE_PANIC && npc.hurtTime > 0 && super.canUse()
}

/**
 * Proactively acquires the nearest hostile [Monster] as the NPC's attack target, but only while
 * [GlbNpcConfig.profession] is [GlbNpcConfig.PROFESSION_GUARD] — CustomNPC's `JobGuard` feeding a shared target
 * predicate, reshaped as a self-gating goal so switching profession re-gates on the next tick with no teardown.
 *
 * Targets monsters (the vanilla `Monster` hostiles) rather than players: a guard fighting what players fight reads as
 * protective and needs no ownership/faction model, and it can never turn on the player standing next to it. A
 * configurable entity-id whitelist (CNPC's `GuardTargets`) is the natural later extension.
 */
class GlbGuardTargetGoal(private val npc: GlbNpc) : NearestAttackableTargetGoal<Monster>(npc, Monster::class.java, true) {
    override fun canUse(): Boolean = npc.config.profession == GlbNpcConfig.PROFESSION_GUARD && super.canUse()
    override fun canContinueToUse(): Boolean = npc.config.profession == GlbNpcConfig.PROFESSION_GUARD && super.canContinueToUse()
}

/**
 * Chases and melee-hits the NPC's current attack target while [GlbNpcConfig.profession] is
 * [GlbNpcConfig.PROFESSION_GUARD] and the Stats attack type is [GlbNpcStats.ATTACK_MELEE] — the execution half of the
 * guard profession (CustomNPC's generic melee attack goal, which is only live once a target is set by
 * [GlbGuardTargetGoal]). Damage comes from the `ATTACK_DAMAGE` attribute; [GlbNpcStats.meleeSpeed] drives our own
 * cooldown and [GlbNpcStats.meleeRange] replaces vanilla's bounding-box reach. Holds MOVE+LOOK+JUMP, so it is
 * outranked by [GlbSitGoal] (a sitting guard stays put).
 */
class GlbGuardAttackGoal(private val npc: GlbNpc) : MeleeAttackGoal(npc, 1.2, true) {

    private var cooldownTicks = 0

    override fun canUse(): Boolean =
        npc.config.profession == GlbNpcConfig.PROFESSION_GUARD &&
            npc.config.stats.attackType == GlbNpcStats.ATTACK_MELEE && super.canUse()

    override fun canContinueToUse(): Boolean =
        npc.config.profession == GlbNpcConfig.PROFESSION_GUARD &&
            npc.config.stats.attackType == GlbNpcStats.ATTACK_MELEE && super.canContinueToUse()

    override fun start() {
        super.start()
        cooldownTicks = 0
    }

    override fun stop() {
        super.stop()
        cooldownTicks = 0
    }

    override fun tick() {
        if (cooldownTicks > 0) cooldownTicks--
        super.tick()
    }

    // The vanilla cooldown field is private and fixed at 20 ticks; `isTimeToAttack` is the supported hook, so the
    // configurable interval lives in our own counter and the vanilla one is left at its default (always ready).
    override fun isTimeToAttack(): Boolean = cooldownTicks <= 0

    override fun resetAttackCooldown() {
        super.resetAttackCooldown()
        cooldownTicks = npc.config.stats.meleeSpeed
    }

    override fun canPerformAttack(target: LivingEntity): Boolean {
        if (!isTimeToAttack()) return false
        val range = npc.config.stats.meleeRange.toDouble()
        return npc.distanceToSqr(target) <= range * range && npc.sensing.hasLineOfSight(target)
    }
}

/**
 * Chases the target into firing range and shoots vanilla arrows while the Stats attack type is
 * [GlbNpcStats.ATTACK_RANGED]. Timing/counts come from the Stats block (min/max delay, burst count/delay, shots per
 * volley); [GlbNpcStats.rangedIndirect] relaxes the line-of-sight requirement for indirect fire. Only one ranged
 * guard can exist per NPC and it never runs alongside [GlbGuardAttackGoal], because the attack type gates both.
 */
class GlbRangedAttackGoal(private val npc: GlbNpc) : Goal() {

    init {
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK))
    }

    private var cooldown = 0
    private var burstLeft = 1
    private var repathCooldown = 0

    override fun canUse(): Boolean {
        if (!rangedGuard()) return false
        val target = npc.target ?: return false
        return target.isAlive && npc.distanceToSqr(target) <= aggroRangeSq()
    }

    override fun canContinueToUse(): Boolean {
        if (!rangedGuard()) return false
        val target = npc.target ?: return false
        return target.isAlive && npc.distanceToSqr(target) <= aggroRangeSq()
    }

    override fun start() {
        burstLeft = npc.config.stats.rangedBurstCount
        cooldown = randomDelay()
        repathCooldown = 0
    }

    override fun stop() {
        npc.navigation.stop()
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun tick() {
        val target = npc.target ?: return
        val stats = npc.config.stats
        npc.lookControl.setLookAt(target, 30f, 30f)

        val distanceSq = npc.distanceToSqr(target)
        val range = stats.rangedRange.toDouble()
        val inRange = distanceSq <= range * range
        val outsideDeadzone = stats.rangedMeleeDistance <= 0 ||
            distanceSq > stats.rangedMeleeDistance.toDouble() * stats.rangedMeleeDistance

        if (!inRange) {
            chase(target)
            return
        }
        // In range but too close, or without a firing window: hold position instead of walking into the target.
        npc.navigation.stop()
        if (!outsideDeadzone || !hasFiringWindow(target, stats, distanceSq)) return
        if (cooldown > 0) {
            cooldown--
            return
        }
        fireVolley(target, stats)
        if (burstLeft > 1) {
            burstLeft--
            cooldown = stats.rangedBurstDelay
        } else {
            burstLeft = stats.rangedBurstCount
            cooldown = randomDelay()
        }
    }

    private fun rangedGuard(): Boolean =
        npc.config.profession == GlbNpcConfig.PROFESSION_GUARD &&
            npc.config.stats.attackType == GlbNpcStats.ATTACK_RANGED

    private fun aggroRangeSq(): Double {
        val range = npc.config.stats.aggroRange.toDouble()
        return range * range
    }

    private fun chase(target: LivingEntity) {
        if (repathCooldown-- > 0) return
        repathCooldown = REPATH_INTERVAL
        npc.navigation.moveTo(target, SPEED)
    }

    /** Direct sight is required unless the indirect mode allows lobbing shots over cover. */
    private fun hasFiringWindow(target: LivingEntity, stats: GlbNpcStats, distanceSq: Double): Boolean {
        if (npc.sensing.hasLineOfSight(target)) return true
        return when (stats.rangedIndirect) {
            GlbNpcStats.RANGED_INDIRECT_HIDDEN -> true
            GlbNpcStats.RANGED_INDIRECT_DISTANT -> {
                val half = stats.rangedRange / 2.0
                distanceSq > half * half
            }
            else -> false
        }
    }

    private fun fireVolley(target: LivingEntity, stats: GlbNpcStats) {
        repeat(stats.rangedShotCount) { npc.performRangedAttack(target, stats.rangedSpeed / 10f) }
    }

    private fun randomDelay(): Int {
        val stats = npc.config.stats
        return stats.rangedMinDelay + npc.random.nextInt(stats.rangedMaxDelay - stats.rangedMinDelay + 1)
    }

    companion object {
        private const val SPEED = 1.0
        private const val REPATH_INTERVAL = 10
    }
}

// ---- AI-tab goals (CustomNPC DataAI) ----
// All of them self-gate on config.ai, like the other goals, so the door/shelter/return/leap switches take effect on
// the next goal evaluation with no selector teardown.

/**
 * Opens (or, in break mode, destroys) the next closed wooden door or fence gate on the NPC's path.
 *
 * Written from scratch instead of reusing vanilla [net.minecraft.world.entity.ai.goal.OpenDoorGoal] / `BreakDoorGoal`
 * because those only react when `horizontalCollision` is set and only ever handle `DoorBlock`: a closed fence gate is
 * not a `DoorBlock`, and by the time a mob is pressed against a gate the pathfinder has usually already given up.
 * This goal instead looks at the current path nodes (and the block directly ahead as a fallback), opens at up to two
 * and a half blocks, and keeps the door/gate open until the NPC has walked away. [GlbNpcNavigation] makes closed
 * gates pathable so a path to the other side exists in the first place.
 */
class GlbDoorGoal(private val npc: GlbNpc) : Goal() {
    private var doorPos: BlockPos? = null

    /** True when this goal opened the block (so [stop] may close it again). */
    private var opened = false

    private var breakTicks = 0
    private var lastBreakStage = -1

    override fun canUse(): Boolean {
        val mode = npc.config.ai.doorMode
        if (mode == GlbNpcAi.DOOR_DISABLED) return false
        if (mode == GlbNpcAi.DOOR_BREAK && !npc.level().gameRules.getBoolean(GameRules.RULE_MOBGRIEFING)) return false
        val target = findClosedDoor() ?: return false
        doorPos = target
        return true
    }

    override fun canContinueToUse(): Boolean {
        val pos = doorPos ?: return false
        if (npc.config.ai.doorMode == GlbNpcAi.DOOR_DISABLED) return false
        if (npc.blockPosition().distSqr(pos) > LEAVE_RANGE_SQR) return false
        val state = npc.level().getBlockState(pos)
        // A block opened by someone else (or destroyed) no longer needs our attention.
        return if (isClosedBarrier(state)) true else opened
    }

    override fun start() {
        opened = false
        breakTicks = 0
        lastBreakStage = -1
    }

    override fun tick() {
        val pos = doorPos ?: return
        val level = npc.level()
        val state = level.getBlockState(pos)
        if (!isClosedBarrier(state)) return
        if (npc.config.ai.doorMode == GlbNpcAi.DOOR_BREAK) {
            tickBreak(pos, state)
            return
        }
        when (val block = state.block) {
            is DoorBlock -> if (!state.getValue(DoorBlock.OPEN)) {
                block.setOpen(npc, level, state, pos, true)
                opened = true
                npc.swing(InteractionHand.MAIN_HAND)
            }
            is FenceGateBlock -> if (!state.getValue(FenceGateBlock.OPEN)) {
                level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, true), UPDATE_CLIENTS)
                level.playSound(
                    null, pos, block.openSound, SoundSource.BLOCKS,
                    1.0f, level.random.nextFloat() * 0.1f + 0.9f,
                )
                opened = true
                npc.swing(InteractionHand.MAIN_HAND)
            }
        }
    }

    override fun stop() {
        val pos = doorPos ?: return
        val level = npc.level()
        level.destroyBlockProgress(npc.id, pos, -1)
        if (opened) closeDoor(pos)
        doorPos = null
        opened = false
        breakTicks = 0
        lastBreakStage = -1
    }

    private fun tickBreak(pos: BlockPos, state: BlockState) {
        val level = npc.level()
        if (breakTicks % 20 == 0) {
            npc.swing(InteractionHand.MAIN_HAND)
            level.levelEvent(null, 1010, pos, 0)
        }
        breakTicks++
        val stage = (breakTicks.toFloat() / BREAK_TICKS * 10f).toInt()
        if (stage != lastBreakStage) {
            level.destroyBlockProgress(npc.id, pos, stage)
            lastBreakStage = stage
        }
        if (breakTicks < BREAK_TICKS) return
        level.destroyBlockProgress(npc.id, pos, -1)
        level.destroyBlock(pos, false)
        level.levelEvent(null, 2001, pos, Block.getId(state))
    }

    private fun closeDoor(pos: BlockPos) {
        val level = npc.level()
        val state = level.getBlockState(pos)
        when (val block = state.block) {
            is DoorBlock -> if (state.getValue(DoorBlock.OPEN)) {
                block.setOpen(npc, level, state, pos, false)
            }
            is FenceGateBlock -> if (state.getValue(FenceGateBlock.OPEN)) {
                level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, false), UPDATE_CLIENTS)
                level.playSound(
                    null, pos, block.closeSound, SoundSource.BLOCKS,
                    1.0f, level.random.nextFloat() * 0.1f + 0.9f,
                )
            }
        }
    }

    /**
     * Looks for a closed wooden door or fence gate on the next few path nodes, then directly ahead of the NPC. Path
     * nodes are the reliable source (they are exactly the blocks the NPC intends to walk through); the look-ahead
     * catches the case where the path ends just short of the door.
     */
    private fun findClosedDoor(): BlockPos? {
        val level = npc.level()
        val path = npc.navigation.path
        if (path != null && !path.isDone) {
            val start = path.nextNodeIndex
            val end = minOf(start + 2, path.nodeCount - 1)
            for (i in start..end) {
                val node = path.getNode(i)
                if (npc.distanceToSqr(node.x.toDouble(), npc.y, node.z.toDouble()) > OPEN_RANGE_SQR) continue
                for (dy in 0..1) {
                    val pos = BlockPos(node.x, node.y + dy, node.z)
                    if (isClosedBarrier(level.getBlockState(pos))) return pos
                }
            }
        }
        val base = npc.blockPosition()
        val ahead = base.relative(npc.direction)
        for (column in arrayOf(ahead, base)) {
            for (dy in 0..1) {
                val pos = column.above(dy)
                if (isClosedBarrier(level.getBlockState(pos))) return pos
            }
        }
        return null
    }

    private fun isClosedBarrier(state: BlockState): Boolean = when (val block = state.block) {
        is DoorBlock -> DoorBlock.isWoodenDoor(state) && !state.getValue(DoorBlock.OPEN)
        is FenceGateBlock -> !state.getValue(FenceGateBlock.OPEN)
        else -> false
    }

    private companion object {
        /** Path nodes farther than 2.5 blocks are handled once the NPC gets closer. */
        const val OPEN_RANGE_SQR = 6.25

        /** Keep the door open until the NPC is three blocks past it. */
        const val LEAVE_RANGE_SQR = 9.0

        const val BREAK_TICKS = 240

        /** `Block.UPDATE_CLIENTS` / `UPDATE_NEIGHBORS` — vanilla's flag for interactive state changes. */
        const val UPDATE_CLIENTS = 10
    }
}

/** Wolf-style small jumps at the current attack target while [GlbNpcAi.leapAtTarget] is on. */
class GlbLeapGoal(private val npc: GlbNpc) : LeapAtTargetGoal(npc, 0.4f) {
    override fun canUse(): Boolean = npc.config.ai.leapAtTarget && super.canUse()
    override fun canContinueToUse(): Boolean = npc.config.ai.leapAtTarget && super.canContinueToUse()
}

/**
 * Walks back to the home anchor once the NPC has been pushed outside `walkingRange` (knockback, fleeing, following)
 * and is idle, mirroring CNPC's `EntityAIReturn` (`navigation.isDone()` gate). Deliberately not vanilla's
 * `MoveTowardsRestrictionGoal`: that one reacts the instant the NPC crosses the radius, so combined with wander it
 * cancels the current path every step and makes the NPC shuffle one block at a time instead of travelling.
 */
class GlbReturnHomeGoal(private val npc: GlbNpc) : Goal() {
    init {
        setFlags(EnumSet.of(Goal.Flag.MOVE))
    }

    private var retryCooldown = 0

    override fun canUse(): Boolean {
        if (!npc.config.ai.returnToStart) return false
        if (retryCooldown > 0) {
            retryCooldown--
            return false
        }
        val home = npc.homeAnchor() ?: return false
        if (!npc.navigation.isDone) return false
        return npc.distanceToHomeSqr() > npc.homeRangeSqr()
    }

    override fun canContinueToUse(): Boolean =
        npc.config.ai.returnToStart && !npc.navigation.isDone

    override fun start() {
        val home = npc.homeAnchor() ?: return
        npc.navigation.moveTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5, SPEED)
    }

    override fun stop() {
        npc.navigation.stop()
        // If the way home is blocked, do not recompute the same failing path every tick.
        retryCooldown = RETRY_TICKS
    }

    private companion object {
        const val SPEED = 1.0
        const val RETRY_TICKS = 40
    }
}

/** Vanilla's avoid-sunlight navigation flag, gated on the Sunlight shelter mode. */
class GlbRestrictSunGoal(private val npc: GlbNpc) : RestrictSunGoal(npc) {
    override fun canUse(): Boolean = npc.config.ai.shelter == GlbNpcAi.SHELTER_SUNLIGHT && super.canUse()
    override fun canContinueToUse(): Boolean =
        npc.config.ai.shelter == GlbNpcAi.SHELTER_SUNLIGHT && super.canContinueToUse()
}

/**
 * CustomNPC `EntityAIMoveIndoors` analogue for the Darkness shelter mode: at night or while raining, walk to a nearby
 * spot with no sky access. Samples ten random positions (±10 x/z, ±3 y) and accepts the first that is roofed and not
 * inside a solid block (the same sanity filter CNPC gets from its `getWalkTargetValue` override).
 */
class GlbIndoorShelterGoal(private val npc: GlbNpc) : Goal() {
    init {
        setFlags(EnumSet.of(Goal.Flag.MOVE))
    }

    private var shelter: BlockPos? = null

    override fun canUse(): Boolean {
        if (npc.config.ai.shelter != GlbNpcAi.SHELTER_DARKNESS) return false
        val level = npc.level()
        // CNPC: only seek shelter when the sky is dark-ish (night or rain), and only in skylight dimensions.
        if (level.isDay && !level.isRaining) return false
        if (!level.dimensionType().hasSkyLight()) return false
        val pos = npc.blockPosition()
        if (!level.canSeeSkyFromBelowWater(pos) && level.getLightEmission(pos) > 8) return false
        shelter = findShelter() ?: return false
        return true
    }

    override fun canContinueToUse(): Boolean = !npc.navigation.isDone

    override fun start() {
        val target = shelter ?: return
        npc.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 1.0)
    }

    override fun stop() {
        shelter = null
    }

    private fun findShelter(): BlockPos? {
        val level = npc.level()
        val origin = npc.blockPosition()
        val random = npc.random
        repeat(SAMPLE_COUNT) {
            val pos = origin.offset(
                random.nextInt(SAMPLE_RADIUS * 2 + 1) - SAMPLE_RADIUS,
                random.nextInt(7) - 3,
                random.nextInt(SAMPLE_RADIUS * 2 + 1) - SAMPLE_RADIUS,
            )
            if (!level.canSeeSkyFromBelowWater(pos) && !level.getBlockState(pos).isSolidRender(level, pos)) return pos
        }
        return null
    }

    private companion object {
        const val SAMPLE_COUNT = 10
        const val SAMPLE_RADIUS = 10
    }
}

/**
 * CustomNPC `EntityAIFindShade` analogue for the Sunlight shelter mode: while it is day and the NPC stands in direct
 * sun, walk to a nearby roofed spot. Same ten-sample search as [GlbIndoorShelterGoal]; [GlbRestrictSunGoal] keeps the
 * pathfinder from then walking back out into the sun.
 */
class GlbSunShelterGoal(private val npc: GlbNpc) : Goal() {
    init {
        setFlags(EnumSet.of(Goal.Flag.MOVE))
    }

    private var shelter: BlockPos? = null

    override fun canUse(): Boolean {
        if (npc.config.ai.shelter != GlbNpcAi.SHELTER_SUNLIGHT) return false
        val level = npc.level()
        if (!level.isDay) return false
        if (!level.canSeeSkyFromBelowWater(npc.blockPosition())) return false
        shelter = findShelter() ?: return false
        return true
    }

    override fun canContinueToUse(): Boolean = !npc.navigation.isDone

    override fun start() {
        val target = shelter ?: return
        npc.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 1.0)
    }

    override fun stop() {
        shelter = null
    }

    private fun findShelter(): BlockPos? {
        val level = npc.level()
        val origin = npc.blockPosition()
        val random = npc.random
        repeat(SAMPLE_COUNT) {
            val pos = origin.offset(
                random.nextInt(SAMPLE_RADIUS * 2 + 1) - SAMPLE_RADIUS,
                random.nextInt(7) - 3,
                random.nextInt(SAMPLE_RADIUS * 2 + 1) - SAMPLE_RADIUS,
            )
            if (!level.canSeeSkyFromBelowWater(pos) && !level.getBlockState(pos).isSolidRender(level, pos)) return pos
        }
        return null
    }

    private companion object {
        const val SAMPLE_COUNT = 10
        const val SAMPLE_RADIUS = 10
    }
}
