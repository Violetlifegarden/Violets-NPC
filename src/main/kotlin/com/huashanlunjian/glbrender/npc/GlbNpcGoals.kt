package com.huashanlunjian.glbrender.npc

import net.minecraft.world.entity.ai.goal.AvoidEntityGoal
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import java.util.EnumSet

/**
 * Self-gating NPC goals. Each checks the live [GlbNpcConfig] inside [canUse] rather than being added/removed from the
 * [net.minecraft.world.entity.ai.goal.GoalSelector], so toggling `wander`/`followOwner` at runtime (from the Step 5
 * editor) takes effect on the next goal evaluation with no selector teardown.
 *
 * This matters because `Mob`'s constructor calls `registerGoals()` before the Kotlin `config` property is initialized;
 * goal constructors therefore must NOT read config — only the per-tick `canUse`/`canContinueToUse` may.
 */

/** Wanders randomly, but only while [GlbNpcConfig.wander] is on. */
class GlbWanderGoal(private val npc: GlbNpc) : WaterAvoidingRandomStrollGoal(npc, 1.0) {
    override fun canUse(): Boolean = npc.config.wander && super.canUse()
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
 * [GlbNpcConfig.PROFESSION_GUARD] — the execution half of the guard profession (CustomNPC's generic melee attack goal,
 * which is only live once a target is set by [GlbGuardTargetGoal]). Damage comes from the `ATTACK_DAMAGE` attribute
 * registered on [GlbNpc]. Holds MOVE+LOOK+JUMP, so it is outranked by [GlbSitGoal] (a sitting guard stays put).
 */
class GlbGuardAttackGoal(private val npc: GlbNpc) : MeleeAttackGoal(npc, 1.2, true) {
    override fun canUse(): Boolean = npc.config.profession == GlbNpcConfig.PROFESSION_GUARD && super.canUse()
    override fun canContinueToUse(): Boolean = npc.config.profession == GlbNpcConfig.PROFESSION_GUARD && super.canContinueToUse()
}
