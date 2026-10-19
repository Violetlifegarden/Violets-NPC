package com.huashanlunjian.glbrender.npc

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import java.util.Locale

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
 *
 * The accessors must be *registered* (their [SynchedEntityData.defineId] calls run) before the first entity is
 * constructed: `SynchedEntityData.Builder` sizes its id array from the registry at construction, so registering from
 * inside `defineSynchedData` is too late and indexes past the array. [GlbNpc]'s companion init calls
 * [ensureAccessorsRegistered] during class initialization, which the JVM performs before any constructor runs.
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
     * The NPC's profession/job, mirroring CustomNPC's `DataAdvanced.NpcJob`: [PROFESSION_NONE] is a plain idle mob,
     * [PROFESSION_GUARD] makes it proactively hunt and melee hostile mobs (see the guard goals in [GlbNpcGoals]). The
     * value is read only inside goal `canUse` (never in a goal constructor, since `Mob`'s constructor registers goals
     * before this config exists), so switching it at runtime re-gates behavior on the next tick with no teardown.
     */
    var profession: Int
        get() = npc.entityData.get(DATA_PROFESSION)
        set(value) = npc.entityData.set(DATA_PROFESSION, value)

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

    /**
     * Raw dialog lines, one `|`-separated string (the editor's single field + the synced value). Split into
     * [dialogLines] for display. Empty means the NPC has nothing to say, so a plain right-click does not open a dialog.
     */
    var dialogs: String
        get() = npc.entityData.get(DATA_DIALOGS)
        set(value) = npc.entityData.set(DATA_DIALOGS, value)

    /** The dialog lines in talk order; the [GlbNpc.mobInteract] talk path walks these in the dialog screen. */
    var dialogLines: List<String>
        get() = dialogs.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) {
            dialogs = value.joinToString("|")
        }

    /**
     * Editor animation preview: a clip name to force-play regardless of the locomotion state machine. Empty means
     * "auto" (the normal [GlbNpcAnimator] priority table). A name the model does not carry falls back to auto, so a
     * stale value after a model switch degrades gracefully. Synced like the other fields; render-only (no behavior).
     */
    var clip: String
        get() = npc.entityData.get(DATA_CLIP)
        set(value) = npc.entityData.set(DATA_CLIP, value)

    /**
     * Shading model for PBR-authored primitives: [MATERIAL_PBR] (metallic-roughness, the default),
     * [MATERIAL_VANILLA] (base color × lightmap) or [MATERIAL_UNLIT] (pre-lighting flat look). Read per frame by
     * [GlbNpcModels.draw], so it switches without a reload. Assets without a PBR material ignore it.
     */
    var materialMode: Int
        get() = npc.entityData.get(DATA_MATERIAL_MODE)
        set(value) = npc.entityData.set(DATA_MATERIAL_MODE, value.coerceIn(MATERIAL_PBR, MATERIAL_UNLIT))

    /**
     * Per-loot-slot death-drop percentages, one `|`-separated value each in `0..100` (CustomNPC `DataInventory`
     * `dropchance`, default 100). The setter canonicalizes the string (see [normalizeDropChances]) so a synced value
     * always has exactly [GlbNpcInventory.LOOT_SLOTS] entries and every consumer can index it blindly.
     */
    var dropChances: String
        get() = npc.entityData.get(DATA_DROP_CHANCES)
        set(value) = npc.entityData.set(DATA_DROP_CHANCES, normalizeDropChances(value))

    /** The death-drop percentage for loot slot [index], already normalized to `0..100`. */
    fun dropChance(index: Int): Float = dropChanceOf(dropChances, index)

    fun setDropChance(index: Int, value: Float) {
        dropChances = withDropChance(dropChances, index, value)
    }

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
        tag.putInt(KEY_PROFESSION, profession)
        tag.putInt(KEY_ON_ATTACK, onAttack)
        if (!maxHealth.isNaN()) tag.putFloat(KEY_MAX_HEALTH, maxHealth)
        tag.putBoolean(KEY_GLOWING, glowing)
        tag.putInt(KEY_TINT, tint)
        tag.putString(KEY_DIALOGS, dialogs)
        tag.putString(KEY_CLIP, clip)
        tag.putInt(KEY_MATERIAL_MODE, materialMode)
        tag.putString(KEY_DROP_CHANCES, dropChances)
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
        if (tag.contains(KEY_PROFESSION)) profession = tag.getInt(KEY_PROFESSION)
        if (tag.contains(KEY_ON_ATTACK)) onAttack = tag.getInt(KEY_ON_ATTACK)
        maxHealth = if (tag.contains(KEY_MAX_HEALTH)) tag.getFloat(KEY_MAX_HEALTH) else Float.NaN
        if (tag.contains(KEY_GLOWING)) glowing = tag.getBoolean(KEY_GLOWING)
        tint = if (tag.contains(KEY_TINT)) tag.getInt(KEY_TINT) else NO_TINT
        if (tag.contains(KEY_DIALOGS)) dialogs = tag.getString(KEY_DIALOGS)
        if (tag.contains(KEY_CLIP)) clip = tag.getString(KEY_CLIP)
        materialMode = when {
            tag.contains(KEY_MATERIAL_MODE) -> tag.getInt(KEY_MATERIAL_MODE)
            // Older saves only carried the PBR on/off flag: on was PBR, off was the vanilla-lit path.
            tag.contains(KEY_PBR_LEGACY) ->
                if (tag.getBoolean(KEY_PBR_LEGACY)) MATERIAL_PBR else MATERIAL_VANILLA
            else -> MATERIAL_PBR
        }
        dropChances = if (tag.contains(KEY_DROP_CHANCES)) tag.getString(KEY_DROP_CHANCES) else DEFAULT_DROP_CHANCES
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
        private val DATA_PROFESSION: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.INT)
        private val DATA_ON_ATTACK: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.INT)
        private val DATA_MAX_HEALTH: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.FLOAT)
        private val DATA_GLOWING: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.BOOLEAN)
        private val DATA_TINT: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.INT)
        private val DATA_DIALOGS: EntityDataAccessor<String> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.STRING)
        private val DATA_CLIP: EntityDataAccessor<String> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.STRING)
        private val DATA_MATERIAL_MODE: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.INT)
        private val DATA_DROP_CHANCES: EntityDataAccessor<String> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.STRING)

        private const val KEY_MODEL_ID = "GlbModelId"
        private const val KEY_SCALE = "GlbScale"
        private const val KEY_YAW_OFFSET = "GlbYawOffset"
        private const val KEY_GROUND_OFFSET_Y = "GlbGroundOffsetY"
        private const val KEY_WANDER = "GlbWander"
        private const val KEY_FOLLOW_OWNER = "GlbFollowOwner"
        private const val KEY_SIT = "GlbSit"
        private const val KEY_LOOK_PLAYER = "GlbLookPlayer"
        private const val KEY_PROFESSION = "GlbProfession"
        private const val KEY_ON_ATTACK = "GlbOnAttack"
        private const val KEY_MAX_HEALTH = "GlbMaxHealth"
        private const val KEY_GLOWING = "GlbGlowing"
        private const val KEY_TINT = "GlbTint"
        private const val KEY_DIALOGS = "GlbDialogs"
        private const val KEY_CLIP = "GlbClip"
        private const val KEY_MATERIAL_MODE = "GlbMaterialMode"
        private const val KEY_DROP_CHANCES = "GlbDropChances"
        /** Read-only migration key from when this field was a Boolean. */
        private const val KEY_PBR_LEGACY = "GlbPbr"

        /** Opaque white packed `0xAARRGGBB`: the identity for the vertex-tint multiply (no tint). */
        const val NO_TINT = -1 // 0xFFFFFFFF as a signed Int; NOT -0x1000000 (that is 0xFF000000 = opaque black)

        /** Fallback max health when no per-instance override is set (matches the vanilla mob default). */
        const val DEFAULT_HEALTH = 20.0f

        // onAttack response values (CustomNPC DataAI.onAttack vocabulary).
        const val RESPONSE_RETALIATE = 0
        const val RESPONSE_PANIC = 1
        const val RESPONSE_FLEE = 2
        const val RESPONSE_NOTHING = 3

        // profession values (CustomNPC NpcJob vocabulary, trimmed to the professions we implement).
        const val PROFESSION_NONE = 0
        const val PROFESSION_GUARD = 1

        // Shading modes; GlbNpcModels maps these onto the render api's MaterialMode.
        const val MATERIAL_PBR = 0
        const val MATERIAL_VANILLA = 1
        const val MATERIAL_UNLIT = 2

        /** CustomNPC's drop-chance default and upper bound: a filled loot slot drops on every death. */
        const val DEFAULT_DROP_CHANCE = 100.0f

        /** Canonical all-default drop-chance string (one entry per loot slot). */
        val DEFAULT_DROP_CHANCES: String =
            (0 until GlbNpcInventory.LOOT_SLOTS).joinToString("|") { formatChance(DEFAULT_DROP_CHANCE) }

        /**
         * Canonicalizes a raw `|`-separated drop-chance string: pads/truncates to [GlbNpcInventory.LOOT_SLOTS] entries,
         * clamps each to `0..100`, and formats with one decimal. Used by the synced setter so every reader (death drops,
         * editor) sees the same shape.
         */
        fun normalizeDropChances(raw: String): String =
            parseDropChances(raw).joinToString("|") { formatChance(it) }

        /**
         * Replaces one loot slot's percentage in a raw drop-chance string, returning the canonical string. [index]
         * outside the loot range leaves the string canonicalized but otherwise unchanged.
         */
        fun withDropChance(raw: String, index: Int, value: Float): String {
            val values = parseDropChances(raw).toMutableList()
            if (index in values.indices) values[index] = normalizeChance(value)
            return values.joinToString("|") { formatChance(it) }
        }

        private fun parseDropChances(raw: String): List<Float> {
            val parts = raw.split('|')
            return (0 until GlbNpcInventory.LOOT_SLOTS).map { index ->
                normalizeChance(parts.getOrNull(index)?.trim()?.toFloatOrNull() ?: DEFAULT_DROP_CHANCE)
            }
        }

        private fun dropChanceOf(raw: String, index: Int): Float = parseDropChances(raw)[index]

        private fun normalizeChance(value: Float): Float =
            if (value.isNaN()) DEFAULT_DROP_CHANCE else value.coerceIn(0.0f, 100.0f)

        private fun formatChance(value: Float): String = "%.1f".format(Locale.ROOT, value)

        /**
         * Forces this companion to initialize, running the [SynchedEntityData.defineId] registrations above. Called
         * from [GlbNpc]'s companion init; see this class's KDoc for why the timing matters.
         */
        fun ensureAccessorsRegistered() {}

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
            builder.define(DATA_PROFESSION, PROFESSION_NONE)
            builder.define(DATA_ON_ATTACK, RESPONSE_NOTHING)
            builder.define(DATA_MAX_HEALTH, Float.NaN)
            builder.define(DATA_GLOWING, false)
            builder.define(DATA_TINT, NO_TINT)
            builder.define(DATA_DIALOGS, "")
            builder.define(DATA_CLIP, "")
            builder.define(DATA_MATERIAL_MODE, MATERIAL_PBR)
            builder.define(DATA_DROP_CHANCES, DEFAULT_DROP_CHANCES)
        }
    }
}
