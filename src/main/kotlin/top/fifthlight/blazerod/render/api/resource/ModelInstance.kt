package top.fifthlight.blazerod.render.api.resource

import org.joml.Matrix4f
import org.joml.Matrix4fc
import top.fifthlight.blazerod.model.NodeTransform
import top.fifthlight.blazerod.model.NodeTransformView
import top.fifthlight.blazerod.model.RgbaColor
import top.fifthlight.blazerod.model.TransformId
import top.fifthlight.blazerod.render.api.refcount.RefCount
import java.util.function.Consumer

interface ModelInstance : RefCount {
    val scene: RenderScene
    var lodDistance: Float

    fun clearTransform()
    fun setTransformMatrix(nodeIndex: Int, transformId: TransformId, matrix: Matrix4f)
    fun setTransformMatrix(nodeIndex: Int, transformId: TransformId, updater: Consumer<NodeTransform.Matrix>)
    fun setTransformMatrix(nodeIndex: Int, transformId: TransformId, updater: NodeTransform.Matrix.() -> Unit)
    fun setTransformDecomposed(nodeIndex: Int, transformId: TransformId, decomposed: NodeTransformView.Decomposed)
    fun setTransformDecomposed(nodeIndex: Int, transformId: TransformId, updater: Consumer<NodeTransform.Decomposed>)
    fun setTransformDecomposed(nodeIndex: Int, transformId: TransformId, updater: NodeTransform.Decomposed.() -> Unit)
    fun setTransformBedrock(nodeIndex: Int, transformId: TransformId, updater: NodeTransform.Bedrock.() -> Unit)
    fun setGroupWeight(morphedPrimitiveIndex: Int, targetGroupIndex: Int, weight: Float)

    fun copyNodeWorldTransform(nodeIndex: Int, dest: Matrix4f)
    fun getCameraTransform(index: Int): CameraTransform?

    fun updateRenderData(time: Float)

    fun createRenderTask(
        modelMatrix: Matrix4fc,
        light: Int,
        overlay: Int = 0,
        tint: RgbaColor = RgbaColor(1f, 1f, 1f, 1f),
    ): RenderTask

    /**
     * Upstream generated this factory with mergetools codegen; the implementation registers itself
     * here instead, so callers keep the same `ModelInstance.Factory.of(scene)` shape.
     */
    interface Factory {
        fun of(scene: RenderScene): ModelInstance

        companion object : Factory {
            var impl: Factory? = null

            override fun of(scene: RenderScene): ModelInstance =
                requireNotNull(impl) { "ModelInstance.Factory has no implementation" }.of(scene)
        }
    }
}
