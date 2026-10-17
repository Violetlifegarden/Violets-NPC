package top.fifthlight.blazerod.render.api.resource

import top.fifthlight.blazerod.model.Camera
import top.fifthlight.blazerod.model.HumanoidTag
import top.fifthlight.blazerod.model.NodeId
import top.fifthlight.blazerod.model.NodeTransform
import top.fifthlight.blazerod.render.api.refcount.RefCount

interface RenderScene : RefCount {
    val rootNode: RenderNode
    val nodes: List<RenderNode>
    val expressions: List<RenderExpression>
    val expressionGroups: List<RenderExpressionGroup>
    val cameras: List<Camera>

    val nodeIdMap: Map<NodeId, RenderNode>
    val nodeNameMap: Map<String, RenderNode>
    val humanoidTagMap: Map<HumanoidTag, RenderNode>

    val renderTransform: NodeTransform?

    val attachments: Map<Class<*>, Any>

}
