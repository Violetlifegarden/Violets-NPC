package com.huashanlunjian.glbrender.npc

import com.huashanlunjian.glbrender.npc.net.GlbNpcEditing
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.level.Level

/**
 * The NPC: a plain [PathfinderMob] (LivingEntity branch, never a fake Player) that the glbrender
 * [com.huashanlunjian.glbrender.client.npc.GlbNpcRenderer] draws as a GLB model.
 *
 * All per-instance state (model id, transform overrides, behavior flags) lives in [config], which owns the synced
 * data, NBT persistence, and typed accessors. [modelId] is a convenience delegate kept for the renderer/interaction
 * callers. Right-clicking opens the CustomNPC-style editor menu ([GlbNpcMenu]) after starting an editing session.
 */
class GlbNpc(type: EntityType<out PathfinderMob>, level: Level) : PathfinderMob(type, level) {

    /** Per-instance config: synced data + NBT + typed getters/setters. Initialized after super() (entityData exists). */
    val config = GlbNpcConfig(this)

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
        goalSelector.addGoal(2, GlbPanicGoal(this))
        goalSelector.addGoal(3, GlbFleeGoal(this))
        goalSelector.addGoal(4, GlbFollowPlayerGoal(this))
        goalSelector.addGoal(5, GlbWanderGoal(this))
        goalSelector.addGoal(6, GlbLookPlayerGoal(this))
        goalSelector.addGoal(7, RandomLookAroundGoal(this))
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        config.save(tag)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        config.load(tag)
    }

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        // Right-click opens the CustomNPC-style editor (replaces the temporary model-cycle). Server-side: start an
        // editing session (so the C2S packets authorize this player for this NPC), then open the container menu,
        // writing the entity id so the client factory can resolve the same NPC.
        if (!level().isClientSide && hand == InteractionHand.MAIN_HAND && player is ServerPlayer) {
            GlbNpcEditing.open(player, this)
            player.openMenu(
                object : MenuProvider {
                    override fun getDisplayName(): Component = Component.literal("GLB NPC")
                    override fun createMenu(windowId: Int, inv: Inventory, p: Player): AbstractContainerMenu =
                        GlbNpcMenu(windowId, this@GlbNpc)
                },
                { buf -> buf.writeInt(id) },
            )
        }
        return InteractionResult.sidedSuccess(level().isClientSide)
    }

    // Keep the test NPC around long enough to be looked at: never despawn for distance or in peaceful.
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    override fun checkDespawn() {}

    companion object {
        fun attributes(): AttributeSupplier.Builder =
            PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.FOLLOW_RANGE, 16.0)
    }
}
