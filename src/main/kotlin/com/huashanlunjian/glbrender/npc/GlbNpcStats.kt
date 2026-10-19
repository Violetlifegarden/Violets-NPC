package com.huashanlunjian.glbrender.npc

import net.minecraft.core.Holder
import net.minecraft.nbt.CompoundTag
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity

/**
 * The NPC's Stats tab as one value object, mirroring CustomNPC's `DataStats` + `Resistances` + `DataMelee` +
 * `DataRanged` field set (same defaults, same units). It is a plain mutable holder: the editor mutates a local copy
 * and commits it in one [SetStatsPacket], the server stores the canonical copy in [GlbNpcConfig.stats] (synced through
 * a single `COMPOUND_TAG` accessor), and the entity reads it from gameplay hooks each tick.
 *
 * The melee/ranged values are adapted to vanilla mechanics rather than copied: melee strength/knockback drive the
 * `ATTACK_DAMAGE` / `ATTACK_KNOCKBACK` attributes, melee delay/range drive our goal's own cooldown/reach, and ranged
 * shots are vanilla arrows (CNPC's custom projectile entity is out of scope). `attackType` is our stand-in for CNPC's
 * projectile-in-inventory gate, since we do not have CNPC's projectile item slot yet.
 */
data class GlbNpcStats(
    /** Target-acquisition range in blocks (CNPC `aggroRange`); pushed onto the `FOLLOW_RANGE` attribute. */
    var aggroRange: Int = 16,
    /** Seconds the corpse stays down before a respawn allowed by [spawnCycle]. */
    var respawnTime: Int = 20,
    /** [SPAWN_YES] / [SPAWN_DAY] / [SPAWN_NIGHT] / [SPAWN_NO] / [SPAWN_NATURALLY]. */
    var spawnCycle: Int = SPAWN_YES,
    /** Hide the model while dead (CNPC `hideKilledBody`). */
    var hideKilledBody: Boolean = false,
    /** Damage multiplier = `2 - resistance`: 1.0 = unchanged, 2.0 = immune, 0.0 = double damage. */
    var resistKnockback: Float = 1.0f,
    var resistArrow: Float = 1.0f,
    var resistMelee: Float = 1.0f,
    var resistExplosion: Float = 1.0f,
    var immuneToFire: Boolean = false,
    var potionImmune: Boolean = false,
    var canDrown: Boolean = true,
    var burnInSun: Boolean = false,
    var noFallDamage: Boolean = false,
    var ignoreCobweb: Boolean = false,
    /** Health restored every 20 ticks while out of combat (CNPC `healthRegen`); 0 disables. */
    var healthRegen: Int = 1,
    /** Health restored every 20 ticks while attacking (CNPC `combatRegen`); 0 disables. */
    var combatRegen: Int = 0,
    /** [CREATURE_NORMAL] / [CREATURE_UNDEAD] / [CREATURE_ARTHROPOD]. */
    var creatureType: Int = CREATURE_NORMAL,
    /** [ATTACK_MELEE] / [ATTACK_RANGED] — which attack goal the NPC uses once it has a target. */
    var attackType: Int = ATTACK_MELEE,
    var meleeStrength: Int = 5,
    /** Ticks between melee hits (CNPC `attackSpeed`), 20 = once per second. */
    var meleeSpeed: Int = 20,
    var meleeRange: Int = 2,
    /** 0..4, added knockback on each hit (CNPC melee knockback). */
    var meleeKnockback: Int = 0,
    /** Index into [MELEE_EFFECT_NAMES]; 0 = no on-hit effect. */
    var meleeEffect: Int = 0,
    /** Seconds the on-hit effect lasts. */
    var meleeEffectTime: Int = 5,
    var meleeEffectAmplifier: Int = 0,
    var rangedAccuracy: Int = 60,
    var rangedShotCount: Int = 1,
    var rangedRange: Int = 15,
    /** Do not shoot when the target is closer than this many blocks (CNPC `meleeDistance`); 0 disables. */
    var rangedMeleeDistance: Int = 0,
    var rangedMinDelay: Int = 20,
    var rangedMaxDelay: Int = 40,
    var rangedBurstCount: Int = 1,
    /** Ticks between burst volleys (CNPC `burstDelay`). */
    var rangedBurstDelay: Int = 5,
    /** Keep aiming while shooting (CNPC `aimWhileShooting`); the vanilla look control is always used while attacking. */
    var rangedAimWhileShooting: Boolean = false,
    /** [RANGED_INDIRECT_NO] / [RANGED_INDIRECT_DISTANT] / [RANGED_INDIRECT_HIDDEN]. */
    var rangedIndirect: Int = RANGED_INDIRECT_NO,
    var rangedDamage: Int = 4,
    /** Arrow velocity in 0.1 blocks/tick units (CNPC `pSpeed`, 10 = velocity 1.0). */
    var rangedSpeed: Int = 10,
    /** Index into [MELEE_EFFECT_NAMES]; 0 = no on-hit effect (CNPC ranged effect, not exposed in the Stats UI yet). */
    var rangedEffect: Int = 0,
    var rangedEffectTime: Int = 5,
    var rangedEffectAmplifier: Int = 0,
) {
    fun copyStats(): GlbNpcStats = copy()

    fun toTag(): CompoundTag = CompoundTag().also { tag ->
        tag.putInt(KEY_AGGRO, aggroRange)
        tag.putInt(KEY_RESPAWN_TIME, respawnTime)
        tag.putInt(KEY_SPAWN_CYCLE, spawnCycle)
        tag.putBoolean(KEY_HIDE_BODY, hideKilledBody)
        tag.putFloat(KEY_RESIST_KNOCKBACK, resistKnockback)
        tag.putFloat(KEY_RESIST_ARROW, resistArrow)
        tag.putFloat(KEY_RESIST_MELEE, resistMelee)
        tag.putFloat(KEY_RESIST_EXPLOSION, resistExplosion)
        tag.putBoolean(KEY_FIRE_IMMUNE, immuneToFire)
        tag.putBoolean(KEY_POTION_IMMUNE, potionImmune)
        tag.putBoolean(KEY_CAN_DROWN, canDrown)
        tag.putBoolean(KEY_BURN_IN_SUN, burnInSun)
        tag.putBoolean(KEY_NO_FALL_DAMAGE, noFallDamage)
        tag.putBoolean(KEY_IGNORE_COBWEB, ignoreCobweb)
        tag.putInt(KEY_HEALTH_REGEN, healthRegen)
        tag.putInt(KEY_COMBAT_REGEN, combatRegen)
        tag.putInt(KEY_CREATURE_TYPE, creatureType)
        tag.putInt(KEY_ATTACK_TYPE, attackType)
        tag.putInt(KEY_MELEE_STRENGTH, meleeStrength)
        tag.putInt(KEY_MELEE_SPEED, meleeSpeed)
        tag.putInt(KEY_MELEE_RANGE, meleeRange)
        tag.putInt(KEY_MELEE_KNOCKBACK, meleeKnockback)
        tag.putInt(KEY_MELEE_EFFECT, meleeEffect)
        tag.putInt(KEY_MELEE_EFFECT_TIME, meleeEffectTime)
        tag.putInt(KEY_MELEE_EFFECT_AMP, meleeEffectAmplifier)
        tag.putInt(KEY_RANGED_ACCURACY, rangedAccuracy)
        tag.putInt(KEY_RANGED_SHOT_COUNT, rangedShotCount)
        tag.putInt(KEY_RANGED_RANGE, rangedRange)
        tag.putInt(KEY_RANGED_MELEE_DISTANCE, rangedMeleeDistance)
        tag.putInt(KEY_RANGED_MIN_DELAY, rangedMinDelay)
        tag.putInt(KEY_RANGED_MAX_DELAY, rangedMaxDelay)
        tag.putInt(KEY_RANGED_BURST, rangedBurstCount)
        tag.putInt(KEY_RANGED_BURST_DELAY, rangedBurstDelay)
        tag.putBoolean(KEY_RANGED_AIM, rangedAimWhileShooting)
        tag.putInt(KEY_RANGED_INDIRECT, rangedIndirect)
        tag.putInt(KEY_RANGED_DAMAGE, rangedDamage)
        tag.putInt(KEY_RANGED_SPEED, rangedSpeed)
        tag.putInt(KEY_RANGED_EFFECT, rangedEffect)
        tag.putInt(KEY_RANGED_EFFECT_TIME, rangedEffectTime)
        tag.putInt(KEY_RANGED_EFFECT_AMP, rangedEffectAmplifier)
    }

    /** Server-side clamp applied to every packet-carried copy, so a bad client cannot write out-of-range values. */
    fun coerced(): GlbNpcStats {
        val minDelay = rangedMinDelay.coerceIn(1, MAX_RANGED_DELAY)
        return copy(
            aggroRange = aggroRange.coerceIn(0, 128),
            respawnTime = respawnTime.coerceIn(0, MAX_RESPAWN_TIME),
            spawnCycle = spawnCycle.coerceIn(SPAWN_YES, SPAWN_NATURALLY),
            resistKnockback = resistKnockback.coerceIn(0f, 2f),
            resistArrow = resistArrow.coerceIn(0f, 2f),
            resistMelee = resistMelee.coerceIn(0f, 2f),
            resistExplosion = resistExplosion.coerceIn(0f, 2f),
            healthRegen = healthRegen.coerceIn(0, MAX_REGEN),
            combatRegen = combatRegen.coerceIn(0, MAX_REGEN),
            creatureType = creatureType.coerceIn(CREATURE_NORMAL, CREATURE_ARTHROPOD),
            attackType = attackType.coerceIn(ATTACK_MELEE, ATTACK_RANGED),
            meleeStrength = meleeStrength.coerceIn(0, MAX_MELEE),
            meleeSpeed = meleeSpeed.coerceIn(1, MAX_MELEE_SPEED),
            meleeRange = meleeRange.coerceIn(1, MAX_MELEE_RANGE),
            meleeKnockback = meleeKnockback.coerceIn(0, MAX_MELEE_KNOCKBACK),
            meleeEffect = meleeEffect.coerceIn(0, MELEE_EFFECT_NAMES.lastIndex),
            meleeEffectTime = meleeEffectTime.coerceIn(1, MAX_EFFECT_TIME),
            meleeEffectAmplifier = meleeEffectAmplifier.coerceIn(0, MAX_EFFECT_AMPLIFIER),
            rangedAccuracy = rangedAccuracy.coerceIn(0, 100),
            rangedShotCount = rangedShotCount.coerceIn(1, MAX_RANGED_SHOTS),
            rangedRange = rangedRange.coerceIn(1, MAX_RANGED_RANGE),
            rangedMeleeDistance = rangedMeleeDistance.coerceIn(0, MAX_RANGED_RANGE),
            rangedMinDelay = minDelay,
            rangedMaxDelay = rangedMaxDelay.coerceIn(1, MAX_RANGED_DELAY).coerceAtLeast(minDelay),
            rangedBurstCount = rangedBurstCount.coerceIn(1, MAX_RANGED_SHOTS),
            rangedBurstDelay = rangedBurstDelay.coerceIn(1, MAX_RANGED_DELAY),
            rangedIndirect = rangedIndirect.coerceIn(RANGED_INDIRECT_NO, RANGED_INDIRECT_HIDDEN),
            rangedDamage = rangedDamage.coerceIn(0, MAX_MELEE),
            rangedSpeed = rangedSpeed.coerceIn(1, MAX_RANGED_SPEED),
            rangedEffect = rangedEffect.coerceIn(0, MELEE_EFFECT_NAMES.lastIndex),
            rangedEffectTime = rangedEffectTime.coerceIn(1, MAX_EFFECT_TIME),
            rangedEffectAmplifier = rangedEffectAmplifier.coerceIn(0, MAX_EFFECT_AMPLIFIER),
        )
    }

    /** The configured on-hit effect for melee, or null for "none". */
    fun meleeEffectHolder(): Holder<MobEffect>? = MELEE_EFFECTS.getOrNull(meleeEffect)

    fun rangedEffectHolder(): Holder<MobEffect>? = MELEE_EFFECTS.getOrNull(rangedEffect)

    /**
     * Damage multiplier for an incoming hit, CNPC `Resistances.applyResistance`: `2 - resistance`, with the same order
     * as CNPC (projectile → explosion → living attacker melee). Anything else is unmodified (1.0).
     */
    fun resistanceMultiplier(source: DamageSource): Float = when {
        source.`is`(DamageTypeTags.IS_PROJECTILE) -> 2f - resistArrow
        source.`is`(DamageTypeTags.IS_EXPLOSION) -> 2f - resistExplosion
        source.entity is LivingEntity -> 2f - resistMelee
        else -> 1f
    }

    companion object {
        // Health itself stays on GlbNpcConfig.maxHealth; these are the remaining Stats-tab fields.
        const val SPAWN_YES = 0
        const val SPAWN_DAY = 1
        const val SPAWN_NIGHT = 2
        const val SPAWN_NO = 3
        const val SPAWN_NATURALLY = 4

        const val CREATURE_NORMAL = 0
        const val CREATURE_UNDEAD = 1
        const val CREATURE_ARTHROPOD = 2

        const val ATTACK_MELEE = 0
        const val ATTACK_RANGED = 1

        const val RANGED_INDIRECT_NO = 0
        const val RANGED_INDIRECT_DISTANT = 1
        const val RANGED_INDIRECT_HIDDEN = 2

        const val MAX_RESPAWN_TIME = 86400
        const val MAX_REGEN = 1000
        const val MAX_MELEE = 100
        const val MAX_MELEE_SPEED = 1000
        const val MAX_MELEE_RANGE = 30
        const val MAX_MELEE_KNOCKBACK = 4
        const val MAX_EFFECT_TIME = 99999
        const val MAX_EFFECT_AMPLIFIER = 10
        const val MAX_RANGED_SHOTS = 100
        const val MAX_RANGED_RANGE = 64
        const val MAX_RANGED_DELAY = 1000
        const val MAX_RANGED_SPEED = 100

        val SPAWN_CYCLE_OPTIONS = arrayOf("Yes", "Day", "Night", "No", "Naturally")
        val CREATURE_TYPE_OPTIONS = arrayOf("Normal", "Undead", "Arthropod")
        val ATTACK_TYPE_OPTIONS = arrayOf("Melee", "Ranged")
        val RANGED_INDIRECT_OPTIONS = arrayOf("No", "When distant", "When hidden")

        /** Display names in the same index order as [MELEE_EFFECTS]. */
        val MELEE_EFFECT_NAMES = arrayOf(
            "None", "Poison", "Slowness", "Weakness", "Wither", "Hunger", "Blindness", "Nausea", "Instant damage",
        )

        private val MELEE_EFFECTS: List<Holder<MobEffect>?> = listOf(
            null,
            MobEffects.POISON,
            MobEffects.MOVEMENT_SLOWDOWN,
            MobEffects.WEAKNESS,
            MobEffects.WITHER,
            MobEffects.HUNGER,
            MobEffects.BLINDNESS,
            MobEffects.CONFUSION,
            MobEffects.HARM,
        )

        /**
         * Parses a stats compound. Missing keys fall back to the CNPC defaults rather than zero (an empty or older
         * compound must not silently disable drowning/regen), by merging the incoming values over a default tag.
         */
        fun fromTag(tag: CompoundTag): GlbNpcStats {
            val merged = GlbNpcStats().toTag().also { it.merge(tag) }
            return GlbNpcStats(
                aggroRange = merged.getInt(KEY_AGGRO),
                respawnTime = merged.getInt(KEY_RESPAWN_TIME),
                spawnCycle = merged.getInt(KEY_SPAWN_CYCLE),
                hideKilledBody = merged.getBoolean(KEY_HIDE_BODY),
                resistKnockback = merged.getFloat(KEY_RESIST_KNOCKBACK),
                resistArrow = merged.getFloat(KEY_RESIST_ARROW),
                resistMelee = merged.getFloat(KEY_RESIST_MELEE),
                resistExplosion = merged.getFloat(KEY_RESIST_EXPLOSION),
                immuneToFire = merged.getBoolean(KEY_FIRE_IMMUNE),
                potionImmune = merged.getBoolean(KEY_POTION_IMMUNE),
                canDrown = merged.getBoolean(KEY_CAN_DROWN),
                burnInSun = merged.getBoolean(KEY_BURN_IN_SUN),
                noFallDamage = merged.getBoolean(KEY_NO_FALL_DAMAGE),
                ignoreCobweb = merged.getBoolean(KEY_IGNORE_COBWEB),
                healthRegen = merged.getInt(KEY_HEALTH_REGEN),
                combatRegen = merged.getInt(KEY_COMBAT_REGEN),
                creatureType = merged.getInt(KEY_CREATURE_TYPE),
                attackType = merged.getInt(KEY_ATTACK_TYPE),
                meleeStrength = merged.getInt(KEY_MELEE_STRENGTH),
                meleeSpeed = merged.getInt(KEY_MELEE_SPEED),
                meleeRange = merged.getInt(KEY_MELEE_RANGE),
                meleeKnockback = merged.getInt(KEY_MELEE_KNOCKBACK),
                meleeEffect = merged.getInt(KEY_MELEE_EFFECT),
                meleeEffectTime = merged.getInt(KEY_MELEE_EFFECT_TIME),
                meleeEffectAmplifier = merged.getInt(KEY_MELEE_EFFECT_AMP),
                rangedAccuracy = merged.getInt(KEY_RANGED_ACCURACY),
                rangedShotCount = merged.getInt(KEY_RANGED_SHOT_COUNT),
                rangedRange = merged.getInt(KEY_RANGED_RANGE),
                rangedMeleeDistance = merged.getInt(KEY_RANGED_MELEE_DISTANCE),
                rangedMinDelay = merged.getInt(KEY_RANGED_MIN_DELAY),
                rangedMaxDelay = merged.getInt(KEY_RANGED_MAX_DELAY),
                rangedBurstCount = merged.getInt(KEY_RANGED_BURST),
                rangedBurstDelay = merged.getInt(KEY_RANGED_BURST_DELAY),
                rangedAimWhileShooting = merged.getBoolean(KEY_RANGED_AIM),
                rangedIndirect = merged.getInt(KEY_RANGED_INDIRECT),
                rangedDamage = merged.getInt(KEY_RANGED_DAMAGE),
                rangedSpeed = merged.getInt(KEY_RANGED_SPEED),
                rangedEffect = merged.getInt(KEY_RANGED_EFFECT),
                rangedEffectTime = merged.getInt(KEY_RANGED_EFFECT_TIME),
                rangedEffectAmplifier = merged.getInt(KEY_RANGED_EFFECT_AMP),
            )
        }

        private const val KEY_AGGRO = "Aggro"
        private const val KEY_RESPAWN_TIME = "RespawnTime"
        private const val KEY_SPAWN_CYCLE = "SpawnCycle"
        private const val KEY_HIDE_BODY = "HideBody"
        private const val KEY_RESIST_KNOCKBACK = "ResistKnockback"
        private const val KEY_RESIST_ARROW = "ResistArrow"
        private const val KEY_RESIST_MELEE = "ResistMelee"
        private const val KEY_RESIST_EXPLOSION = "ResistExplosion"
        private const val KEY_FIRE_IMMUNE = "FireImmune"
        private const val KEY_POTION_IMMUNE = "PotionImmune"
        private const val KEY_CAN_DROWN = "CanDrown"
        private const val KEY_BURN_IN_SUN = "BurnInSun"
        private const val KEY_NO_FALL_DAMAGE = "NoFallDamage"
        private const val KEY_IGNORE_COBWEB = "IgnoreCobweb"
        private const val KEY_HEALTH_REGEN = "HealthRegen"
        private const val KEY_COMBAT_REGEN = "CombatRegen"
        private const val KEY_CREATURE_TYPE = "CreatureType"
        private const val KEY_ATTACK_TYPE = "AttackType"
        private const val KEY_MELEE_STRENGTH = "MeleeStrength"
        private const val KEY_MELEE_SPEED = "MeleeSpeed"
        private const val KEY_MELEE_RANGE = "MeleeRange"
        private const val KEY_MELEE_KNOCKBACK = "MeleeKnockback"
        private const val KEY_MELEE_EFFECT = "MeleeEffect"
        private const val KEY_MELEE_EFFECT_TIME = "MeleeEffectTime"
        private const val KEY_MELEE_EFFECT_AMP = "MeleeEffectAmp"
        private const val KEY_RANGED_ACCURACY = "RangedAccuracy"
        private const val KEY_RANGED_SHOT_COUNT = "RangedShotCount"
        private const val KEY_RANGED_RANGE = "RangedRange"
        private const val KEY_RANGED_MELEE_DISTANCE = "RangedMeleeDistance"
        private const val KEY_RANGED_MIN_DELAY = "RangedMinDelay"
        private const val KEY_RANGED_MAX_DELAY = "RangedMaxDelay"
        private const val KEY_RANGED_BURST = "RangedBurst"
        private const val KEY_RANGED_BURST_DELAY = "RangedBurstDelay"
        private const val KEY_RANGED_AIM = "RangedAim"
        private const val KEY_RANGED_INDIRECT = "RangedIndirect"
        private const val KEY_RANGED_DAMAGE = "RangedDamage"
        private const val KEY_RANGED_SPEED = "RangedSpeed"
        private const val KEY_RANGED_EFFECT = "RangedEffect"
        private const val KEY_RANGED_EFFECT_TIME = "RangedEffectTime"
        private const val KEY_RANGED_EFFECT_AMP = "RangedEffectAmp"
    }
}
