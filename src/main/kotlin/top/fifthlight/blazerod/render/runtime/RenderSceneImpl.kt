package top.fifthlight.blazerod.render.runtime

import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap
import top.fifthlight.blazerod.render.common.resource.RenderSkin
import top.fifthlight.blazerod.model.Camera
import top.fifthlight.blazerod.model.HumanoidTag
import top.fifthlight.blazerod.model.NodeId
import top.fifthlight.blazerod.model.NodeTransform
import top.fifthlight.blazerod.render.api.resource.RenderExpression
import top.fifthlight.blazerod.render.api.resource.RenderExpressionGroup
import top.fifthlight.blazerod.render.api.resource.RenderScene
import top.fifthlight.blazerod.render.common.util.refcount.AbstractRefCount
import top.fifthlight.blazerod.render.runtime.node.RenderNodeImpl
import top.fifthlight.blazerod.render.runtime.node.UpdatePhase
import top.fifthlight.blazerod.render.runtime.node.component.PrimitiveComponent
import top.fifthlight.blazerod.render.runtime.node.component.RenderNodeComponent
import top.fifthlight.blazerod.render.runtime.node.forEach

/**
 * Upstream also carries rigid-body physics and IK here; both depend on a Bullet/Jolt engine that this
 * port does not have, so only the transform/skin/morph/camera pipeline is kept.
 */
class RenderSceneImpl(
    override val rootNode: RenderNodeImpl,
    override val nodes: List<RenderNodeImpl>,
    val skins: List<RenderSkin>,
    override val expressions: List<RenderExpression>,
    override val expressionGroups: List<RenderExpressionGroup>,
    override val cameras: List<Camera>,
    override val renderTransform: NodeTransform?,
) : AbstractRefCount(), RenderScene {
    override val attachments: Map<Class<*>, Any> = emptyMap()

    override val typeId: String
        get() = "scene"

    private val sortedNodes: List<RenderNodeImpl>
    val primitiveComponents: List<PrimitiveComponent>
    val morphedPrimitiveComponents: List<PrimitiveComponent>
    override val nodeIdMap: Map<NodeId, RenderNodeImpl>
    override val nodeNameMap: Map<String, RenderNodeImpl>
    override val humanoidTagMap: Map<HumanoidTag, RenderNodeImpl>

    init {
        rootNode.increaseReferenceCount()
        val nodes = mutableListOf<RenderNodeImpl>()
        val primitiveComponents = mutableListOf<PrimitiveComponent>()
        val morphedPrimitives = Int2ReferenceOpenHashMap<PrimitiveComponent>()
        val nodeIdMap = mutableMapOf<NodeId, RenderNodeImpl>()
        val nodeNameMap = mutableMapOf<String, RenderNodeImpl>()
        val humanoidTagMap = mutableMapOf<HumanoidTag, RenderNodeImpl>()
        rootNode.forEach { node ->
            nodes.add(node)
            node.nodeId?.let { nodeIdMap.put(it, node) }
            node.nodeName?.let { nodeNameMap.put(it, node) }
            node.humanoidTags.forEach { humanoidTagMap[it] = node }
            node.getComponentsOfType(RenderNodeComponent.Type.Primitive).let { components ->
                primitiveComponents.addAll(components)
                for (component in components) {
                    component.morphedPrimitiveIndex?.let { index ->
                        if (morphedPrimitives.containsKey(index)) {
                            throw IllegalStateException("Duplicate morphed primitive index: $index")
                        }
                        morphedPrimitives.put(index, component)
                    }
                }
            }
        }
        this.sortedNodes = nodes
        this.primitiveComponents = primitiveComponents
        this.morphedPrimitiveComponents = (0 until morphedPrimitives.size).map {
            morphedPrimitives.get(it) ?: error("Morphed primitive index not found: $it")
        }
        this.nodeIdMap = nodeIdMap
        this.nodeNameMap = nodeNameMap
        this.humanoidTagMap = humanoidTagMap
    }

    private fun executePhase(instance: ModelInstanceImpl, phase: UpdatePhase) {
        for (node in sortedNodes) {
            node.update(phase, node, instance)
        }
    }

    fun updateRenderData(instance: ModelInstanceImpl, time: Float) {
        if (instance.modelData.undirtyNodeCount != nodes.size) {
            executePhase(instance, UpdatePhase.GlobalTransformPropagation)
            executePhase(instance, UpdatePhase.InfluenceTransformUpdate)
            executePhase(instance, UpdatePhase.GlobalTransformPropagation)
            executePhase(instance, UpdatePhase.RenderDataUpdate)
            executePhase(instance, UpdatePhase.CameraUpdate)
        }
    }

    internal fun attachToInstance(instance: ModelInstanceImpl) {
        executePhase(instance, UpdatePhase.GlobalTransformPropagation)
        executePhase(instance, UpdatePhase.InfluenceTransformUpdate)
        executePhase(instance, UpdatePhase.GlobalTransformPropagation)
        for (node in nodes) {
            for (component in node.components) {
                component.onAttached(instance, node)
            }
        }
    }

    override fun onClosed() {
        rootNode.decreaseReferenceCount()
    }
}
