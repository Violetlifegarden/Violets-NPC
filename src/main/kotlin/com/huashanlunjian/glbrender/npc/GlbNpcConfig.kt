package com.huashanlunjian.glbrender.npc

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData

/**
 * Per-instance NPC configuration, in TouhouLittleMaid's `MaidConfigManager` / CustomNPC's `Data*` shape: one class
 * owns the three views of the same fields — [SynchedEntityData] (auto-replicates to every tracking client, so the
 * renderer reads them with no custom packet), entity NBT (persistence), and typed getters/setters (used by the GUI,
 * goals, and future C2S edit packets alike).
 *
 * The transform fields default to [Float.NaN], meaning "inherit the model descriptor's value"; a NaN sentinel lets a
 * per-NPC override coexist with the shared per-model default without duplicating the default into every entity. It is
 * common (both dists): the server persists/edits it, the client renderer reads the synced values.
 *
 * The [EntityDataAccessor]s and [defineSynchedData] live in the companion because Minecraft calls
 * `defineSynchedData` from the `Entity` superclass constructor, before this instance exists — so definition must be
 * static, while the get/set wrappers need the live entity.
 */
class GlbNpcConfig(private val npc: GlbNpc) {

    /** Model id this NPC renders as; empty falls back to [GlbModels.defaultId] on the client. */
    var modelId: String
        get() = npc.entityData.get(DATA_MODEL_ID)
        set(value) = npc.entityData.set(DATA_MODEL_ID, value)

    /** Per-instance scale override, or [Float.NaN] to inherit the descriptor's [GlbModelDescriptor.scale]. */
    var scale: Float
        get() = npc.entityData.get(DATA_SCALE)
        set(value) = npc.entityData.set(DATA_SCALE, value)

    var yawOffset: Float
        get() = npc.entityData.get(DATA_YAW_OFFSET)
        set(value) = npc.entityData.set(DATA_YAW_OFFSET, value)

    var groundOffsetY: Float
        get() = npc.entityData.get(DATA_GROUND_OFFSET_Y)
        set(value) = npc.entityData.set(DATA_GROUND_OFFSET_Y, value)

    /** Whether the NPC wanders on its own (drives the Step5-2 goal set). */
    var wander: Boolean
        get() = npc.entityData.get(DATA_WANDER)
        set(value) = npc.entityData.set(DATA_WANDER, value)

    /** Whether the NPC follows its owner (drives the Step5-2 goal set). */
    var followOwner: Boolean
        get() = npc.entityData.get(DATA_FOLLOW_OWNER)
        set(value) = npc.entityData.set(DATA_FOLLOW_OWNER, value)

    /** Whether the NPC is sitting: holds the MOVE/JUMP goal flags so wander/follow cannot run (TLM/CNPC sit analogue). */
    var sit: Boolean
        get() = npc.entityData.get(DATA_SIT)
        set(value) = npc.entityData.set(DATA_SIT, value)

    /** Whether the NPC turns to look at nearby players (gates the look-at-player goal). */
    var lookPlayer: Boolean
        get() = npc.entityData.get(DATA_LOOK_PLAYER)
        set(value) = npc.entityData.set(DATA_LOOK_PLAYER, value)

    /**
     * What the NPC does when it comes across / is attacked by a player, mirroring CustomNPC `DataAI.onAttack`:
     * [RESPONSE_RETALIATE]/[RESPONSE_PANIC]/[RESPONSE_FLEE]/[RESPONSE_NOTHING]. Panicked/fled behavior is expressed
     * through `AvoidEntityGoal<Player>` (see [GlbNpcGoals]); retaliate is a placeholder until an attack goal lands.
     */
    var onAttack: Int
        get() = npc.entityData.get(DATA_ON_ATTACK)
        set(value) = npc.entityData.set(DATA_ON_ATTACK, value)

    /**
     * Per-instance max-health override, or [Float.NaN] to inherit the entity default. The attribute itself is
     * server-only and not readable on the client's entity copy, so this synced mirror is what the editor displays;
     * [GlbNpc.applyMaxHealth] is what actually pushes it onto the `MAX_HEALTH` attribute on the server.
     */
    var maxHealth: Float
        get() = npc.entityData.get(DATA_MAX_HEALTH)
        set(value) = npc.entityData.set(DATA_MAX_HEALTH, value)

    /**
     * Whether the NPC renders at full brightness (see-through-glow look). Drives [GlbNpcModels.draw] to pass
     * `FULL_BRIGHT` as the scene light instead of the world's packed light. Rendered client-side from this synced flag.
     */
    var glowing: Boolean
        get() = npc.entityData.get(DATA_GLOWING)
        set(value) = npc.entityData.set(DATA_GLOWING, value)

    /**
     * Per-instance vertex tint as a packed `0xAARRGGBB` int, multiplied component-wise into each material's base
     * color at draw time (see [GlbNpcModels.draw] -> `drawScene` -> `RgbaColor.times`). Defaults to [NO_TINT]
     * (`0xFFFFFFFF`, opaque white), whose multiply is the identity, so an untinted NPC renders byte-identical to before.
     */
    var tint: Int
        get() = npc.entityData.get(DATA_TINT)
        set(value) = npc.entityData.set(DATA_TINT, value)

    fun effectiveScale(descriptor: GlbModelDescriptor): Float = scale.takeUnless { it.isNaN() } ?: descriptor.scale
    fun effectiveYawOffset(descriptor: GlbModelDescriptor): Float =
        yawOffset.takeUnless { it.isNaN() } ?: descriptor.yawOffset

    fun effectiveGroundOffsetY(descriptor: GlbModelDescriptor): Float =
        groundOffsetY.takeUnless { it.isNaN() } ?: descriptor.groundOffsetY

    /** The health the attribute should carry: the override, else the vanilla-mob default. */
    fun effectiveMaxHealth(): Float = maxHealth.takeUnless { it.isNaN() } ?: DEFAULT_HEALTH

    fun save(tag: CompoundTag): CompoundTag {
        tag.putString(KEY_MODEL_ID, modelId)
        if (!scale.isNaN()) tag.putFloat(KEY_SCALE, scale)
        if (!yawOffset.isNaN()) tag.putFloat(KEY_YAW_OFFSET, yawOffset)
        if (!groundOffsetY.isNaN()) tag.putFloat(KEY_GROUND_OFFSET_Y, groundOffsetY)
        tag.putBoolean(KEY_WANDER, wander)
        tag.putBoolean(KEY_FOLLOW_OWNER, followOwner)
        tag.putBoolean(KEY_SIT, sit)
        tag.putBoolean(KEY_LOOK_PLAYER, lookPlayer)
        tag.putInt(KEY_ON_ATTACK, onAttack)
        if (!maxHealth.isNaN()) tag.putFloat(KEY_MAX_HEALTH, maxHealth)
        tag.putBoolean(KEY_GLOWING, glowing)
        tag.putInt(KEY_TINT, tint)
        return tag
    }

    fun load(tag: CompoundTag) {
        modelId = tag.getString(KEY_MODEL_ID)
        scale = if (tag.contains(KEY_SCALE)) tag.getFloat(KEY_SCALE) else Float.NaN
        yawOffset = if (tag.contains(KEY_YAW_OFFSET)) tag.getFloat(KEY_YAW_OFFSET) else Float.NaN
        groundOffsetY = if (tag.contains(KEY_GROUND_OFFSET_Y)) tag.getFloat(KEY_GROUND_OFFSET_Y) else Float.NaN
        if (tag.contains(KEY_WANDER)) wander = tag.getBoolean(KEY_WANDER)
        if (tag.contains(KEY_FOLLOW_OWNER)) followOwner = tag.getBoolean(KEY_FOLLOW_OWNER)
        if (tag.contains(KEY_SIT)) sit = tag.getBoolean(KEY_SIT)
        if (tag.contains(KEY_LOOK_PLAYER)) lookPlayer = tag.getBoolean(KEY_LOOK_PLAYER)
        if (tag.contains(KEY_ON_ATTACK)) onAttack = tag.getInt(KEY_ON_ATTACK)
        maxHealth = if (tag.contains(KEY_MAX_HEALTH)) tag.getFloat(KEY_MAX_HEALTH) else Float.NaN
        if (tag.contains(KEY_GLOWING)) glowing = tag.getBoolean(KEY_GLOWING)
        tint = if (tag.contains(KEY_TINT)) tag.getInt(KEY_TINT) else NO_TINT
    }

    companion object {
        private val DATA_MODEL_ID: EntityDataAccessor<String> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.STRING)
        private val DATA_SCALE: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.FLOAT)
        private val DATA_YAW_OFFSET: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.FLOAT)
        private val DATA_GROUND_OFFSET_Y: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.FLOAT)
        private val DATA_WANDER: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.BOOLEAN)
        private val DATA_FOLLOW_OWNER: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.BOOLEAN)
        private val DATA_SIT: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.BOOLEAN)
        private val DATA_LOOK_PLAYER: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.BOOLEAN)
        private val DATA_ON_ATTACK: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.INT)
        private val DATA_MAX_HEALTH: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.FLOAT)
        private val DATA_GLOWING: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.BOOLEAN)
        private val DATA_TINT: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.INT)

        private const val KEY_MODEL_ID = "GlbModelId"
        private const val KEY_SCALE = "GlbScale"
        private const val KEY_YAW_OFFSET = "GlbYawOffset"
        private const val KEY_GROUND_OFFSET_Y = "GlbGroundOffsetY"
        private const val KEY_WANDER = "GlbWander"
        private const val KEY_FOLLOW_OWNER = "GlbFollowOwner"
        private const val KEY_SIT = "GlbSit"
        private const val KEY_LOOK_PLAYER = "GlbLookPlayer"
        private const val KEY_ON_ATTACK = "GlbOnAttack"
        private const val KEY_MAX_HEALTH = "GlbMaxHealth"
        private const val KEY_GLOWING = "GlbGlowing"
        private const val KEY_TINT = "GlbTint"

        /** Opaque white packed `0xAARRGGBB`: the identity for the vertex-tint multiply (no tint). */
        const val NO_TINT = -0x1000000 // == 0xFFFFFFFF

        /** Fallback max health when no per-instance override is set (matches the vanilla mob default). */
        const val DEFAULT_HEALTH = 20.0f

        // onAttack response values (CustomNPC DataAI.onAttack vocabulary).
        const val RESPONSE_RETALIATE = 0
        const val RESPONSE_PANIC = 1
        const val RESPONSE_FLEE = 2
        const val RESPONSE_NOTHING = 3

        /** Static so it can run from the `Entity` superclass constructor, before a [GlbNpcConfig] instance exists. */
        fun defineSynchedData(builder: SynchedEntityData.Builder) {
            builder.define(DATA_MODEL_ID, "")
            builder.define(DATA_SCALE, Float.NaN)
            builder.define(DATA_YAW_OFFSET, Float.NaN)
            builder.define(DATA_GROUND_OFFSET_Y, Float.NaN)
            builder.define(DATA_WANDER, true)
            builder.define(DATA_FOLLOW_OWNER, false)
            builder.define(DATA_SIT, false)
            builder.define(DATA_LOOK_PLAYER, true)
            builder.define(DATA_ON_ATTACK, RESPONSE_NOTHING)
            builder.define(DATA_MAX_HEALTH, Float.NaN)
            builder.define(DATA_GLOWING, false)
            builder.define(DATA_TINT, NO_TINT)
        }
    }
}
