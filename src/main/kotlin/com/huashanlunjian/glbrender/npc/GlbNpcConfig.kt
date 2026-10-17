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

    fun effectiveScale(descriptor: GlbModelDescriptor): Float = scale.takeUnless { it.isNaN() } ?: descriptor.scale
    fun effectiveYawOffset(descriptor: GlbModelDescriptor): Float =
        yawOffset.takeUnless { it.isNaN() } ?: descriptor.yawOffset

    fun effectiveGroundOffsetY(descriptor: GlbModelDescriptor): Float =
        groundOffsetY.takeUnless { it.isNaN() } ?: descriptor.groundOffsetY

    fun save(tag: CompoundTag): CompoundTag {
        tag.putString(KEY_MODEL_ID, modelId)
        if (!scale.isNaN()) tag.putFloat(KEY_SCALE, scale)
        if (!yawOffset.isNaN()) tag.putFloat(KEY_YAW_OFFSET, yawOffset)
        if (!groundOffsetY.isNaN()) tag.putFloat(KEY_GROUND_OFFSET_Y, groundOffsetY)
        tag.putBoolean(KEY_WANDER, wander)
        tag.putBoolean(KEY_FOLLOW_OWNER, followOwner)
        return tag
    }

    fun load(tag: CompoundTag) {
        modelId = tag.getString(KEY_MODEL_ID)
        scale = if (tag.contains(KEY_SCALE)) tag.getFloat(KEY_SCALE) else Float.NaN
        yawOffset = if (tag.contains(KEY_YAW_OFFSET)) tag.getFloat(KEY_YAW_OFFSET) else Float.NaN
        groundOffsetY = if (tag.contains(KEY_GROUND_OFFSET_Y)) tag.getFloat(KEY_GROUND_OFFSET_Y) else Float.NaN
        if (tag.contains(KEY_WANDER)) wander = tag.getBoolean(KEY_WANDER)
        if (tag.contains(KEY_FOLLOW_OWNER)) followOwner = tag.getBoolean(KEY_FOLLOW_OWNER)
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

        private const val KEY_MODEL_ID = "GlbModelId"
        private const val KEY_SCALE = "GlbScale"
        private const val KEY_YAW_OFFSET = "GlbYawOffset"
        private const val KEY_GROUND_OFFSET_Y = "GlbGroundOffsetY"
        private const val KEY_WANDER = "GlbWander"
        private const val KEY_FOLLOW_OWNER = "GlbFollowOwner"

        /** Static so it can run from the `Entity` superclass constructor, before a [GlbNpcConfig] instance exists. */
        fun defineSynchedData(builder: SynchedEntityData.Builder) {
            builder.define(DATA_MODEL_ID, "")
            builder.define(DATA_SCALE, Float.NaN)
            builder.define(DATA_YAW_OFFSET, Float.NaN)
            builder.define(DATA_GROUND_OFFSET_Y, Float.NaN)
            builder.define(DATA_WANDER, true)
            builder.define(DATA_FOLLOW_OWNER, false)
        }
    }
}
