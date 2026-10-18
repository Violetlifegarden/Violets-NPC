package top.fifthlight.blazerod.render.runtime

import org.joml.Matrix4f
import org.joml.Matrix4fc
import top.fifthlight.blazerod.render.common.resource.CameraTransformImpl
import top.fifthlight.blazerod.render.api.resource.ModelInstance
import top.fifthlight.blazerod.render.api.resource.RenderScene
import top.fifthlight.blazerod.render.common.runtime.data.LocalMatricesBuffer
import top.fifthlight.blazerod.render.common.runtime.data.MorphTargetBuffer
import top.fifthlight.blazerod.render.common.runtime.data.RenderSkinBuffer
import top.fifthlight.blazerod.render.common.util.cowbuffer.CowBuffer
import top.fifthlight.blazerod.render.common.util.cowbuffer.copy
import top.fifthlight.blazerod.render.common.util.iterator.mapToArray
import top.fifthlight.blazerod.render.common.util.refcount.AbstractRefCount
import top.fifthlight.blazerod.model.NodeTransform
import top.fifthlight.blazerod.model.NodeTransformView
import top.fifthlight.blazerod.model.RgbaColor
import top.fifthlight.blazerod.model.TransformId
import top.fifthlight.blazerod.render.runtime.node.RenderNodeImpl
import top.fifthlight.blazerod.render.runtime.node.TransformMap
import top.fifthlight.blazerod.render.runtime.node.UpdatePhase
import top.fifthlight.blazerod.render.runtime.node.component.RenderNodeComponent
import top.fifthlight.blazerod.render.runtime.node.markNodeTransformDirty
import java.util.function.Consumer
import kotlin.collections.mapIndexed

class ModelInstanceImpl(
    override val scene: RenderSceneImpl,
) : AbstractRefCount(), ModelInstance {
    constructor(scene: RenderScene) : this(scene as RenderSceneImpl)

    override val typeId: String
        get() = "model_instance"

    override var lodDistance: Float = 0f

    val modelData = ModelData(scene)

    init {
        scene.increaseReferenceCount()
        scene.attachToInstance(this)
        for (i in scene.nodes.indices) {
            updateNodeTransform(i)
        }
    }

    class ModelData(scene: RenderSceneImpl) : AutoCloseable {
        var undirtyNodeCount = 0
        var undirtyCameraCount = 0

        val transformMaps = scene.nodes.mapToArray { node ->
            TransformMap(node.absoluteTransform)
        }

        val transformDirty = Array(scene.nodes.size) { true }

        val worldTransforms = Array(scene.nodes.size) { Matrix4f() }
        val worldTransformsNoPhysics = Array(scene.nodes.size) { Matrix4f() }

        val localMatricesBuffer = run {
            val buffer = LocalMatricesBuffer(scene.primitiveComponents.size)
            buffer.clear()
            CowBuffer(buffer).also { it.increaseReferenceCount() }
        }

        val skinBuffers = scene.skins.mapIndexed { index, skin ->
            val skinBuffer = RenderSkinBuffer(skin.jointSize)
            skinBuffer.clear()
            CowBuffer(skinBuffer).also { it.increaseReferenceCount() }
        }

        val targetBuffers = scene.morphedPrimitiveComponents.mapIndexed { index, component ->
            val primitive = component.primitive
            val targets = primitive.targets!!
            val targetBuffers = MorphTargetBuffer(
                positionTargets = targets.position.targetsCount,
                colorTargets = targets.color.targetsCount,
                texCoordTargets = targets.texCoord.targetsCount,
            )
            for (targetGroup in primitive.targetGroups) {
                fun processGroup(index: Int?, channel: MorphTargetBuffer.WeightChannel, weight: Float) =
                    index?.let {
                        channel[index] = weight
                    }
                processGroup(targetGroup.position, targetBuffers.positionChannel, targetGroup.weight)
                processGroup(targetGroup.color, targetBuffers.colorChannel, targetGroup.weight)
                processGroup(targetGroup.texCoord, targetBuffers.texCoordChannel, targetGroup.weight)
            }
            CowBuffer(targetBuffers).also { it.increaseReferenceCount() }
        }

        val cameraTransforms = scene.cameras.map { CameraTransformImpl.of(it) }


        override fun close() {
            localMatricesBuffer.decreaseReferenceCount()
            skinBuffers.forEach { it.decreaseReferenceCount() }
            targetBuffers.forEach { it.decreaseReferenceCount() }
        }
    }

    override fun clearTransform() {
        modelData.undirtyNodeCount = 0
        modelData.undirtyCameraCount = 0
        for (i in scene.nodes.indices) {
            modelData.transformMaps[i].clearFrom(TransformId.ABSOLUTE.next)
            modelData.transformDirty[i] = true
        }
    }

    override fun setTransformMatrix(nodeIndex: Int, transformId: TransformId, matrix: Matrix4f) {
        markNodeTransformDirty(scene.nodes[nodeIndex])
        val transform = modelData.transformMaps[nodeIndex]
        transform.setMatrix(transformId, matrix)
    }

    override fun setTransformMatrix(
        nodeIndex: Int,
        transformId: TransformId,
        updater: Consumer<NodeTransform.Matrix>,
    ) = setTransformMatrix(nodeIndex, transformId) { updater.accept(this) }

    override fun setTransformMatrix(
        nodeIndex: Int,
        transformId: TransformId,
        updater: NodeTransform.Matrix.() -> Unit,
    ) {
        markNodeTransformDirty(scene.nodes[nodeIndex])
        val transform = modelData.transformMaps[nodeIndex]
        transform.updateMatrix(transformId, updater)
    }

    override fun setTransformDecomposed(
        nodeIndex: Int,
        transformId: TransformId,
        decomposed: NodeTransformView.Decomposed,
    ) {
        markNodeTransformDirty(scene.nodes[nodeIndex])
        val transform = modelData.transformMaps[nodeIndex]
        transform.setMatrix(transformId, decomposed)
    }

    override fun setTransformDecomposed(
        nodeIndex: Int,
        transformId: TransformId,
        updater: Consumer<NodeTransform.Decomposed>,
    ) = setTransformDecomposed(nodeIndex, transformId) { updater.accept(this) }

    override fun setTransformDecomposed(
        nodeIndex: Int,
        transformId: TransformId,
        updater: NodeTransform.Decomposed.() -> Unit,
    ) {
        markNodeTransformDirty(scene.nodes[nodeIndex])
        val transform = modelData.transformMaps[nodeIndex]
        transform.updateDecomposed(transformId, updater)
    }

    override fun setTransformBedrock(
        nodeIndex: Int,
        transformId: TransformId,
        updater: NodeTransform.Bedrock.() -> Unit,
    ) {
        markNodeTransformDirty(scene.nodes[nodeIndex])
        val transform = modelData.transformMaps[nodeIndex]
        transform.updateBedrock(transformId, updater)
    }

    override fun setGroupWeight(morphedPrimitiveIndex: Int, targetGroupIndex: Int, weight: Float) {
        val primitiveComponent = scene.morphedPrimitiveComponents[morphedPrimitiveIndex]
        val group = primitiveComponent.primitive.targetGroups[targetGroupIndex]
        val weightsIndex = requireNotNull(primitiveComponent.morphedPrimitiveIndex) {
            "Component $primitiveComponent don't have target? Check model loader"
        }
        val weights = modelData.targetBuffers[weightsIndex]
        weights.edit {
            group.position?.let { positionChannel[it] = weight }
            group.color?.let { colorChannel[it] = weight }
            group.texCoord?.let { texCoordChannel[it] = weight }
        }
    }

    override fun copyNodeWorldTransform(nodeIndex: Int, dest: Matrix4f) {
        modelData.worldTransforms[nodeIndex].get(dest)
    }

    override fun getCameraTransform(index: Int) = modelData.cameraTransforms.getOrNull(index)

    override fun updateRenderData(time: Float) {
        scene.updateRenderData(this, time)
    }

    internal fun updateNodeTransform(nodeIndex: Int) {
        val node = scene.nodes[nodeIndex]
        updateNodeTransform(node)
    }

    internal fun updateNodeTransform(node: RenderNodeImpl) {
        if (modelData.undirtyNodeCount == scene.nodes.size) {
            return
        }
        node.update(UpdatePhase.GlobalTransformPropagation, node, this)
        for (child in node.children) {
            updateNodeTransform(child)
        }
    }

    internal fun updateNodeTransformNoPhysics(node: RenderNodeImpl) {
        val nodeIndex = node.nodeIndex
        val localBase = modelData.transformMaps[nodeIndex].getSum(TransformId.EXTERNAL_PARENT_DEFORM)
        val parent = node.parent
        val dst = modelData.worldTransformsNoPhysics[nodeIndex]
        if (parent != null) {
            dst.set(modelData.worldTransformsNoPhysics[parent.nodeIndex]).mul(localBase)
        } else {
            dst.set(localBase)
        }
        for (child in node.children) {
            updateNodeTransformNoPhysics(child)
        }
    }

    internal fun updateWorldTransformsNoPhysics() {
        updateNodeTransformNoPhysics(scene.rootNode)
    }

    override fun createRenderTask(
        modelMatrix: Matrix4fc,
        light: Int,
        overlay: Int,
        tint: RgbaColor,
    ): RenderTaskImpl {
        return RenderTaskImpl(
            instance = this,
            modelMatrix = modelMatrix,
            light = light,
            overlay = overlay,
            tint = tint,
            localMatricesBuffer = modelData.localMatricesBuffer.copy(),
            skinBuffer = modelData.skinBuffers.copy(),
            morphTargetBuffer = modelData.targetBuffers.copy().also { buffer ->
                buffer.forEach {
                    it.content.uploadIndices()
                }
            },
        ).apply {
            scene.renderTransform?.matrix?.let {
                this.modelMatrix.mul(it)
            }
        }
    }

    override fun onClosed() {
        scene.decreaseReferenceCount()
        modelData.close()
    }
}
