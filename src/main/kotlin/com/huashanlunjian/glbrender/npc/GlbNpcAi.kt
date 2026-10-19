package com.huashanlunjian.glbrender.npc

import net.minecraft.nbt.CompoundTag

/**
 * The NPC's AI-tab block as one value object, mirroring CustomNPC's `DataAI` fields that our goal set can honor:
 * door interaction, shelter seeking, water avoidance, returning home, leaping at targets, rider control, and the
 * movement sub-screen's walking range + speed.
 *
 * Like [GlbNpcStats] it is a plain mutable holder — the editor mutates a local copy and commits it in one
 * [com.huashanlunjian.glbrender.npc.net.SetAiPacket], the server stores the canonical copy in [GlbNpcConfig.ai]
 * (synced through a single `COMPOUND_TAG` accessor), and [GlbNpc.applyAi] pushes it onto navigation/attributes.
 *
 * Units and defaults follow CNPC: `moveSpeed` is an integer where `attribute = moveSpeed / 20.0` (5 → 0.25), and
 * `walkingRange` is the block radius the NPC may roam from its home position before returning.
 */
data class GlbNpcAi(
    /** [DOOR_BREAK] / [DOOR_OPEN] / [DOOR_DISABLED] (CNPC `doorInteract`). */
    var doorMode: Int = DOOR_DISABLED,
    /** [SHELTER_DARKNESS] / [SHELTER_SUNLIGHT] / [SHELTER_DISABLED] (CNPC `findShelter`). */
    var shelter: Int = SHELTER_DISABLED,
    /** Avoid water tiles through a pathfinding malus, like an enderman (CNPC `avoidsWater`). */
    var avoidsWater: Boolean = false,
    /** Walk back to the spawn position when outside [walkingRange] (CNPC `returnToStart`). */
    var returnToStart: Boolean = true,
    /** Leap at nearby attack targets in small jumps, wolf-style (CNPC `canLeap`). */
    var leapAtTarget: Boolean = false,
    /** Let the first passenger steer the NPC (CNPC `mountControl`). */
    var mountControl: Boolean = false,
    /** Block radius the NPC may wander from its home position before returning. */
    var walkingRange: Int = 10,
    /** Movement speed in CNPC units: the `MOVEMENT_SPEED` attribute is set to this / 20.0. */
    var moveSpeed: Int = 5,
) {
    fun copyAi(): GlbNpcAi = copy()

    fun toTag(): CompoundTag = CompoundTag().also { tag ->
        tag.putInt(KEY_DOOR_MODE, doorMode)
        tag.putInt(KEY_SHELTER, shelter)
        tag.putBoolean(KEY_AVOIDS_WATER, avoidsWater)
        tag.putBoolean(KEY_RETURN_TO_START, returnToStart)
        tag.putBoolean(KEY_LEAP_AT_TARGET, leapAtTarget)
        tag.putBoolean(KEY_MOUNT_CONTROL, mountControl)
        tag.putInt(KEY_WALKING_RANGE, walkingRange)
        tag.putInt(KEY_MOVE_SPEED, moveSpeed)
    }

    /** Server-side clamp applied to every packet-carried copy, so a bad client cannot write out-of-range values. */
    fun coerced(): GlbNpcAi = copy(
        doorMode = doorMode.coerceIn(DOOR_BREAK, DOOR_DISABLED),
        shelter = shelter.coerceIn(SHELTER_DARKNESS, SHELTER_DISABLED),
        walkingRange = walkingRange.coerceIn(1, MAX_WALKING_RANGE),
        moveSpeed = moveSpeed.coerceIn(0, MAX_MOVE_SPEED),
    )

    /** The `MOVEMENT_SPEED` attribute value for [moveSpeed] (CNPC's `moveSpeed / 20.0`). */
    fun movementSpeed(): Double = moveSpeed / 20.0

    companion object {
        // doorMode values (CustomNPC DataAI.doorInteract).
        const val DOOR_BREAK = 0
        const val DOOR_OPEN = 1
        const val DOOR_DISABLED = 2

        // shelter values (CustomNPC DataAI.findShelter).
        const val SHELTER_DARKNESS = 0
        const val SHELTER_SUNLIGHT = 1
        const val SHELTER_DISABLED = 2

        const val MAX_WALKING_RANGE = 100
        const val MAX_MOVE_SPEED = 100

        val DOOR_OPTIONS = arrayOf("Break", "Open", "Disabled")
        val SHELTER_OPTIONS = arrayOf("Darkness", "Sunlight", "Disabled")
        val MOVEMENT_TYPE_OPTIONS = arrayOf("Standing", "Wandering")

        /** Parses an AI compound, merging over defaults so an empty/older tag never silently disables return-home. */
        fun fromTag(tag: CompoundTag): GlbNpcAi {
            val merged = GlbNpcAi().toTag().also { it.merge(tag) }
            return GlbNpcAi(
                doorMode = merged.getInt(KEY_DOOR_MODE),
                shelter = merged.getInt(KEY_SHELTER),
                avoidsWater = merged.getBoolean(KEY_AVOIDS_WATER),
                returnToStart = merged.getBoolean(KEY_RETURN_TO_START),
                leapAtTarget = merged.getBoolean(KEY_LEAP_AT_TARGET),
                mountControl = merged.getBoolean(KEY_MOUNT_CONTROL),
                walkingRange = merged.getInt(KEY_WALKING_RANGE),
                moveSpeed = merged.getInt(KEY_MOVE_SPEED),
            )
        }

        private const val KEY_DOOR_MODE = "DoorMode"
        private const val KEY_SHELTER = "Shelter"
        private const val KEY_AVOIDS_WATER = "AvoidsWater"
        private const val KEY_RETURN_TO_START = "ReturnToStart"
        private const val KEY_LEAP_AT_TARGET = "LeapAtTarget"
        private const val KEY_MOUNT_CONTROL = "MountControl"
        private const val KEY_WALKING_RANGE = "WalkingRange"
        private const val KEY_MOVE_SPEED = "MoveSpeed"
    }
}
