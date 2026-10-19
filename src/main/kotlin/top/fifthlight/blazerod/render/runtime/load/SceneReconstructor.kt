package top.fifthlight.blazerod.render.common.runtime.load

import top.fifthlight.blazerod.render.common.BlazeRod
import top.fifthlight.blazerod.render.api.refcount.checkInUse
import top.fifthlight.blazerod.model.TransformId
import top.fifthlight.blazerod.render.runtime.RenderSceneImpl
import top.fifthlight.blazerod.render.runtime.node.RenderNodeImpl
import top.fifthlight.blazerod.render.runtime.node.component.CameraComponent
import top.fifthlight.blazerod.render.runtime.node.component.InfluenceSourceComponent
import top.fifthlight.blazerod.render.runtime.node.component.JointComponent
import top.fifthlight.blazerod.render.runtime.node.component.PrimitiveComponent
import top.fifthlight.blazerod.render.runtime.resource.RenderMaterial
import top.fifthlight.blazerod.render.runtime.resource.RenderPrimitive
import top.fifthlight.blazerod.render.runtime.resource.RenderTexture
import top.fifthlight.blazerod.model.Camera as ModelCamera

class SceneReconstructor private constructor(private val info: GpuLoadModelLoadInfo) {
    private val nodeIdToIndexMap = buildMap {
        info.nodes.forEachIndexed { index, node ->
            node.nodeId?.let { put(it, index) }
        }
    }

    private suspend fun loadTexture(
        textureInfo: MaterialLoadInfo.TextureInfo?,
        fallback: RenderTexture = RenderTexture.WHITE_RGBA_TEXTURE,
    ) = textureInfo?.let {
        info.textures[textureInfo.textureIndex].await()
    } ?: fallback

    private suspend fun loadMaterial(materialLoadInfo: MaterialLoadInfo) = when (materialLoadInfo) {
        is MaterialLoadInfo.Pbr -> RenderMaterial.Pbr(
            name = materialLoadInfo.name,
            baseColor = materialLoadInfo.baseColor,
            baseColorTexture = loadTexture(materialLoadInfo.baseColorTexture),
            metallicFactor = materialLoadInfo.metallicFactor,
            roughnessFactor = materialLoadInfo.roughnessFactor,
            metallicRoughnessTexture = loadTexture(materialLoadInfo.metallicRoughnessTexture),
            normalTexture = loadTexture(materialLoadInfo.normalTexture),
            hasNormalTexture = materialLoadInfo.normalTexture != null,
            normalScale = materialLoadInfo.normalScale,
            occlusionTexture = loadTexture(materialLoadInfo.occlusionTexture),
            occlusionStrength = materialLoadInfo.occlusionStrength,
            emissiveTexture = loadTexture(materialLoadInfo.emissiveTexture),
            emissiveFactor = materialLoadInfo.emissiveFactor,
            alphaMode = materialLoadInfo.alphaMode,
            alphaCutoff = materialLoadInfo.alphaCutoff,
            doubleSided = materialLoadInfo.doubleSided,
            skinned = materialLoadInfo.skinned,
            morphed = materialLoadInfo.morphed,
        )

        is MaterialLoadInfo.Unlit -> RenderMaterial.Unlit(
            name = materialLoadInfo.name,
            baseColor = materialLoadInfo.baseColor,
            baseColorTexture = loadTexture(materialLoadInfo.baseColorTexture),
            alphaMode = materialLoadInfo.alphaMode,
            alphaCutoff = materialLoadInfo.alphaCutoff,
            doubleSided = materialLoadInfo.doubleSided,
            skinned = materialLoadInfo.skinned,
            morphed = materialLoadInfo.morphed,
        )

        is MaterialLoadInfo.Vanilla -> RenderMaterial.Vanilla(
            name = materialLoadInfo.name,
            baseColor = materialLoadInfo.baseColor,
            baseColorTexture = loadTexture(materialLoadInfo.baseColorTexture),
            alphaMode = materialLoadInfo.alphaMode,
            alphaCutoff = materialLoadInfo.alphaCutoff,
            doubleSided = materialLoadInfo.doubleSided,
            skinned = materialLoadInfo.skinned,
            morphed = materialLoadInfo.morphed,
        )
    }

    private val cameras = mutableListOf<ModelCamera>()
    private suspend fun loadNode(
        index: Int,
        node: NodeLoadInfo,
    ) = RenderNodeImpl(
        nodeId = node.nodeId,
        nodeName = node.nodeName,
        humanoidTags = node.humanoidTags,
        nodeIndex = index,
        absoluteTransform = node.transform,
        components = node.components.mapNotNull { component ->
            when (component) {
                is NodeLoadInfo.Component.Primitive -> {
                    val primitiveInfo = info.primitiveInfos[component.infoIndex]
                    val vertexBuffer = info.vertexBuffers[primitiveInfo.vertexBufferIndex].await()
                    val indexBuffer = primitiveInfo.indexBufferIndex?.let { index -> info.indexBuffers[index].await() }
                    val material = primitiveInfo.materialInfo?.let { materialLoadInfo ->
                        loadMaterial(materialLoadInfo)
                    } ?: RenderMaterial.defaultMaterial
                    val targets =
                        primitiveInfo.morphedPrimitiveIndex?.let { index -> info.morphTargetInfos[index].await() }
                    PrimitiveComponent(
                        primitiveIndex = component.infoIndex,
                        primitive = RenderPrimitive(
                            vertices = primitiveInfo.vertices,
                            vertexFormatMode = primitiveInfo.vertexFormatMode,
                            gpuVertexBuffer = vertexBuffer.gpuBuffer,
                            cpuVertexBuffer = vertexBuffer.cpuBuffer,
                            indexBuffer = indexBuffer,
                            material = material,
                            targets = targets?.let {
                                RenderPrimitive.Targets(
                                    position = it.position,
                                    color = it.color,
                                    texCoord = it.texCoord,
                                )
                            },
                            targetGroups = targets?.targetGroups ?: listOf(),
                        ),
                        skinIndex = primitiveInfo.skinIndex,
                        morphedPrimitiveIndex = primitiveInfo.morphedPrimitiveIndex,
                    )
                }

                is NodeLoadInfo.Component.Joint -> {
                    JointComponent(
                        skinIndex = component.skinIndex,
                        jointIndex = component.jointIndex,
                    )
                }

                is NodeLoadInfo.Component.Camera -> {
                    val cameraIndex = cameras.size
                    cameras.add(component.camera)
                    CameraComponent(cameraIndex)
                }

                is NodeLoadInfo.Component.InfluenceSource -> {
                    val influence = component.influence
                    InfluenceSourceComponent(
                        targetNodeIndex = nodeIdToIndexMap[component.influence.target] ?: return@mapNotNull null,
                        influence = influence.influence,
                        influenceRotation = influence.influenceRotation,
                        influenceTranslation = influence.influenceTranslation,
                        appendLocal = influence.appendLocal,
                        target = TransformId.INFLUENCE,
                    )
                }
            }
        },
    )

    private suspend fun reconstruct(): RenderSceneImpl {
        val nodes = info.nodes.mapIndexed { index, node -> loadNode(index, node) }
        for ((index, node) in nodes.withIndex()) {
            val nodeInfo = info.nodes[index]
            node.initializeChildren(nodeInfo.childrenIndices.map { nodes[it] })
        }
        return RenderSceneImpl(
            rootNode = nodes[info.rootNodeIndex],
            nodes = nodes,
            skins = info.skins,
            expressions = info.expressions,
            expressionGroups = info.expressionGroups,
            cameras = cameras,
            renderTransform = info.renderTransform,
        )
    }

    companion object {
        suspend fun reconstruct(info: GpuLoadModelLoadInfo) = SceneReconstructor(info).reconstruct().also {
            if (BlazeRod.debug) {
                info.textures.forEach { it.await()?.checkInUse() }
                info.indexBuffers.forEach { it.await().checkInUse() }
                info.vertexBuffers.forEach { it.await().gpuBuffer?.checkInUse() }
            }
        }
    }
}
