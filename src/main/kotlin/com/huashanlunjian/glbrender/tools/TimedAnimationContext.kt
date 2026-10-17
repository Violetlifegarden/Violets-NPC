package com.huashanlunjian.glbrender.tools

import top.fifthlight.blazerod.model.animation.AnimationContext
import top.fifthlight.blazerod.model.util.MutableFloat
import top.fifthlight.blazerod.model.util.MutableLong

/**
 * A clock-only [AnimationContext] for the offscreen probe: [SimpleAnimationState] advances purely from the
 * game tick, so stepping [tick] is enough to scrub a clip without Minecraft or a player being present.
 */
class TimedAnimationContext : AnimationContext {
    var tick: Long = 0L

    override fun getGameTick(): Long = tick

    override fun getDeltaTick(): Float = 0f

    @Suppress("UNCHECKED_CAST")
    override fun <T> getProperty(type: AnimationContext.Property<T>): T? = when (type) {
        AnimationContext.Property.GameTick -> MutableLong(tick)
        AnimationContext.Property.DeltaTick -> MutableFloat(0f)
        else -> null
    } as T

    override fun getPropertyTypes(): Set<AnimationContext.Property<*>> = setOf(
        AnimationContext.Property.GameTick,
        AnimationContext.Property.DeltaTick,
    )
}
