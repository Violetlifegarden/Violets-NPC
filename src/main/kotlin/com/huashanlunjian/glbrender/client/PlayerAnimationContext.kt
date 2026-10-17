package com.huashanlunjian.glbrender.client

import net.minecraft.world.entity.player.Player
import org.joml.Vector3d
import top.fifthlight.blazerod.model.animation.AnimationContext
import top.fifthlight.blazerod.model.util.*

class PlayerAnimationContext(
    private val player: Player,
    private val gameTick: Long,
    private val deltaTick: Float,
) : AnimationContext {

    override fun getGameTick(): Long = gameTick
    override fun getDeltaTick(): Float = deltaTick

    @Suppress("UNCHECKED_CAST")
    override fun <T> getProperty(type: AnimationContext.Property<T>): T? = when (type) {
        AnimationContext.Property.GameTick -> MutableLong(gameTick) as T
        AnimationContext.Property.DeltaTick -> MutableFloat(deltaTick) as T
        AnimationContext.Property.RenderTarget -> AnimationContext.RenderingTargetType.PLAYER as T

        AnimationContext.Property.EntityPosition -> Vector3d(player.x, player.y, player.z) as T
        AnimationContext.Property.EntityGroundSpeed -> MutableDouble(player.walkDist.toDouble()) as T
        AnimationContext.Property.EntityVerticalSpeed -> MutableDouble(player.deltaMovement.y.toDouble()) as T
        AnimationContext.Property.EntityIsOnGround -> MutableBoolean(player.onGround()) as T
        AnimationContext.Property.EntityIsInWater -> MutableBoolean(player.isInWater) as T

        AnimationContext.Property.PlayerHeadXRotation -> MutableFloat(player.xRot) as T
        AnimationContext.Property.PlayerHeadYRotation -> MutableFloat(player.yHeadRot) as T
        AnimationContext.Property.PlayerBodyXRotation -> MutableFloat(player.xRot) as T
        AnimationContext.Property.PlayerBodyYRotation -> MutableFloat(player.yBodyRot) as T
        AnimationContext.Property.PlayerIsSneaking -> MutableBoolean(player.isCrouching) as T
        AnimationContext.Property.PlayerIsSprinting -> MutableBoolean(player.isSprinting) as T
        AnimationContext.Property.PlayerIsSwimming -> MutableBoolean(player.isSwimming) as T
        AnimationContext.Property.PlayerIsJumping -> MutableBoolean(!player.onGround()) as T
        AnimationContext.Property.PlayerIsFirstPerson -> MutableBoolean(false) as T
        AnimationContext.Property.PlayerIsSpectator -> MutableBoolean(player.isSpectator) as T

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
            AnimationContext.Property.PlayerHeadXRotation,
            AnimationContext.Property.PlayerHeadYRotation,
            AnimationContext.Property.PlayerBodyXRotation,
            AnimationContext.Property.PlayerBodyYRotation,
            AnimationContext.Property.PlayerIsSneaking,
            AnimationContext.Property.PlayerIsSprinting,
            AnimationContext.Property.PlayerIsSwimming,
            AnimationContext.Property.PlayerIsJumping,
            AnimationContext.Property.PlayerIsFirstPerson,
            AnimationContext.Property.PlayerIsSpectator,
        )
    }
}
