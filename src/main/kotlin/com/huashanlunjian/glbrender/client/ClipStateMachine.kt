package com.huashanlunjian.glbrender.client

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import top.fifthlight.blazerod.model.animation.AnimationState
import top.fifthlight.blazerod.render.api.animation.AnimationItemInstance
import top.fifthlight.blazerod.render.api.resource.ModelInstance
import top.fifthlight.blazerod.render.runtime.animation.AnimationItemImpl
import java.util.UUID
import kotlin.math.sqrt

/**
 * Per-player glTF clip selector + clock, mirroring ArmorStand's `LiveSwitched` state machine: pick a base clip
 * from movement (sprint / walk / idle), hard-cut on state change (no crossfade), and advance the clip on real
 * game ticks - `partialTick` is deliberately ignored for timing because [AnimationState] already receives it
 * through [PlayerAnimationContext]'s deltaTick.
 *
 * Sampling/writing bones is done by the ported [AnimationItemInstance] layer (channels write ABSOLUTE, which
 * overrides bind pose for the bones the clip touches). This class owns only clip choice and timing, so it stays
 * testable in isolation. For a single-clip rig (e.g. CesiumMan's one walk cycle) the one clip is used for every
 * moving state and frozen on idle - the selector degrades gracefully rather than hardcoding clip names.
 */
class ClipStateMachine(private val clips: List<AnimationItemImpl>) {
    enum class State { Idle, Walk, Sprint }

    private val idleClip = pick(listOf("idle", "standing", "stand"))
    private val walkClip = pick(listOf("walk"))
    private val runClip = pick(listOf("run", "sprint", "jog"))
    private val firstClip = clips.firstOrNull()

    private class Runtime(val itemInstance: AnimationItemInstance) {
        var state: AnimationState? = null
    }

    private val runtimes = HashMap<AnimationItemImpl, Runtime>()
    private val lastStates = HashMap<UUID, State>()

    // Test hook: force a movement state so a single-clip rig advances without the player actually walking
    // (used for headless capture verification). Unset = derive from real movement.
    private val forcedState: State? = System.getProperty("glbrender.clipState")?.trim()?.lowercase()?.let {
        when (it) {
            "sprint" -> State.Sprint
            "walk" -> State.Walk
            "idle" -> State.Idle
            else -> null
        }
    }

    private fun pick(keys: List<String>): AnimationItemImpl? = clips.firstOrNull { clip ->
        val name = (clip.name ?: "").lowercase()
        keys.any { name.contains(it) }
    }

    private fun selectClip(state: State): AnimationItemImpl? = when (state) {
        State.Sprint -> runClip ?: walkClip ?: idleClip ?: firstClip
        State.Walk -> walkClip ?: runClip ?: idleClip ?: firstClip
        State.Idle -> idleClip ?: walkClip ?: runClip ?: firstClip
    }

    /**
     * Drives the model for one player for this frame. Returns true when a clip wrote bone transforms (the
     * caller must then skip the procedural driver), false when there is no clip to play (caller falls back to
     * [VanillaPlayerPoseDriver]). A single shared [ModelInstance] means only one clip drives it per call, so
     * this is meant to be invoked once per draw for the entity actually being rendered.
     */
    fun apply(modelInstance: ModelInstance, player: Player, partialTick: Float): Boolean {
        if (firstClip == null) return false
        val state = movementState(player)
        val clip = selectClip(state) ?: return false
        val context = animationContext(player, partialTick)

        // Hard-cut: entering a new movement state restarts the chosen clip from its first frame, matching
        // upstream LiveSwitched (no crossfade). Resetting the runtime state re-seeks to zero next updateTime.
        val stateChanged = lastStates[player.uuid] != state
        lastStates[player.uuid] = state

        val runtime = runtimes.getOrPut(clip) {
            Runtime(AnimationItemInstance.Factory.of(clip))
        }
        if (stateChanged || runtime.state == null) {
            runtime.state = runtime.itemInstance.createState(context)
        }

        val animationState = runtime.state ?: return false
        animationState.updateTime(context)
        // Idle on a single-clip rig: freeze on the current frame instead of looping a run cycle while standing.
        if (state == State.Idle && clips.size == 1) {
            (animationState as? top.fifthlight.blazerod.model.animation.SimpleAnimationState)?.paused = true
        }
        val pendingValues = runtime.itemInstance.update(context, animationState)
        runtime.itemInstance.apply(modelInstance, pendingValues)
        return true
    }

    private fun animationContext(player: Player, partialTick: Float): PlayerAnimationContext {
        val gameTick = Minecraft.getInstance().level?.gameTime ?: player.tickCount.toLong()
        return PlayerAnimationContext(player, gameTick, partialTick)
    }

    private fun movementState(player: Player): State {
        forcedState?.let { return it }
        val movement = player.deltaMovement
        val speed = sqrt(movement.x * movement.x + movement.z * movement.z)
        return when {
            speed <= MOVE_EPSILON -> State.Idle
            player.isSprinting -> State.Sprint
            else -> State.Walk
        }
    }

    companion object {
        // deltaMovement is blocks/tick: idle ~0, walk ~0.1, sprint ~0.3, so 0.01 cleanly separates them.
        private const val MOVE_EPSILON = 0.01
    }
}
