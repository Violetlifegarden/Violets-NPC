package com.huashanlunjian.glbrender.npc

import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.Level

/**
 * The Step 2 proof-of-concept NPC: a plain [PathfinderMob] (LivingEntity branch, never a fake Player) that the
 * glbrender [com.huashanlunjian.glbrender.client.npc.GlbNpcRenderer] draws as a GLB model. It carries no goals, so
 * it simply stands where it spawns; the model itself is chosen by the renderer's shared scene cache, not here.
 */
class GlbNpc(type: EntityType<out PathfinderMob>, level: Level) : PathfinderMob(type, level) {

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
