package top.fifthlight.blazerod.render.runtime.animation

import org.joml.Quaternionf
import org.joml.Vector3f
import top.fifthlight.blazerod.model.animation.AnimationContext
import top.fifthlight.blazerod.model.animation.AnimationState
import top.fifthlight.blazerod.render.api.animation.AnimationItemInstance
import top.fifthlight.blazerod.render.api.animation.AnimationItemPendingValues
import top.fifthlight.blazerod.render.api.resource.ModelInstance
import top.fifthlight.blazerod.render.runtime.ModelInstanceImpl
import kotlin.math.max
import kotlin.math.min

/**
 * Pending values of one transition frame: both endpoint clips' solved channel values plus the blend weight
 * (0 = pure source, 1 = pure target).
 */
class CrossfadeAnimationItemPendingValues(
    val sourcePending: AnimationItemPendingValues,
    val targetPending: AnimationItemPendingValues,
    var weight: Float,
) : AnimationItemPendingValues

/**
 * Advances both endpoint clips while tracking blend progress, so the outgoing clip keeps playing during the fade
 * instead of restarting or freezing. Also carries the fade length as its [duration] (the states' own durations stay
 * reachable through [sourceState]/[targetState]).
 */
class CrossfadeAnimationState(
    val sourceState: AnimationState,
    val targetState: AnimationState,
    context: AnimationContext,
    override val duration: Float,
) : AnimationState {
    var elapsedTime: Float = 0f
    private var prevGameTick: Long = context.getGameTick()
    private var prevDeltaTick: Float = context.getDeltaTick()

    override val playing: Boolean
        get() = true

    override fun updateTime(context: AnimationContext) {
        sourceState.updateTime(context)
        targetState.updateTime(context)

        val currentTick = context.getGameTick()
        val currentDelta = context.getDeltaTick()
        val timePassed = ((currentTick - prevGameTick).toFloat() + (currentDelta - prevDeltaTick)) *
            AnimationContext.SECONDS_PER_TICK

        prevGameTick = currentTick
        prevDeltaTick = currentDelta
        elapsedTime += timePassed
    }

    override fun getTime(): Float = elapsedTime
}

/**
 * Blends two animation instances over [duration] seconds. Lifted from blazerod 1.21.8 (LGPL v3+) and adapted to the
 * vendored runtime package.
 *
 * The transform layers compose multiplicatively and carry no per-layer weight, so blending has to happen on each
 * channel's solved value before `apply`: TRS channels interpolate (lerp/slerp), a channel only one side animates
 * blends against the identity pose, and non-TRS channels (morph / camera / bedrock) switch at the midpoint.
 *
 * Note this class is one-shot per state: [createState] starts both endpoint clips from their first frame. A caller
 * that wants phase continuity across a controller-driven switch (e.g. walk -> sprint) builds
 * [CrossfadeAnimationState] directly from the already-running source state instead.
 */
class CrossfadeAnimationItemInstance(
    val sourceInstance: AnimationItemInstanceImpl,
    val targetInstance: AnimationItemInstanceImpl,
    val duration: Float,
) : AnimationItemInstance {

    override fun createState(context: AnimationContext): AnimationState {
        val sourceState = sourceInstance.createState(context)
        val targetState = targetInstance.createState(context)
        return CrossfadeAnimationState(sourceState, targetState, context, duration)
    }

    override fun update(context: AnimationContext, state: AnimationState): AnimationItemPendingValues {
        val crossfadeState = state as CrossfadeAnimationState

        val sourcePending = sourceInstance.update(context, crossfadeState.sourceState)
        val targetPending = targetInstance.update(context, crossfadeState.targetState)

        val weight = min(1f, max(0f, crossfadeState.elapsedTime / crossfadeState.duration))

        return CrossfadeAnimationItemPendingValues(sourcePending, targetPending, weight)
    }

    @Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
    private inline fun <P : Any> AnimationChannelItem<*, *, P>.applyUnsafe(
        instance: ModelInstanceImpl,
        pendingValue: Any,
    ) = apply(instance, pendingValue as P)

    override fun apply(instance: ModelInstance, pendingValues: AnimationItemPendingValues) {
        val crossfadeValues = pendingValues as CrossfadeAnimationItemPendingValues
        val modelImpl = instance as ModelInstanceImpl

        val weight = crossfadeValues.weight

        // If the crossfade is complete, just apply the target animation directly
        if (weight >= 1f) {
            targetInstance.apply(modelImpl, crossfadeValues.targetPending)
            return
        }

        val sourcePendingImpl = crossfadeValues.sourcePending as AnimationItemPendingValuesImpl
        val targetPendingImpl = crossfadeValues.targetPending as AnimationItemPendingValuesImpl

        // Fast path: if weight is 0, just apply source
        if (weight <= 0f) {
            sourceInstance.apply(modelImpl, crossfadeValues.sourcePending)
            return
        }

        val sourceChannels = sourceInstance.animationItem.channels
        val targetChannels = targetInstance.animationItem.channels

        val sourceMap = HashMap<Pair<Int, Class<out AnimationChannelItem<*, *, *>>>, Any>()
        for (i in sourceChannels.indices) {
            val ch = sourceChannels[i]
            val nodeIdx = ch.targetNodeIndex
            if (nodeIdx != null) {
                sourceMap[Pair(nodeIdx, ch.javaClass)] = sourcePendingImpl.pendingValues[i]
            }
        }

        val appliedNodes = HashSet<Pair<Int, Class<out AnimationChannelItem<*, *, *>>>>()

        // Apply target channels (blending from source or identity)
        for (i in targetChannels.indices) {
            val tChannel = targetChannels[i]
            val nodeIdx = tChannel.targetNodeIndex ?: continue
            val key = Pair(nodeIdx, tChannel.javaClass)
            val tVal = targetPendingImpl.pendingValues[i]
            val sVal = sourceMap[key]

            appliedNodes.add(key)

            when (tChannel) {
                is AnimationChannelItem.TranslationItem -> {
                    val tVec = tVal as Vector3f
                    if (sVal != null) {
                        val sVec = sVal as Vector3f
                        tVec.lerp(sVec, 1f - weight) // if weight=1, tVec unchanged. if weight=0, tVec=sVec.
                    } else {
                        // interpolate from identity (0,0,0)
                        val sVec = Vector3f(0f, 0f, 0f)
                        tVec.lerp(sVec, 1f - weight)
                    }
                    tChannel.applyUnsafe(modelImpl, tVec)
                }
                is AnimationChannelItem.RotationItem -> {
                    val tQuat = tVal as Quaternionf
                    if (sVal != null) {
                        val sQuat = sVal as Quaternionf
                        tQuat.slerp(sQuat, 1f - weight)
                    } else {
                        // interpolate from identity
                        val sQuat = Quaternionf()
                        tQuat.slerp(sQuat, 1f - weight)
                    }
                    tChannel.applyUnsafe(modelImpl, tQuat)
                }
                is AnimationChannelItem.ScaleItem -> {
                    val tVec = tVal as Vector3f
                    if (sVal != null) {
                        val sVec = sVal as Vector3f
                        tVec.lerp(sVec, 1f - weight)
                    } else {
                        // interpolate from identity (1,1,1)
                        val sVec = Vector3f(1f, 1f, 1f)
                        tVec.lerp(sVec, 1f - weight)
                    }
                    tChannel.applyUnsafe(modelImpl, tVec)
                }
                else -> {
                    if (weight >= 0.5f) {
                        tChannel.applyUnsafe(modelImpl, tVal)
                    } else if (sVal != null) {
                        for (j in sourceChannels.indices) {
                            val sChannel = sourceChannels[j]
                            if (sChannel.targetNodeIndex == nodeIdx && sChannel.javaClass == tChannel.javaClass) {
                                sChannel.applyUnsafe(modelImpl, sVal)
                                break
                            }
                        }
                    }
                }
            }
        }

        // Apply remaining source channels (blending to identity)
        for (i in sourceChannels.indices) {
            val sChannel = sourceChannels[i]
            val nodeIdx = sChannel.targetNodeIndex ?: continue
            val key = Pair(nodeIdx, sChannel.javaClass)

            if (!appliedNodes.contains(key)) {
                val sVal = sourcePendingImpl.pendingValues[i]
                when (sChannel) {
                    is AnimationChannelItem.TranslationItem -> {
                        val sVec = sVal as Vector3f
                        val tVec = Vector3f(0f, 0f, 0f)
                        sVec.lerp(tVec, weight) // if weight=1, sVec=(0,0,0)
                        sChannel.applyUnsafe(modelImpl, sVec)
                    }
                    is AnimationChannelItem.RotationItem -> {
                        val sQuat = sVal as Quaternionf
                        val tQuat = Quaternionf()
                        sQuat.slerp(tQuat, weight)
                        sChannel.applyUnsafe(modelImpl, sQuat)
                    }
                    is AnimationChannelItem.ScaleItem -> {
                        val sVec = sVal as Vector3f
                        val tVec = Vector3f(1f, 1f, 1f)
                        sVec.lerp(tVec, weight)
                        sChannel.applyUnsafe(modelImpl, sVec)
                    }
                    else -> {
                        if (weight < 0.5f) {
                            sChannel.applyUnsafe(modelImpl, sVal)
                        }
                    }
                }
            }
        }
    }
}
