package com.huashanlunjian.glbrender.npc

import com.huashanlunjian.glbrender.npc.net.GlbNpcEditing
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
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

    /** Whether the server-only attributes have been pushed once from the (synced) config on this entity. */
    private var statsApplied = false

    /** Game tick at which a dead NPC may respawn (0 = no pending respawn). */
    private var respawnAt = 0L

    /** Whether the current corpse was hidden by [GlbNpcStats.hideKilledBody] (so respawn restores visibility). */
    private var hiddenCorpse = false

    /** Ticks accumulated for the 20-tick (one second) regeneration cadence. */
    private var regenTicks = 0

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
        if (!statsApplied) {
            statsApplied = true
            applyMaxHealth()
            applyStats()
            return
        }
        tickRegen()
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
        goalSelector.addGoal(3, GlbPanicGoal(this))
        goalSelector.addGoal(4, GlbFleeGoal(this))
        goalSelector.addGoal(5, GlbFollowPlayerGoal(this))
        goalSelector.addGoal(6, GlbWanderGoal(this))
        goalSelector.addGoal(7, GlbLookPlayerGoal(this))
        goalSelector.addGoal(8, RandomLookAroundGoal(this))
        // The guard's target acquisition lives in the target selector; it sets getTarget() for the melee goal above.
        targetSelector.addGoal(1, GlbGuardTargetGoal(this))
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        config.save(tag)
        val inventoryTag = CompoundTag()
        ContainerHelper.saveAllItems(inventoryTag, storage.items, registryAccess())
        tag.put(KEY_INVENTORY, inventoryTag)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        config.load(tag)
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
        return super.hurt(source, scaled)
    }

    /** Knockback resistance: the same `2 - resistance` multiplier CustomNPC applies around vanilla knockback. */
    override fun knockback(strength: Double, x: Double, z: Double) {
        val resistance = if (configReady) config.stats.resistKnockback else 1f
        super.knockback(strength * (2.0 - resistance), x, z)
    }

    /** Applies the configured on-hit effect after a successful melee hit (CNPC's melee potion effect). */
    override fun doHurtTarget(target: Entity): Boolean {
        val hit = super.doHurtTarget(target)
        if (hit && target is LivingEntity && configReady) {
            val stats = config.stats
            stats.meleeEffectHolder()?.let { effect ->
                target.addEffect(
                    MobEffectInstance(effect, stats.meleeEffectTime * TICKS_PER_SECOND, stats.meleeEffectAmplifier),
                    this,
                )
            }
        }
        return hit
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
        // Plain right-click: talk. The dialog lines are already synced on config, so the client walks them in a screen
        // with no server round trip; the server just consumes the interaction so it does not also trigger item use.
        if (level().isClientSide) {
            val lines = config.dialogLines
            if (lines.isNotEmpty()) {
                GlbNpcClientHooks.openDialog?.invoke(
                    displayName ?: Component.translatable(type.descriptionId),
                    lines,
                )
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

        private const val KEY_INVENTORY = "GlbInventory"
    }
}
