package com.huashanlunjian.glbrender.client.npc.anim

import net.minecraft.world.entity.LivingEntity
import org.joml.Vector3d
import top.fifthlight.blazerod.model.animation.AnimationContext
import top.fifthlight.blazerod.model.util.MutableBoolean
import top.fifthlight.blazerod.model.util.MutableDouble
import top.fifthlight.blazerod.model.util.MutableFloat
import top.fifthlight.blazerod.model.util.MutableInt
import top.fifthlight.blazerod.model.util.MutableLong

/**
 * An [AnimationContext] over a generic [LivingEntity] (our `PathfinderMob` NPCs), the entity-side twin of
 * `PlayerAnimationContext`. Baked glTF clips advance purely from [getGameTick]/[getDeltaTick] via
 * `SimpleAnimationState`; the entity properties are supplied so a clip that samples them (procedural channels, or a
 * future retarget) resolves instead of falling back to null. `RenderTarget` is [RenderingTargetType.ENTITY], not
 * `PLAYER` — the player-only head/body rotation properties are intentionally omitted.
 */
class NpcAnimationContext(
    private val entity: LivingEntity,
    private val gameTick: Long,
    private val deltaTick: Float,
) : AnimationContext {

    override fun getGameTick(): Long = gameTick

    override fun getDeltaTick(): Float = deltaTick

    @Suppress("UNCHECKED_CAST")
    override fun <T> getProperty(type: AnimationContext.Property<T>): T? = when (type) {
        AnimationContext.Property.GameTick -> MutableLong(gameTick) as T
        AnimationContext.Property.DeltaTick -> MutableFloat(deltaTick) as T
        AnimationContext.Property.RenderTarget -> AnimationContext.RenderingTargetType.ENTITY as T

        AnimationContext.Property.EntityPosition -> Vector3d(entity.x, entity.y, entity.z) as T
        AnimationContext.Property.EntityGroundSpeed -> MutableDouble(entity.walkDist.toDouble()) as T
        AnimationContext.Property.EntityVerticalSpeed -> MutableDouble(entity.deltaMovement.y) as T
        AnimationContext.Property.EntityIsOnGround -> MutableBoolean(entity.onGround()) as T
        AnimationContext.Property.EntityIsInWater -> MutableBoolean(entity.isInWater) as T

        AnimationContext.Property.LivingEntityHurtTime -> MutableInt(entity.hurtTime) as T
        AnimationContext.Property.LivingEntityIsDead -> MutableBoolean(entity.isDeadOrDying) as T
        AnimationContext.Property.LivingEntityHealth -> MutableFloat(entity.health) as T
        AnimationContext.Property.LivingEntityMaxHealth -> MutableFloat(entity.maxHealth) as T

        else -> null
    }

    override fun getPropertyTypes(): Set<AnimationContext.Property<*>> = SUPPORTED_PROPERTIES

    companion object {
        private val SUPPORTED_PROPERTIES = setOf(
            AnimationContext.Property.GameTick,
            AnimationContext.Property.DeltaTick,
            AnimationContext.Property.RenderTarget,
            AnimationContext.Property.EntityPosition,
            AnimationContext.Property.EntityGroundSpeed,
            AnimationContext.Property.EntityVerticalSpeed,
            AnimationContext.Property.EntityIsOnGround,
            AnimationContext.Property.EntityIsInWater,
            AnimationContext.Property.LivingEntityHurtTime,
            AnimationContext.Property.LivingEntityIsDead,
            AnimationContext.Property.LivingEntityHealth,
            AnimationContext.Property.LivingEntityMaxHealth,
        )
    }
}
