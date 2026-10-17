package com.huashanlunjian.glbrender.tools

import top.fifthlight.blazerod.model.Node
import top.fifthlight.blazerod.model.NodeComponent
import top.fifthlight.blazerod.model.forEach
import top.fifthlight.blazerod.model.loader.LoadContext
import top.fifthlight.blazerod.model.loader.ModelFileLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.io.path.extension
import kotlin.io.path.inputStream

/**
 * Runs the ported model layer outside Minecraft, so GLB parsing failures can be told apart
 * from renderer failures. Usage: gradlew glbDump -Pmodel=path/to/model.glb
 */
fun main(args: Array<String>) {
    val path = Path.of(args[0])
    println("path        = ${path.toAbsolutePath()}")

    val loaders = ServiceLoader.load(ModelFileLoader::class.java).toList()
    check(loaders.isNotEmpty()) { "No ModelFileLoader registered in META-INF/services" }

    val extension = path.extension.lowercase()
    val loader = loaders.firstOrNull { extension in it.extensions.keys }
        ?: error("No loader for .$extension, registered: ${loaders.flatMap { it.extensions.keys }}")

    val header = ByteBuffer.wrap(path.inputStream().use { it.readNBytes(loader.probeLength ?: 4) })
        .order(ByteOrder.LITTLE_ENDIAN)
    println("probe       = ${loader.javaClass.simpleName} -> ${loader.probe(header)}")

    val result = loader.load(path, LoadContext.File(path.parent))
    val model = result.model ?: error("Loader returned no model: $result")

    val scene = model.defaultScene ?: model.scenes.first()
    val nodes = mutableListOf<Node>()
    // Explicit loop: List.forEach (stdlib) would shadow the recursive Node.forEach extension.
    for (root in scene.nodes) root.forEach { nodes += it }

    println("scenes      = ${model.scenes.size}")
    println("nodes       = ${nodes.size}")
    println("skins       = ${model.skins.size}")
    println("expressions = ${model.expressions.size}")
    println("physicalJoints = ${model.physicalJoints.size}")
    println(
        "components  = " + nodes.flatMap { it.components }
            .groupingBy { it.type }
            .eachCount()
    )

    val meshes = nodes.flatMap { it.components }
        .filterIsInstance<NodeComponent.MeshComponent>()
        .map { it.mesh }
    var totalVertices = 0
    var totalTriangles = 0
    var morphedPrimitives = 0
    var skinnedPrimitives = 0
    meshes.forEachIndexed { meshIndex, mesh ->
        mesh.primitives.forEachIndexed { primitiveIndex, primitive ->
            val position = primitive.attributes.position
            val indices = primitive.indices?.count ?: 0
            totalVertices += position.count
            totalTriangles += indices / 3
            if (primitive.targets.isNotEmpty()) morphedPrimitives += 1
            if (primitive.attributes.joints.isNotEmpty()) skinnedPrimitives += 1
            println(
                "  primitive mesh=$meshIndex/$primitiveIndex mode=${primitive.mode} " +
                        "vertices=${position.count} indices=$indices " +
                        "normals=${primitive.attributes.normal != null} " +
                        "tangents=${primitive.attributes.tangent != null} " +
                        "texcoords=${primitive.attributes.texcoords.size} " +
                        "morphTargets=${primitive.targets.size} " +
                        "material=${primitive.material?.javaClass?.simpleName}"
            )
        }
    }
    println("meshes      = ${meshes.size}")
    println("primitives  = vertices=$totalVertices triangles=$totalTriangles")
    println("skinned     = $skinnedPrimitives primitives, morphed = $morphedPrimitives primitives")

    model.skins.forEachIndexed { index, skin ->
        println("  skin $index ${skin.name}: joints=${skin.joints.size} " +
                "inverseBindMatrices=${skin.inverseBindMatrices?.size} humanoidTagged=" +
                (skin.jointHumanoidTags?.count { it != null } ?: 0))
    }

    // Console output mangles non-ASCII node names on Windows, so the joint tree goes to a UTF-8 file.
    val nodeById = nodes.associateBy { it.id }
    val jointLines = mutableListOf<String>()
    model.skins.forEachIndexed { index, skin ->
        val jointNodes = skin.joints.mapNotNull { nodeById[it] }
        val jointSet = jointNodes.toSet()
        jointLines += "skin $index (${jointNodes.size} joints):"
        jointNodes.forEachIndexed { jointIndex, joint ->
            val jointChildren = joint.children.filter { it in jointSet }.map { it.name }
            jointLines += "  [$jointIndex] ${joint.name} -> ${jointChildren}"
        }
    }
    val reportPath = path.resolveSibling(path.fileName.toString().substringBeforeLast('.') + "-joints.txt")
    java.nio.file.Files.write(reportPath, jointLines, java.nio.charset.StandardCharsets.UTF_8)
    println("jointsFile  = ${reportPath.toAbsolutePath()}")

    val animations = result.animations ?: emptyList()
    println("animations  = ${animations.size}")
    animations.forEach {
        println("  - ${it.name}: channels=${it.channels.size} duration=${it.duration}")
    }

    println("firstNodes  = ${nodes.mapNotNull { it.name }.take(12)}")
}
