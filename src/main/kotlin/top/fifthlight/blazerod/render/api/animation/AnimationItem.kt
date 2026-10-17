package top.fifthlight.blazerod.render.api.animation

import top.fifthlight.blazerod.model.animation.Animation
import top.fifthlight.blazerod.model.animation.AnimationContext
import top.fifthlight.blazerod.model.animation.AnimationState
import top.fifthlight.blazerod.render.api.resource.ModelInstance
import top.fifthlight.blazerod.render.api.resource.RenderScene

interface AnimationItem {
    val name: String?
    val duration: Float?

    interface Factory {
        fun load(scene: RenderScene, animation: Animation): AnimationItem

        companion object : Factory {
            var impl: Factory? = null

            override fun load(scene: RenderScene, animation: Animation): AnimationItem =
                requireNotNull(impl) { "AnimationItem.Factory has no implementation" }.load(scene, animation)
        }
    }
}

interface AnimationItemPendingValues

interface AnimationItemInstance {
    fun createState(context: AnimationContext): AnimationState
    fun update(context: AnimationContext, state: AnimationState): AnimationItemPendingValues
    fun apply(instance: ModelInstance, pendingValues: AnimationItemPendingValues)

    interface Factory {
        fun of(animation: AnimationItem): AnimationItemInstance

        companion object : Factory {
            var impl: Factory? = null

            override fun of(animation: AnimationItem): AnimationItemInstance =
                requireNotNull(impl) { "AnimationItemInstance.Factory has no implementation" }.of(animation)
        }
    }
}

interface MaskableAnimationItemInstance {
    fun applyMasked(
        instance: ModelInstance,
        pendingValues: AnimationItemPendingValues,
        allowedNodeIndices: BooleanArray,
    )
}
