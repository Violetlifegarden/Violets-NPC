package com.huashanlunjian.glbrender.npc

import com.huashanlunjian.glbrender.npc.net.GlbNpcEditing
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Mth
import net.minecraft.world.ContainerHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.world.SimpleContainer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.RangedAttackMob
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.AbstractArrow
import net.minecraft.world.entity.projectile.Arrow
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.phys.Vec3

/**
 * The NPC: a plain [PathfinderMob] (LivingEntity branch, never a fake Player) that the glbrender
 * [com.huashanlunjian.glbrender.client.npc.GlbNpcRenderer] draws as a GLB model.
 *
 * All per-instance state (model id, transform overrides, behavior flags) lives in [config], which owns the synced
 * data, NBT persistence, and typed accessors. [modelId] is a convenience delegate kept for the renderer/interaction
 * callers. Sneak right-clicking opens the CustomNPC-style editor menu ([GlbNpcMenu]); a plain right-click makes the
 * NPC talk through its dialog lines (see [GlbNpcClientHooks.openDialog]).
 */
class GlbNpc(type: EntityType<out PathfinderMob>, level: Level) : PathfinderMob(type, level), RangedAttackMob {

    /**
     * Per-instance config: synced data + NBT + typed getters/setters. Backed by a nullable field because Kotlin runs
     * this initializer after the superclass constructor, which already calls [registerGoals]; a few vanilla hooks
     * (e.g. [fireImmune]) can be queried very early, so the accessors below fall back to defaults until it exists.
     */
    private var configOrNull: GlbNpcConfig? = null

    val config: GlbNpcConfig
        get() = configOrNull ?: error("GlbNpc config accessed before initialization")

    private val configReady: Boolean
        get() = configOrNull != null

    init {
        configOrNull = GlbNpcConfig(this)
    }

    /**
     * Projectile + nine "NPC Inventory" drop slots, in [GlbNpcInventory]'s storage order. The hands and armor are not
     * here: they live in the vanilla equipment slots (see [GlbNpcEquipmentContainer]). The contents persist in NBT and
     * are synced through the editor menu's slots while it is open.
     */
    val storage = SimpleContainer(GlbNpcInventory.STORAGE_SLOTS)

    /** The model id this NPC renders as; empty falls back to [GlbModels.defaultId] on the client. */
    var modelId: String
        get() = config.modelId
        set(value) {
            config.modelId = value
        }

    /** Whether the server-only attributes and AI state have been pushed once from the (synced) config on this entity. */
    private var initialApplied = false

    /** The NPC's home position (CustomNPC `DataAI.startPos`), captured on the first server tick unless loaded. */
    private var homePos: BlockPos = BlockPos.ZERO

    /** Whether [homePos] is meaningful; false only between construction and the first server tick / NBT load. */
    private var homeSet = false

    /** Whether [travel] currently has the mount-control step-height boost applied. */
    private var mountStepBoost = false

    /** Last value written through [setPathfindingMalus] for [PathType.WATER]; NaN forces a first write. */
    private var appliedWaterMalus = Float.NaN

    /** Game tick at which a dead NPC may respawn (0 = no pending respawn). */
    private var respawnAt = 0L

    /** Whether the current corpse was hidden by [GlbNpcStats.hideKilledBody] (so respawn restores visibility). */
    private var hiddenCorpse = false

    /** Ticks accumulated for the 20-tick (one second) regeneration cadence. */
    private var regenTicks = 0

    /** Per-category cursor used when [GlbNpcAdvanced.orderedLines] is on (server-side only, not persisted). */
    private val lineCursors = IntArray(GlbNpcAdvanced.LINE_COUNT)

    /** Cooldowns for the ambient world-line and nearby-NPC-line rolls. */
    private var worldLineCooldown = 0
    private var npcLineCooldown = 0

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        GlbNpcConfig.defineSynchedData(builder)
    }

    override fun tick() {
        super.tick()
        if (level().isClientSide) {
            // Our dead NPC is never removed by the server (it respawns), so unlike vanilla the client would keep a
            // deathTime from the last death forever. Clear it once health is synced back up.
            if (deathTime > 0 && health > 0f) deathTime = 0
            return
        }
        // The attributes are server-only and not restored from our synced mirrors on world load, so apply them once
        // on the entity's first server tick — this covers both reload-from-NBT and a fresh summon. Later edits
        // re-apply directly from the packet handlers.
        if (!initialApplied) {
            initialApplied = true
            applyMaxHealth()
            applyStats()
            applyAi()
            return
        }
        refreshWaterMalus()
        tickRegen()
        tickLines()
    }

    /**
     * The water malus has to follow the NPC's own state: `-1` makes water unpathable, including as the *start* node,
     * so an avoids-water NPC that is already swimming would get no path at all. While it is in water the malus drops
     * to vanilla-neutral so the pathfinder can route it out; it goes back to `-1` as soon as it is dry again.
     */
    private fun refreshWaterMalus() {
        val malus = if (config.ai.avoidsWater && !isInWater && !isInLava) -1f else 0f
        if (malus == appliedWaterMalus) return
        setPathfindingMalus(PathType.WATER, malus)
        appliedWaterMalus = malus
    }

    /** Push [GlbNpcConfig.maxHealth] onto the live `MAX_HEALTH` attribute and clamp current health to the new cap. */
    fun applyMaxHealth() {
        if (level().isClientSide) return
        val target = config.effectiveMaxHealth().toDouble()
        val attribute = getAttribute(Attributes.MAX_HEALTH) ?: return
        attribute.setBaseValue(target)
        if (health > target) setHealth(target.toFloat())
    }

    /** Push the Stats block onto the attributes it owns: aggro range, melee damage, and melee knockback. */
    fun applyStats() {
        if (level().isClientSide) return
        val stats = config.stats
        getAttribute(Attributes.FOLLOW_RANGE)?.baseValue = stats.aggroRange.toDouble()
        getAttribute(Attributes.ATTACK_DAMAGE)?.baseValue = stats.meleeStrength.toDouble()
        getAttribute(Attributes.ATTACK_KNOCKBACK)?.baseValue = stats.meleeKnockback.toDouble()
    }

    /**
     * Push the AI block onto the navigation/attributes it owns, mirroring CustomNPC's `doorInteractType` /
     * `avoidsWater` / `setWalkingSpeed` / return-home setup. Idempotent, server-only: called once on the first server
     * tick and again from every [com.huashanlunjian.glbrender.npc.net.SetAiPacket].
     */
    fun applyAi() {
        if (level().isClientSide) return
        val ai = config.ai
        // CNPC enables door pathing for both Break and Open; Disabled leaves the navigation door-blind.
        (navigation as? GroundPathNavigation)?.setCanOpenDoors(ai.doorMode != GlbNpcAi.DOOR_DISABLED)
        appliedWaterMalus = Float.NaN
        refreshWaterMalus()
        getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = ai.movementSpeed()
        // The home anchor is captured once (at spawn) and kept in NBT, like CNPC's startPos. `walkingRange` clamps the
        // wander target ([GlbWanderGoal]) and triggers [GlbReturnHomeGoal]; there is deliberately no hard path
        // restriction, which would fight the wander path at the radius boundary.
        if (!homeSet) {
            homePos = blockPosition()
            homeSet = true
        }
    }

    /** Home anchor for the walking-range/return-home goals, or null before the first server tick. */
    internal fun homeAnchor(): BlockPos? = if (homeSet) homePos else null

    /** Squared distance from the NPC to the centre of the home block. */
    internal fun distanceToHomeSqr(): Double =
        if (!homeSet) 0.0 else distanceToSqr(homePos.x + 0.5, homePos.y + 0.5, homePos.z + 0.5)

    /** Squared `walkingRange`, the radius the NPC is allowed to roam without being called home. */
    internal fun homeRangeSqr(): Double {
        val range = config.ai.walkingRange.toDouble()
        return range * range
    }

    /** Ground navigation that can path through closed fence gates as well as wooden doors (see [GlbNpcNavigation]). */
    override fun createNavigation(level: Level): PathNavigation = GlbNpcNavigation(this, level)

    /** CustomNPC's regen cadence: once per second, either out-of-combat or in-combat regen, never both. */
    private fun tickRegen() {
        if (health <= 0f || ++regenTicks < REGEN_INTERVAL) return
        regenTicks = 0
        if (health >= maxHealth) return
        val stats = config.stats
        if (target != null) {
            if (stats.combatRegen > 0) heal(stats.combatRegen.toFloat())
        } else if (stats.healthRegen > 0) {
            heal(stats.healthRegen.toFloat())
        }
    }

    // ---- Advanced block: lines + voice sounds (CustomNPC DataAdvanced) ----

    /**
     * Rolls the two ambient line categories: world chatter (CNPC's 1-in-1800 tick roll with a 100-tick cooldown while
     * idle) and greeting a nearby GLB NPC. Both are server-only and skip the scan entirely when the category is empty.
     */
    private fun tickLines() {
        val advanced = config.advanced
        if (worldLineCooldown > 0) worldLineCooldown--
        if (npcLineCooldown > 0) npcLineCooldown--
        if (isDeadOrDying || target != null) return
        if (worldLineCooldown == 0 && advanced.hasLines(GlbNpcAdvanced.LINE_WORLD) &&
            random.nextInt(WORLD_LINE_CHANCE) == 0
        ) {
            worldLineCooldown = WORLD_LINE_COOLDOWN
            sayLine(GlbNpcAdvanced.LINE_WORLD, null)
        }
        if (npcLineCooldown == 0 && advanced.hasLines(GlbNpcAdvanced.LINE_NPC_INTERACT) &&
            random.nextInt(NPC_LINE_CHANCE) == 0
        ) {
            val otherNearby = level().getEntitiesOfClass(GlbNpc::class.java, boundingBox.inflate(NPC_LINE_RANGE))
                .any { it !== this }
            if (otherNearby) {
                npcLineCooldown = NPC_LINE_COOLDOWN
                sayLine(GlbNpcAdvanced.LINE_NPC_INTERACT, null)
            }
        }
    }

    /**
     * Picks a line from [category] (random, or slot order when [GlbNpcAdvanced.orderedLines] is on), substitutes
     * `@target`, plays the line's sound and sends the text to players within [LINE_RANGE_SQR]. The text goes through
     * chat rather than an overhead speech bubble: CNPC draws bubbles from a client-side packet system we do not have,
     * while a chat message is server-local and needs no new payload.
     */
    private fun sayLine(category: Int, target: LivingEntity?) {
        if (level().isClientSide) return
        val advanced = config.advanced
        val filled = advanced.filledLines(category)
        if (filled.isEmpty()) return
        val line = if (advanced.orderedLines) {
            val index = Math.floorMod(lineCursors[category]++, filled.size)
            filled[index]
        } else {
            filled[random.nextInt(filled.size)]
        }
        val text = if (target == null) line.text else line.text.replace("@target", target.name.string)
        if (text.isNotBlank()) {
            val message = Component.literal(text)
            for (player in level().players()) {
                if (player.distanceToSqr(this) <= LINE_RANGE_SQR) player.displayClientMessage(message, false)
            }
        }
        playConfiguredSound(line.sound, 1.0f, getVoicePitch())
    }

    /** Resolves a sound id against the built-in registry; unknown/blank ids resolve to null (caller falls back). */
    private fun configuredSound(id: String): SoundEvent? {
        if (id.isEmpty()) return null
        val location = ResourceLocation.tryParse(id) ?: return null
        return BuiltInRegistries.SOUND_EVENT.get(location)
    }

    private fun playConfiguredSound(id: String, volume: Float, pitch: Float) {
        configuredSound(id)?.let { playSound(it, volume, pitch) }
    }

    override fun getAmbientSound(): SoundEvent? =
        (if (configReady) configuredSound(config.advanced.idleSound) else null) ?: super.getAmbientSound()

    override fun getHurtSound(source: DamageSource): SoundEvent? =
        (if (configReady) configuredSound(config.advanced.hurtSound) else null) ?: super.getHurtSound(source)

    override fun getDeathSound(): SoundEvent? =
        (if (configReady) configuredSound(config.advanced.deathSound) else null) ?: super.getDeathSound()

    /** A configured step sound replaces the block's own footstep sound (vanilla step volume is 0.15). */
    override fun playStepSound(pos: BlockPos, state: BlockState) {
        val custom = if (configReady) configuredSound(config.advanced.stepSound) else null
        if (custom == null) {
            super.playStepSound(pos, state)
            return
        }
        playSound(custom, 0.15f, getVoicePitch())
    }

    override fun getVoicePitch(): Float =
        if (configReady && config.advanced.disablePitch) 1.0f else super.getVoicePitch()

    /**
     * Registers the goal set once. `Mob`'s constructor calls this before [config] exists, so the goals here must be
     * self-gating (they read [config] inside `canUse`, not in their constructors) — see [GlbWanderGoal] /
     * [GlbFollowPlayerGoal]. Priority order follows the usual convention: swim out of water first, then follow, then
     * wander, then look.
     */
    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, GlbSitGoal(this))
        // Guard's attack goals sit just under sit (so a sitting guard won't attack) and above the reactive flee/panic
        // and locomotion goals. Only one of the two starts: each is gated on the Stats attackType and only ever runs
        // when profession==GUARD and a target is set below.
        goalSelector.addGoal(2, GlbGuardAttackGoal(this))
        goalSelector.addGoal(2, GlbRangedAttackGoal(this))
        goalSelector.addGoal(2, GlbFactionAttackGoal(this))
        goalSelector.addGoal(3, GlbLeapGoal(this))
        goalSelector.addGoal(4, GlbPanicGoal(this))
        goalSelector.addGoal(5, GlbFleeGoal(this))
        goalSelector.addGoal(6, GlbFollowPlayerGoal(this))
        goalSelector.addGoal(7, GlbReturnHomeGoal(this))
        // Reads the path nodes each tick, so it can sit under the movement goals without fighting them.
        goalSelector.addGoal(8, GlbDoorGoal(this))
        goalSelector.addGoal(9, GlbIndoorShelterGoal(this))
        goalSelector.addGoal(9, GlbSunShelterGoal(this))
        goalSelector.addGoal(9, GlbRestrictSunGoal(this))
        goalSelector.addGoal(10, GlbWanderGoal(this))
        goalSelector.addGoal(11, GlbLookPlayerGoal(this))
        goalSelector.addGoal(12, RandomLookAroundGoal(this))
        // Target acquisition lives in the target selector; both goals only set getTarget() for the melee goals above.
        targetSelector.addGoal(1, GlbGuardTargetGoal(this))
        targetSelector.addGoal(2, GlbFactionTargetGoal(this))
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        config.save(tag)
        if (homeSet) tag.putLong(KEY_HOME, homePos.asLong())
        val inventoryTag = CompoundTag()
        ContainerHelper.saveAllItems(inventoryTag, storage.items, registryAccess())
        tag.put(KEY_INVENTORY, inventoryTag)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        config.load(tag)
        if (tag.contains(KEY_HOME)) {
            homePos = BlockPos.of(tag.getLong(KEY_HOME))
            homeSet = true
        }
        storage.clearContent()
        if (tag.contains(KEY_INVENTORY)) {
            ContainerHelper.loadAllItems(tag.getCompound(KEY_INVENTORY), storage.items, registryAccess())
        }
    }

    /**
     * Drop each non-empty "NPC Inventory" slot with its configured chance, matching CustomNPC's `DataInventory`
     * behavior. Equipment (armor/hands) is left to the vanilla `Mob` death-drop path, which runs in `super.die`.
     */
    override fun die(damageSource: DamageSource) {
        if (!level().isClientSide) {
            sayLine(GlbNpcAdvanced.LINE_KILLED, damageSource.entity as? LivingEntity)
            dropConfiguredLoot()
            if (config.stats.hideKilledBody) {
                hiddenCorpse = true
                isInvisible = true
            }
        }
        super.die(damageSource)
    }

    private fun dropConfiguredLoot() {
        val level = level()
        for (index in 0 until GlbNpcInventory.LOOT_SLOTS) {
            val storageIndex = GlbNpcInventory.LOOT_START + index
            val stack = storage.getItem(storageIndex)
            if (stack.isEmpty || random.nextFloat() * 100f >= config.dropChance(index)) continue
            val dropped = stack.copy()
            storage.setItem(storageIndex, ItemStack.EMPTY)
            val itemEntity = ItemEntity(level, x, y - 0.3 + eyeHeight, z, dropped)
            itemEntity.setPickUpDelay(40)
            val speed = random.nextFloat() * 0.5f
            val angle = random.nextFloat() * 2f * Math.PI.toFloat()
            itemEntity.setDeltaMovement(
                (-Mth.sin(angle) * speed).toDouble(),
                0.2,
                (Mth.cos(angle) * speed).toDouble(),
            )
            level.addFreshEntity(itemEntity)
        }
    }

    // ---- Stats passives (CustomNPC DataStats) ----

    override fun fireImmune(): Boolean = configReady && config.stats.immuneToFire

    override fun isInvertedHealAndHarm(): Boolean =
        (configReady && config.stats.creatureType == GlbNpcStats.CREATURE_UNDEAD) || super.isInvertedHealAndHarm()

    override fun canBeAffected(effect: MobEffectInstance): Boolean {
        if (configReady) {
            val stats = config.stats
            if (stats.potionImmune) return false
            if (stats.creatureType == GlbNpcStats.CREATURE_ARTHROPOD && effect.effect == MobEffects.POISON) return false
        }
        return super.canBeAffected(effect)
    }

    override fun decreaseAirSupply(airSupply: Int): Int =
        if (configReady && !config.stats.canDrown) airSupply else super.decreaseAirSupply(airSupply)

    override fun causeFallDamage(fallDistance: Float, multiplier: Float, damageSource: DamageSource): Boolean =
        if (configReady && config.stats.noFallDamage) false
        else super.causeFallDamage(fallDistance, multiplier, damageSource)

    override fun makeStuckInBlock(state: BlockState, motionMultiplier: Vec3) {
        if (configReady && config.stats.ignoreCobweb && state.`is`(Blocks.COBWEB)) return
        super.makeStuckInBlock(state, motionMultiplier)
    }

    /** Damage resistances: projectile/explosion/melee multiplier `2 - resistance`, applied before vanilla armor. */
    override fun hurt(source: DamageSource, amount: Float): Boolean {
        val scaled = if (configReady && !level().isClientSide) {
            amount * config.stats.resistanceMultiplier(source)
        } else {
            amount
        }
        val hurt = super.hurt(source, scaled)
        if (hurt && !level().isClientSide) defendFaction(source.entity)
        return hurt
    }

    /**
     * CustomNPC's "Defending faction": the wounded NPC takes the attacker as its own target (unless already fighting
     * something) and wakes same-faction NPCs within [FACTION_ALERT_RANGE] that have no target, so
     * [GlbFactionAttackGoal] executes on the next evaluation. Factionless NPCs and NPCs with the switch off do nothing.
     */
    private fun defendFaction(attacker: Entity?) {
        val advanced = config.advanced
        if (!advanced.defendFaction || advanced.faction == GlbNpcAdvanced.FACTION_NONE) return
        val enemy = attacker as? LivingEntity ?: return
        if (enemy === this || !enemy.isAlive) return
        if (target == null && canAttack(enemy)) target = enemy
        val allies = level().getEntitiesOfClass(GlbNpc::class.java, boundingBox.inflate(FACTION_ALERT_RANGE))
        for (ally in allies) {
            if (ally === this) continue
            if (ally.config.advanced.faction != advanced.faction) continue
            if (ally.target == null && ally.canAttack(enemy)) ally.target = enemy
        }
    }

    /** True when both NPCs carry the same non-zero faction; used to keep teammates from hurting each other. */
    private fun sharesFaction(other: GlbNpc): Boolean {
        val mine = config.advanced.faction
        return mine != GlbNpcAdvanced.FACTION_NONE && mine == other.config.advanced.faction
    }

    /** Knockback resistance: the same `2 - resistance` multiplier CustomNPC applies around vanilla knockback. */
    override fun knockback(strength: Double, x: Double, z: Double) {
        val resistance = if (configReady) config.stats.resistKnockback else 1f
        super.knockback(strength * (2.0 - resistance), x, z)
    }

    /** Applies the configured on-hit effect after a successful melee hit (CNPC's melee potion effect). */
    override fun doHurtTarget(target: Entity): Boolean {
        // Faction teammates cannot damage each other (CNPC faction rule); factionless NPCs keep vanilla behavior.
        if (configReady && target is GlbNpc && sharesFaction(target)) return false
        val hit = super.doHurtTarget(target)
        if (hit && target is LivingEntity && configReady) {
            val stats = config.stats
            stats.meleeEffectHolder()?.let { effect ->
                target.addEffect(
                    MobEffectInstance(effect, stats.meleeEffectTime * TICKS_PER_SECOND, stats.meleeEffectAmplifier),
                    this,
                )
            }
            if (!level().isClientSide) {
                sayLine(GlbNpcAdvanced.LINE_ATTACK, target)
                playConfiguredSound(config.advanced.angrySound, 1.0f, getVoicePitch())
            }
        }
        return hit
    }

    /** CNPC's kill line: spoken when the NPC's blow is credited with killing a target. */
    override fun killedEntity(serverLevel: ServerLevel, entity: LivingEntity): Boolean {
        val credited = super.killedEntity(serverLevel, entity)
        if (configReady) sayLine(GlbNpcAdvanced.LINE_KILL, entity)
        return credited
    }

    /** CNPC `burnInSun`: catch fire in daylight when exposed to the sky (8 seconds, like vanilla undead). */
    override fun aiStep() {
        if (configReady && !level().isClientSide && config.stats.burnInSun && level().isDay) {
            val light = getLightLevelDependentMagicValue()
            if (light > 0.5f && random.nextFloat() * 30f < (light - 0.4f) * 2f &&
                level().canSeeSkyFromBelowWater(blockPosition())
            ) {
                remainingFireTicks = SUN_BURN_TICKS
            }
        }
        super.aiStep()
    }

    // ---- Death / respawn (CustomNPC spawn cycle) ----

    /**
     * Custom death handling for the respawning spawn cycles: instead of removing the entity at deathTime 20 like
     * vanilla, the corpse stays in the world (optionally invisible) and the NPC revives after `respawnTime`, but only
     * during the allowed Day/Night window. `No` and `Naturally` keep vanilla behavior (permanent death); `Naturally`
     * also opts back into vanilla distance despawning (see [removeWhenFarAway]).
     */
    override fun tickDeath() {
        if (level().isClientSide) {
            if (deathTime < DEATH_TICKS) deathTime++
            return
        }
        val stats = config.stats
        if (!respawns(stats.spawnCycle)) {
            super.tickDeath()
            return
        }
        deathTime++
        if (deathTime == DEATH_TICKS) respawnAt = level().gameTime + stats.respawnTime * TICKS_PER_SECOND
        if (deathTime >= DEATH_TICKS && respawnAt > 0L && level().gameTime >= respawnAt && spawnAllowedNow(stats.spawnCycle)) {
            respawnNpc()
        }
    }

    private fun respawnNpc() {
        deathTime = 0
        dead = false
        respawnAt = 0L
        if (hiddenCorpse) {
            hiddenCorpse = false
            isInvisible = false
        }
        remainingFireTicks = 0
        hurtTime = 0
        target = null
        navigation.stop()
        setSprinting(false)
        deltaMovement = Vec3.ZERO
        setHealth(maxHealth)
        applyStats()
    }

    private fun respawns(spawnCycle: Int): Boolean =
        spawnCycle != GlbNpcStats.SPAWN_NO && spawnCycle != GlbNpcStats.SPAWN_NATURALLY

    private fun spawnAllowedNow(spawnCycle: Int): Boolean = when (spawnCycle) {
        GlbNpcStats.SPAWN_DAY -> level().isDay
        GlbNpcStats.SPAWN_NIGHT -> !level().isDay
        else -> true
    }

    // ---- Ranged attack (vanilla arrows; CNPC's custom projectile entity is out of scope) ----

    override fun performRangedAttack(target: LivingEntity, velocity: Float) {
        val stats = config.stats
        val arrow = Arrow(level(), this, ItemStack(Items.ARROW), ItemStack(Items.ARROW))
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED
        arrow.setBaseDamage(stats.rangedDamage.toDouble())
        val dx = target.x - x
        val dz = target.z - z
        val horizontal = Math.sqrt(dx * dx + dz * dz)
        val dy = target.y + target.bbHeight * 0.3333333333333333 - arrow.y
        val inaccuracy = (100 - stats.rangedAccuracy).coerceAtLeast(0) * INACCURACY_PER_PERCENT
        arrow.shoot(dx, dy + horizontal * 0.2, dz, velocity, inaccuracy)
        level().addFreshEntity(arrow)
        playSound(SoundEvents.SKELETON_SHOOT, 1.0f, 1.0f / (random.nextFloat() * 0.4f + 0.8f))
    }

    // ---- Mount control (CustomNPC DataAI.mountControl) ----

    /**
     * With mount control on, the first passenger is the controlling passenger (vanilla's `Mob` version only accepts
     * mobs that themselves can drive). Without it the NPC keeps vanilla's behavior, so a passenger cannot steer it.
     */
    override fun getControllingPassenger(): LivingEntity? =
        if (configReady && config.ai.mountControl) passengers.firstOrNull() as? LivingEntity
        else super.getControllingPassenger()

    /**
     * CustomNPC's rider-driven travel: face the rider's view, then move on the rider's forward/strafe input, with the
     * step height raised so the NPC can climb a block while being ridden. Only the local instance steers from input —
     * on the server the rider's `xxa`/`zza` are not populated and the vehicle position arrives in move-vehicle
     * packets, mirroring vanilla horses — while the step-height boost is kept on both sides for pathing.
     */
    override fun travel(input: Vec3) {
        val rider = if (configReady && config.ai.mountControl) getControllingPassenger() else null
        if (rider == null) {
            if (mountStepBoost) {
                getAttribute(Attributes.STEP_HEIGHT)?.baseValue = DEFAULT_STEP_HEIGHT
                mountStepBoost = false
            }
            super.travel(input)
            return
        }
        if (!mountStepBoost) {
            getAttribute(Attributes.STEP_HEIGHT)?.baseValue = MOUNT_STEP_HEIGHT
            mountStepBoost = true
        }
        if (!isControlledByLocalInstance) {
            super.travel(input)
            return
        }
        setYRot(rider.yRot)
        yRotO = yRot
        setXRot(rider.xRot * 0.5f)
        xRotO = xRot
        yBodyRot = yRot
        yHeadRot = yRot
        val forward = if (rider.zza <= 0f) rider.zza * 0.25f else rider.zza
        super.travel(Vec3((rider.xxa * 0.5f).toDouble(), input.y, forward.toDouble()))
    }

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS
        }
        if (player.isSecondaryUseActive) {
            // Sneak + right-click: the CustomNPC-style editor. Server-side: start an editing session (so the C2S
            // packets authorize this player for this NPC), then open the container menu, writing the entity id so the
            // client factory resolves the same NPC and GlbNpcClient's bound screen opens.
            if (!level().isClientSide && player is ServerPlayer) {
                GlbNpcEditing.open(player, this)
                player.openMenu(
                    object : MenuProvider {
                        override fun getDisplayName(): Component = Component.literal("GLB NPC")
                        override fun createMenu(windowId: Int, inv: Inventory, p: Player): AbstractContainerMenu =
                            GlbNpcMenu(windowId, this@GlbNpc, inv)
                    },
                    { buf -> buf.writeInt(id) },
                )
                return InteractionResult.CONSUME
            }
            return InteractionResult.sidedSuccess(level().isClientSide)
        }
        // Plain right-click: say the interact line (server-side; the text is sent to the interacting player) and talk.
        if (!level().isClientSide) {
            sayLine(GlbNpcAdvanced.LINE_INTERACT, player)
        }
        // The dialog lines and the bound world-dialog ids are already synced, so the client opens its screen with no
        // server round trip. Bound dialogs take precedence over the inline lines, like CNPC's option slots.
        if (level().isClientSide) {
            val name = displayName ?: Component.translatable(type.descriptionId)
            val dialogIds = config.advanced.boundDialogIds()
            if (dialogIds.isNotEmpty()) {
                GlbNpcClientHooks.openDialogChooser?.invoke(name, dialogIds, config.dialogLines)
                return InteractionResult.SUCCESS
            }
            val lines = config.dialogLines
            if (lines.isNotEmpty()) {
                GlbNpcClientHooks.openDialog?.invoke(name, lines)
                return InteractionResult.SUCCESS
            }
        }
        return InteractionResult.PASS
    }

    /**
     * Distance despawning is opt-in through the `Naturally` spawn cycle (CNPC's wording for "behaves like a natural
     * mob"); every other cycle keeps the NPC around so it can be found and edited again.
     */
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean =
        configReady && config.stats.spawnCycle == GlbNpcStats.SPAWN_NATURALLY

    override fun checkDespawn() {
        if (configReady && config.stats.spawnCycle == GlbNpcStats.SPAWN_NATURALLY) super.checkDespawn()
    }

    companion object {
        init {
            // Register the synced-data accessors now. SynchedEntityData sizes its per-entity id array from the
            // registry when the Entity constructor runs, but Kotlin companions initialize lazily: without this the
            // first touch is inside defineSynchedData, after the array was already sized, and the first NPC built in
            // a fresh JVM (e.g. loaded from a save) throws ArrayIndexOutOfBoundsException. Class initialization runs
            // before any constructor, so this is early enough.
            GlbNpcConfig.ensureAccessorsRegistered()
        }

        fun attributes(): AttributeSupplier.Builder =
            PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.FOLLOW_RANGE, 16.0)
                // Only consumed by the guard profession's melee goal (GlbGuardAttackGoal); harmless for other NPCs.
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.ATTACK_KNOCKBACK, 0.0)

        private const val TICKS_PER_SECOND = 20
        private const val REGEN_INTERVAL = 20
        private const val DEATH_TICKS = 20
        /** Vanilla's 8-second undead daylight burn. */
        private const val SUN_BURN_TICKS = 160
        /** Degrees of arrow spread per missing accuracy point (60% accuracy = 10°). */
        private const val INACCURACY_PER_PERCENT = 0.25f

        /** Vanilla's default step height, restored when nobody is riding the NPC. */
        private const val DEFAULT_STEP_HEIGHT = 0.6
        /** CNPC's step height while mount control is active. */
        private const val MOUNT_STEP_HEIGHT = 1.1

        /** Ambient line rolls (CNPC: 1/1800 per tick, 100-tick cooldown; NPC-greeting roll is our own slower cadence). */
        private const val WORLD_LINE_CHANCE = 1800
        private const val WORLD_LINE_COOLDOWN = 100
        private const val NPC_LINE_CHANCE = 600
        private const val NPC_LINE_COOLDOWN = 600
        private const val NPC_LINE_RANGE = 3.0

        /** Squared distance (16 blocks, CNPC's nearby-chat range) that hears a spoken line. */
        private const val LINE_RANGE_SQR = 16.0 * 16.0

        /** Radius in which a defend-faction NPC calls same-faction members to the fight. */
        private const val FACTION_ALERT_RANGE = 16.0

        private const val KEY_INVENTORY = "GlbInventory"
        private const val KEY_HOME = "GlbHome"
    }
}
