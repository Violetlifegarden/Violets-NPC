package top.fifthlight.blazerod.render.runtime.node.component

import top.fifthlight.blazerod.render.common.util.refcount.AbstractRefCount
import top.fifthlight.blazerod.render.runtime.node.RenderNodeImpl
import top.fifthlight.blazerod.render.runtime.node.UpdatePhase
import top.fifthlight.blazerod.render.runtime.ModelInstanceImpl

sealed class RenderNodeComponent<C : RenderNodeComponent<C>> : AbstractRefCount() {
    override val typeId: String
        get() = "node"

    sealed class Type<C : RenderNodeComponent<C>> {
        object Primitive : Type<PrimitiveComponent>()
        object Joint : Type<JointComponent>()
        object InfluenceSource : Type<InfluenceSourceComponent>()
        object Camera : Type<CameraComponent>()
    }

    abstract val type: Type<C>

    open fun onAttached(instance: ModelInstanceImpl, node: RenderNodeImpl) {}

    abstract val updatePhases: List<UpdatePhase.Type>
    abstract fun update(phase: UpdatePhase, node: RenderNodeImpl, instance: ModelInstanceImpl)

    lateinit var node: RenderNodeImpl
        internal set
}

