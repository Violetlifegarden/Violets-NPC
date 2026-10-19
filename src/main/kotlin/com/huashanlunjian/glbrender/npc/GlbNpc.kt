package com.huashanlunjian.glbrender.npc

import com.huashanlunjian.glbrender.npc.net.GlbNpcEditing
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.ContainerHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.world.SimpleContainer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * The NPC: a plain [PathfinderMob] (LivingEntity branch, never a fake Player) that the glbrender
 * [com.huashanlunjian.glbrender.client.npc.GlbNpcRenderer] draws as a GLB model.
 *
 * All per-instance state (model id, transform overrides, behavior flags) lives in [config], which owns the synced
 * data, NBT persistence, and typed accessors. [modelId] is a convenience delegate kept for the renderer/interaction
 * callers. Sneak right-clicking opens the CustomNPC-style editor menu ([GlbNpcMenu]); a plain right-click makes the
 * NPC talk through its dialog lines (see [GlbNpcClientHooks.openDialog]).
 */
class GlbNpc(type: EntityType<out PathfinderMob>, level: Level) : PathfinderMob(type, level) {

    /** Per-instance config: synced data + NBT + typed getters/setters. Initialized after super() (entityData exists). */
    val config = GlbNpcConfig(this)

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

    private var maxHealthApplied = false

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        GlbNpcConfig.defineSynchedData(builder)
    }

    override fun tick() {
        super.tick()
        // The MAX_HEALTH attribute is server-only and not restored from our synced mirror on world load, so apply it
        // once on the entity's first server tick — this covers both reload-from-NBT and a fresh summon. Later edits
        // re-apply directly from the packet handler.
        if (!maxHealthApplied && !level().isClientSide) {
            maxHealthApplied = true
            applyMaxHealth()
        }
    }

    /** Push [GlbNpcConfig.maxHealth] onto the live `MAX_HEALTH` attribute and clamp current health to the new cap. */
    fun applyMaxHealth() {
        if (level().isClientSide) return
        val target = config.effectiveMaxHealth().toDouble()
        val attribute = getAttribute(Attributes.MAX_HEALTH) ?: return
        attribute.setBaseValue(target)
        if (health > target) setHealth(target.toFloat())
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
        // Guard's melee goal sits just under sit (so a sitting guard won't chase) and above the reactive flee/panic
        // and locomotion goals; it only ever starts when profession==GUARD and a target is set below.
        goalSelector.addGoal(2, GlbGuardAttackGoal(this))
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
        if (!level().isClientSide) dropConfiguredLoot()
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

    // Keep the test NPC around long enough to be looked at: never despawn for distance or in peaceful.
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    override fun checkDespawn() {}

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

        private const val KEY_INVENTORY = "GlbInventory"
    }
}
