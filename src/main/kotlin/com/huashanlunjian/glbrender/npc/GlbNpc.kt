package com.huashanlunjian.glbrender.npc

import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level

/**
 * The NPC: a plain [PathfinderMob] (LivingEntity branch, never a fake Player) that the glbrender
 * [com.huashanlunjian.glbrender.client.npc.GlbNpcRenderer] draws as a GLB model. It carries no goals, so it simply
 * stands where it spawns.
 *
 * The look is chosen by a synced [modelId] (fork A: one EntityType, the id selects the model). Empty means "use the
 * registry default". Right-clicking cycles the id through [GlbModels] as a TEMPORARY transition mechanism — it will
 * be replaced by a CustomNPC-style right-click UI later.
 */
class GlbNpc(type: EntityType<out PathfinderMob>, level: Level) : PathfinderMob(type, level) {

    /** The model id this NPC renders as; empty falls back to [GlbModels.defaultId] on the client. */
    var modelId: String
        get() = entityData.get(DATA_MODEL_ID)
        set(value) = entityData.set(DATA_MODEL_ID, value)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_MODEL_ID, DEFAULT_MODEL_ID)
    }

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        // Transition model-switch: cycle to the next registered model on the server; the synced value updates the
        // client renderer. Replaced by a right-click UI later.
        if (!level().isClientSide && hand == InteractionHand.MAIN_HAND) {
            val ids = GlbModels.ids()
            if (ids.size >= 2) {
                val current = modelId.ifEmpty { GlbModels.defaultId }
                modelId = ids[(ids.indexOf(current) + 1).mod(ids.size)]
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide)
    }

    // Keep the test NPC around long enough to be looked at: never despawn for distance or in peaceful.
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    override fun checkDespawn() {}

    companion object {
        private val DATA_MODEL_ID: EntityDataAccessor<String> =
            SynchedEntityData.defineId(GlbNpc::class.java, EntityDataSerializers.STRING)

        /** Empty by default so the client resolves it against [GlbModels.defaultId] (which the server may not know). */
        const val DEFAULT_MODEL_ID = ""

        fun attributes(): AttributeSupplier.Builder =
            PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.FOLLOW_RANGE, 16.0)
    }
}
