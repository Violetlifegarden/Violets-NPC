package com.huashanlunjian.glbrender.npc

import com.mojang.logging.LogUtils
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.level.Level

/**
 * The NPC: a plain [PathfinderMob] (LivingEntity branch, never a fake Player) that the glbrender
 * [com.huashanlunjian.glbrender.client.npc.GlbNpcRenderer] draws as a GLB model.
 *
 * All per-instance state (model id, transform overrides, behavior flags) lives in [config], which owns the synced
 * data, NBT persistence, and typed accessors. [modelId] is a convenience delegate kept for the renderer/interaction
 * callers. Right-clicking currently cycles the model as a TEMPORARY transition mechanism — it will be replaced by a
 * CustomNPC-style right-click editor UI in Step 5.
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

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        GlbNpcConfig.defineSynchedData(builder)
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
        // Transition model-switch: cycle to the next registered model on the server; the synced value updates the
        // client renderer. Replaced by a right-click editor UI in Step 5.
        if (!level().isClientSide && hand == InteractionHand.MAIN_HAND) {
            val ids = GlbModels.ids()
            LOGGER.info("glbrender: npc {} interact, registered models {}", stringUUID, ids)
            if (ids.size >= 2) {
                val current = modelId.ifEmpty { GlbModels.defaultId }
                val next = ids[(ids.indexOf(current) + 1).mod(ids.size)]
                modelId = next
                LOGGER.info("glbrender: npc {} model {} -> {}", stringUUID, current, next)
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide)
    }

    // Keep the test NPC around long enough to be looked at: never despawn for distance or in peaceful.
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    override fun checkDespawn() {}

    companion object {
        private val LOGGER = LogUtils.getLogger()

        fun attributes(): AttributeSupplier.Builder =
            PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.FOLLOW_RANGE, 16.0)
    }
}
