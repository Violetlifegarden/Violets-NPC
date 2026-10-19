package top.fifthlight.blazerod.render.runtime.resource

import top.fifthlight.blazerod.gl.GpuBuffer
import top.fifthlight.blazerod.render.format.GlbPrimitiveMode
import top.fifthlight.blazerod.render.common.resource.MorphTargetGroup
import top.fifthlight.blazerod.render.api.resource.MaterialMode
import top.fifthlight.blazerod.render.common.util.refcount.AbstractRefCount
import top.fifthlight.blazerod.render.render.GpuIndexBuffer
import top.fifthlight.blazerod.render.render.RefCountedGpuBuffer
import java.nio.ByteBuffer

class RenderPrimitive(
    val vertices: Int,
    val vertexFormatMode: GlbPrimitiveMode,
    val gpuVertexBuffer: RefCountedGpuBuffer?,
    val cpuVertexBuffer: ByteBuffer?,
    val indexBuffer: GpuIndexBuffer?,
    val material: RenderMaterial<*>,
    /**
     * Alternative vanilla-lit material for the same primitive, built at load time from the PBR description.
     * PBR and Vanilla share the NORMAL-inclusive vertex format, so a task can swap between them at draw time
     * without rebuilding vertex data. Null for primitives whose primary material is not PBR.
     */
    val vanillaMaterial: RenderMaterial<*>? = null,
    /** Flat (unlit) alternative for a PBR primitive: the pre-lighting appearance, still using the same vertex data. */
    val unlitMaterial: RenderMaterial<*>? = null,
    val targets: Targets?,
    val targetGroups: List<MorphTargetGroup>,
) : AbstractRefCount() {
    override val typeId: String
        get() = "primitive"

    init {
        gpuVertexBuffer?.increaseReferenceCount()
        indexBuffer?.increaseReferenceCount()
        material.increaseReferenceCount()
        vanillaMaterial?.increaseReferenceCount()
        unlitMaterial?.increaseReferenceCount()
        if (targetGroups.isEmpty()) {
            require(targets == null) { "Empty target groups with non-empty targets" }
        } else {
            require(targets != null) { "Non-empty target groups with empty targets" }
        }
    }

    val gpuComplete = gpuVertexBuffer != null && targets?.gpuComplete != false
    val cpuComplete = cpuVertexBuffer != null && targets?.cpuComplete != false

    /** The material a task draws with for the requested mode, falling back to the primitive's own material. */
    fun materialFor(materialMode: Int): RenderMaterial<*> = when (materialMode) {
        MaterialMode.UNLIT -> unlitMaterial ?: material
        MaterialMode.VANILLA -> vanillaMaterial ?: material
        else -> material
    }

    class Target(
        val gpuBuffer: GpuBuffer?,
        val cpuBuffer: ByteBuffer?,
        val targetsCount: Int,
    ) : AutoCloseable {
        val slice = gpuBuffer?.slice()

        init {
            gpuBuffer?.let {
                val tbo = gpuBuffer.usage() and GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER != 0
                val ssbo = gpuBuffer.extraUsage and GpuBuffer.EXTRA_USAGE_STORAGE_BUFFER != 0
                require(tbo || ssbo) {
                    "RenderPrimitive's target should have buffer with usage USAGE_UNIFORM_TEXEL_BUFFER or EXTRA_USAGE_STORAGE_BUFFER"
                }
            }
        }

        override fun close() {
            gpuBuffer?.close()
        }
    }

    class Targets(
        val position: Target,
        val color: Target,
        val texCoord: Target,
    ) {
        val gpuComplete = position.gpuBuffer != null && color.gpuBuffer != null && texCoord.gpuBuffer != null
        val cpuComplete = position.cpuBuffer != null && color.cpuBuffer != null && texCoord.cpuBuffer != null
    }

    override fun onClosed() {
        gpuVertexBuffer?.decreaseReferenceCount()
        indexBuffer?.decreaseReferenceCount()
        material.decreaseReferenceCount()
        vanillaMaterial?.decreaseReferenceCount()
        unlitMaterial?.decreaseReferenceCount()
        targets?.apply {
            position.close()
            color.close()
            texCoord.close()
        }
    }
}
