package com.huashanlunjian.glbrender.npc

import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
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
