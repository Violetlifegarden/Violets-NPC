package top.fifthlight.blazerod.render.runtime.node.component

import org.joml.Matrix4f
import top.fifthlight.blazerod.render.runtime.ModelInstanceImpl
import top.fifthlight.blazerod.render.runtime.node.RenderNodeImpl
import top.fifthlight.blazerod.render.runtime.node.UpdatePhase
import top.fifthlight.blazerod.render.runtime.node.getWorldTransform

class JointComponent(
    val skinIndex: Int,
    val jointIndex: Int,
) : RenderNodeComponent<JointComponent>() {
    override fun onClosed() {}

    override val type: Type<JointComponent>
        get() = Type.Joint

    companion object {
        private val updatePhases = listOf(UpdatePhase.Type.RENDER_DATA_UPDATE)
    }

    override val updatePhases
        get() = Companion.updatePhases

    private val cacheMatrix = Matrix4f()

    override fun update(phase: UpdatePhase, node: RenderNodeImpl, instance: ModelInstanceImpl) {
        when (phase) {
            is UpdatePhase.RenderDataUpdate -> {
                val cacheMatrix = cacheMatrix
                cacheMatrix.set(instance.getWorldTransform(node))
                val skin = instance.scene.skins[skinIndex]
                val skinBuffer = instance.modelData.skinBuffers[skinIndex]
                val inverseMatrix = skin.inverseBindMatrices?.get(jointIndex)
                skinBuffer.edit {
                    inverseMatrix?.let { cacheMatrix.mul(it) }
                    setMatrix(jointIndex, cacheMatrix)
                }
            }

            else -> {}
        }
    }
}
