package top.fifthlight.blazerod.render.runtime.renderer

import top.fifthlight.blazerod.gl.GlbId
import top.fifthlight.blazerod.gl.TextureFormat
import top.fifthlight.blazerod.gl.GpuBufferSlice
import top.fifthlight.blazerod.gl.BlendFunction
import top.fifthlight.blazerod.gl.RenderPipeline
import top.fifthlight.blazerod.gl.UniformType
import top.fifthlight.blazerod.gl.RenderPass
import top.fifthlight.blazerod.gl.Gl
import top.fifthlight.blazerod.gl.GpuTextureView
import top.fifthlight.blazerod.gl.GlbLightTexture
import it.unimi.dsi.fastutil.ints.Int2ReferenceAVLTreeMap
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap
import org.joml.Matrix4f
import org.joml.Vector2f
import org.joml.Vector2i
import top.fifthlight.blazerod.model.RgbaColor
import top.fifthlight.blazerod.model.times
import top.fifthlight.blazerod.render.common.runtime.data.MorphTargetBuffer
import top.fifthlight.blazerod.render.common.runtime.data.RenderSkinBuffer
import top.fifthlight.blazerod.render.common.runtime.uniform.FogUniformBuffer
import top.fifthlight.blazerod.render.common.runtime.uniform.InstanceDataUniformBuffer
import top.fifthlight.blazerod.render.common.runtime.uniform.LightingUniformBuffer
import top.fifthlight.blazerod.render.common.runtime.uniform.MorphDataUniformBuffer
import top.fifthlight.blazerod.render.common.runtime.uniform.PbrDataUniformBuffer
import top.fifthlight.blazerod.render.common.runtime.uniform.ProjectionUniformBuffer
import top.fifthlight.blazerod.render.common.runtime.uniform.SkinModelIndicesUniformBuffer
import top.fifthlight.blazerod.render.common.runtime.uniform.UnlitDataUniformBuffer
import top.fifthlight.blazerod.render.common.runtime.uniform.VanillaDataUniformBuffer
import top.fifthlight.blazerod.render.common.util.bitmap.BitmapItem
import top.fifthlight.blazerod.render.common.util.gpushaderpool.GpuShaderDataPool
import top.fifthlight.blazerod.render.common.util.gpushaderpool.ofSsbo
import top.fifthlight.blazerod.render.common.util.gpushaderpool.ofTbo
import top.fifthlight.blazerod.render.common.util.gpushaderpool.upload
import top.fifthlight.blazerod.render.common.BlazeRod
import top.fifthlight.blazerod.render.api.render.Renderer
import top.fifthlight.blazerod.gl.GpuBuffer
import top.fifthlight.blazerod.render.runtime.RenderSceneImpl
import top.fifthlight.blazerod.render.runtime.RenderTaskImpl
import top.fifthlight.blazerod.render.runtime.node.component.PrimitiveComponent
import top.fifthlight.blazerod.render.runtime.resource.RenderMaterial
import top.fifthlight.blazerod.render.runtime.resource.RenderPrimitive
import java.util.*

class VertexShaderTransformRenderer private constructor() :
    TaskMapScheduledRenderer<VertexShaderTransformRenderer, VertexShaderTransformRenderer.Type>() {

    @Suppress("NOTHING_TO_INLINE")
    @JvmInline
    private value class PipelineInfo(val bitmap: BitmapItem = BitmapItem()) {
        constructor(
            doubleSided: Boolean = true,
            skinned: Boolean = false,
            instanced: Boolean = false,
            morphed: Boolean = false,
        ) : this(Unit.run {
            var item = BitmapItem()
            if (doubleSided) {
                item += ELEMENT_DOUBLE_SIDED
            }
            if (skinned) {
                item += ELEMENT_SKINNED
            }
            if (instanced) {
                item += ELEMENT_INSTANCED
            }
            if (morphed) {
                item += ELEMENT_MORPHED
            }
            item
        })

        constructor(material: RenderMaterial<*>, instanced: Boolean) : this(
            doubleSided = material.doubleSided,
            skinned = material.skinned,
            instanced = instanced,
            morphed = material.morphed
        )

        val doubleSided
            get() = ELEMENT_DOUBLE_SIDED in bitmap
        val skinned
            get() = ELEMENT_SKINNED in bitmap
        val instanced
            get() = ELEMENT_INSTANCED in bitmap
        val morphed
            get() = ELEMENT_MORPHED in bitmap

        fun nameSuffix() = buildString {
            if (doubleSided) {
                append("_cull")
            } else {
                append("_no_cull")
            }
            if (skinned) {
                append("_skinned")
            }
            if (instanced) {
                append("_instanced")
            }
            if (morphed) {
                append("_morphed")
            }
        }

        companion object {
            val ELEMENT_DOUBLE_SIDED = BitmapItem.Element.of(0)
            val ELEMENT_SKINNED = BitmapItem.Element.of(1)
            val ELEMENT_INSTANCED = BitmapItem.Element.of(2)
            val ELEMENT_MORPHED = BitmapItem.Element.of(3)
        }

        inline operator fun plus(element: BitmapItem.Element) =
            PipelineInfo(bitmap + element)

        inline operator fun minus(element: BitmapItem.Element) =
            PipelineInfo(bitmap - element)

        inline operator fun contains(element: BitmapItem.Element) =
            element in bitmap

        override fun toString(): String {
            return "PipelineInfo(doubleSided=$doubleSided, skinned=$skinned, instanced=$instanced, morphed=$morphed)"
        }
    }

    companion object Type : Renderer.Type<VertexShaderTransformRenderer, Type>() {
        override val id: String
            get() = "vertex_transform"
        override val isAvailable: Boolean
            get() = true
        override val supportScheduling: Boolean
            get() = true

        @JvmStatic
        override fun create() = VertexShaderTransformRenderer()

        private val useSsbo by lazy {
            val device = Gl.device
            device.supportSsbo && device.maxSsboInVertexShader >= 8
        }

        private val pipelineCache = mutableMapOf<RenderMaterial.Descriptor, Int2ReferenceMap<RenderPipeline>>()

        private fun getPipeline(material: RenderMaterial<*>, instanced: Boolean): RenderPipeline {
            val pipelineInfo = PipelineInfo(
                material = material,
                instanced = instanced,
            )
            val materialMap = pipelineCache.getOrPut(material.descriptor) { Int2ReferenceAVLTreeMap() }
            return materialMap.getOrPut(pipelineInfo.bitmap.inner) {
                RenderPipeline.builder().apply {
                    withCull(!pipelineInfo.doubleSided)
                    withUniform("Projection", UniformType.UNIFORM_BUFFER)
                    withUniform("Fog", UniformType.UNIFORM_BUFFER)
                    val useSsbo = Gl.device.supportSsbo
                    if (useSsbo) {
                        withShaderDefine("SUPPORT_SSBO")
                    }
                    if (pipelineInfo.morphed) {
                        withShaderDefine("MORPHED")
                        withShaderDefine("MAX_ENABLED_MORPH_TARGETS", BlazeRod.MAX_ENABLED_MORPH_TARGETS)
                        withUniform("MorphData", UniformType.UNIFORM_BUFFER)
                        if (useSsbo) {
                            withStorageBuffer("MorphPositionBlock")
                            withStorageBuffer("MorphColorBlock")
                            withStorageBuffer("MorphTexCoordBlock")
                            withStorageBuffer("MorphTargetIndicesData")
                            withStorageBuffer("MorphWeightsData")
                        } else {
                            withUniform("MorphPositionData", UniformType.TEXEL_BUFFER, TextureFormat.RGBA32F)
                            withUniform("MorphColorData", UniformType.TEXEL_BUFFER, TextureFormat.RGBA32F)
                            withUniform("MorphTexCoordData", UniformType.TEXEL_BUFFER, TextureFormat.RG32F)
                            withUniform("MorphTargetIndices", UniformType.TEXEL_BUFFER, TextureFormat.R32I)
                            withUniform("MorphWeights", UniformType.TEXEL_BUFFER, TextureFormat.R32F)
                        }
                    }
                    if (pipelineInfo.skinned) {
                        withShaderDefine("SKINNED")
                        withUniform("SkinModelIndices", UniformType.UNIFORM_BUFFER)
                        if (useSsbo) {
                            withStorageBuffer("JointsData")
                        } else {
                            withUniform("Joints", UniformType.TEXEL_BUFFER, TextureFormat.RGBA32F)
                        }
                    }
                    withUniform("InstanceData", UniformType.UNIFORM_BUFFER)
                    if (useSsbo) {
                        withStorageBuffer("LocalMatricesData")
                    } else {
                        withUniform("LocalMatrices", UniformType.TEXEL_BUFFER, TextureFormat.RGBA32F)
                    }
                    withShaderDefine("INSTANCE_SIZE", BlazeRod.INSTANCE_SIZE)
                    if (instanced) {
                        withShaderDefine("INSTANCED")
                    }
                    withVertexFormat(material.vertexFormat)

                    when (material) {
                        is RenderMaterial.Pbr -> {
                            withLocation(
                                GlbId.fromNamespaceAndPath(
                                    "blazerod",
                                    "pbr" + pipelineInfo.nameSuffix()
                                )
                            )

                            withVertexShader(GlbId.fromNamespaceAndPath("blazerod", "core/pbr"))
                            withFragmentShader(GlbId.fromNamespaceAndPath("blazerod", "core/pbr"))
                            withBlend(BlendFunction.TRANSLUCENT)
                            withSampler("SamplerBaseColor")
                            withSampler("SamplerLightMap")
                            withSampler("SamplerOverlay")
                            withSampler("SamplerMetallicRoughness")
                            withSampler("SamplerNormal")
                            withSampler("SamplerOcclusion")
                            withSampler("SamplerEmissive")
                            withUniform("PbrData", UniformType.UNIFORM_BUFFER)
                            withUniform("Lighting", UniformType.UNIFORM_BUFFER)
                        }

                        is RenderMaterial.Unlit -> {
                            withLocation(
                                GlbId.fromNamespaceAndPath(
                                    "blazerod",
                                    "unlit" + pipelineInfo.nameSuffix()
                                )
                            )

                            withVertexShader(GlbId.fromNamespaceAndPath("blazerod", "core/unlit"))
                            withFragmentShader(GlbId.fromNamespaceAndPath("blazerod", "core/unlit"))
                            withBlend(BlendFunction.TRANSLUCENT)
                            withSampler("SamplerBaseColor")
                            withSampler("SamplerLightMap")
                            withSampler("SamplerOverlay")
                            withUniform("UnlitData", UniformType.UNIFORM_BUFFER)
                        }

                        is RenderMaterial.Vanilla -> {
                            withLocation(
                                GlbId.fromNamespaceAndPath(
                                    "blazerod",
                                    "vanilla" + pipelineInfo.nameSuffix()
                                )
                            )

                            withVertexShader(GlbId.fromNamespaceAndPath("blazerod", "core/vanilla"))
                            withFragmentShader(GlbId.fromNamespaceAndPath("blazerod", "core/vanilla"))
                            withBlend(BlendFunction.TRANSLUCENT)
                            withSampler("SamplerBaseColor")
                            withSampler("SamplerLightMap")
                            withSampler("SamplerOverlay")
                            withUniform("VanillaData", UniformType.UNIFORM_BUFFER)
                            withUniform("Lighting", UniformType.UNIFORM_BUFFER)
                        }
                    }
                }.build()
            }
        }
    }

    override val type: Type
        get() = Type

    private val dataPool = if (useSsbo) {
        GpuShaderDataPool.ofSsbo()
    } else {
        GpuShaderDataPool.ofTbo()
    }

    private val lightVector = Vector2i()
    private val overlayVector = Vector2i()
    private val normalMatrix = Matrix4f()
    private val viewportSize = Vector2f()

    /** 1.21.1 has no `RenderSystem.bindDefaultUniforms`, so the projection block is filled in by hand. */
    private fun projectionUniform(colorFrameBuffer: GpuTextureView) = ProjectionUniformBuffer.write {
        projMat = Gl.frameContext.projectionMatrix()
        viewportSize.set(colorFrameBuffer.width.toFloat(), colorFrameBuffer.height.toFloat())
        screenSize = viewportSize
    }

    private fun RenderPass.bindMorphTargets(targets: RenderPrimitive.Targets) {
        if (Gl.device.supportSsbo) {
            setStorageBuffer("MorphPositionBlock", targets.position.slice!!)
            setStorageBuffer("MorphColorBlock", targets.color.slice!!)
            setStorageBuffer("MorphTexCoordBlock", targets.texCoord.slice!!)
        } else {
            setUniform(
                "MorphPositionData",
                targets.position.gpuBuffer ?: error("Morph position don't have a GPU buffer")
            )
            setUniform(
                "MorphColorData",
                targets.color.gpuBuffer ?: error("Morph color don't have a GPU buffer")
            )
            setUniform(
                "MorphTexCoordData",
                targets.texCoord.gpuBuffer ?: error("Morph texture coordinate don't have a GPU buffer")
            )
        }
    }

    override fun render(
        colorFrameBuffer: GpuTextureView,
        depthFrameBuffer: GpuTextureView?,
        scene: RenderSceneImpl,
        primitive: RenderPrimitive,
        primitiveIndex: Int,
        task: RenderTaskImpl,
        skinBuffer: RenderSkinBuffer?,
        targetBuffer: MorphTargetBuffer?,
    ) {
        if (!primitive.gpuComplete) {
            return
        }
        val device = Gl.device
        val commandEncoder = device.createCommandEncoder()
        val material = primitive.material
        var renderPass: RenderPass? = null
        val instanceDataUniformBufferSlice: GpuBufferSlice
        val localMatricesBufferSlice: GpuBufferSlice
        var skinModelIndicesBufferSlice: GpuBufferSlice? = null
        var skinJointBufferSlice: GpuBufferSlice? = null
        var morphDataUniformBufferSlice: GpuBufferSlice? = null
        var morphWeightsBufferSlice: GpuBufferSlice? = null
        var morphTargetIndicesBufferSlice: GpuBufferSlice? = null
        var unlitData: GpuBufferSlice? = null
        var vanillaData: GpuBufferSlice? = null
        var pbrData: GpuBufferSlice? = null

        try {
            instanceDataUniformBufferSlice = InstanceDataUniformBuffer.write {
                primitiveSize = scene.primitiveComponents.size
                this.primitiveIndex = primitiveIndex
                this.viewMatrix = Gl.frameContext.modelViewMatrix()
                this.modelMatrices[0] = task.modelMatrix
                this.modelNormalMatrices[0] = task.modelMatrix.normal(normalMatrix)
                lightVector.set(
                    task.light and (GlbLightTexture.FULL_BLOCK or 0xFF0F),
                    (task.light shr 16) and (GlbLightTexture.FULL_BLOCK or 0xFF0F)
                )
                overlayVector.set(
                    task.overlay and 0xFFFF,
                    (task.overlay shr 16) and 0xFFFF,
                )
                this.lightMapUvs[0] = lightVector
                this.overlayUvs[0] = overlayVector
            }
            localMatricesBufferSlice = dataPool.upload(task.localMatricesBuffer.content.buffer)
            skinBuffer?.let { skinBuffer ->
                skinModelIndicesBufferSlice = SkinModelIndicesUniformBuffer.write {
                    skinJoints = skinBuffer.jointSize
                }
                skinJointBufferSlice = dataPool.upload(skinBuffer.buffer)
            }
            targetBuffer?.let { targetBuffer ->
                primitive.targets?.let { targets ->
                    morphDataUniformBufferSlice = MorphDataUniformBuffer.write {
                        totalVertices = primitive.vertices
                        posTargets = targets.position.targetsCount
                        colorTargets = targets.color.targetsCount
                        texCoordTargets = targets.texCoord.targetsCount
                        totalTargets =
                            targets.position.targetsCount + targets.color.targetsCount + targets.texCoord.targetsCount
                    }
                }
                morphWeightsBufferSlice = dataPool.upload(targetBuffer.weightsBuffer)
                morphTargetIndicesBufferSlice = dataPool.upload(targetBuffer.indicesBuffer)
            }
            when (material) {
                is RenderMaterial.Pbr -> {
                    pbrData = PbrDataUniformBuffer.write {
                        baseColor = material.baseColor * task.tint
                        emissiveFactor = RgbaColor(
                            material.emissiveFactor.r,
                            material.emissiveFactor.g,
                            material.emissiveFactor.b,
                            1f,
                        )
                        metallicFactor = material.metallicFactor
                        roughnessFactor = material.roughnessFactor
                        normalScale = material.normalScale
                        occlusionStrength = material.occlusionStrength
                        flags = if (material.hasNormalTexture) {
                            PbrDataUniformBuffer.PbrDataLayout.FLAG_NORMAL_TEXTURE
                        } else {
                            0
                        }
                    }
                }

                is RenderMaterial.Unlit -> {
                    unlitData = UnlitDataUniformBuffer.write {
                        baseColor = material.baseColor * task.tint
                    }
                }

                is RenderMaterial.Vanilla -> {
                    vanillaData = VanillaDataUniformBuffer.write {
                        baseColor = material.baseColor * task.tint
                    }
                }
            }

            val pipeline = getPipeline(material = material, instanced = false)
            val projectionData = projectionUniform(colorFrameBuffer)
            val fogData = FogUniformBuffer.writeFrom()

            renderPass = commandEncoder.createRenderPass(
                { "BlazeRod render pass (non-instanced)" },
                colorFrameBuffer,
                OptionalInt.empty(),
                depthFrameBuffer,
                OptionalDouble.empty()
            )

            with(renderPass) {
                setPipeline(pipeline)
                setUniform("Projection", projectionData)
                setUniform("Fog", fogData)
                unlitData?.let {
                    setUniform("UnlitData", unlitData)
                }
                vanillaData?.let {
                    setUniform("VanillaData", vanillaData)
                }
                pbrData?.let {
                    setUniform("PbrData", it)
                }
                when (material) {
                    is RenderMaterial.Pbr -> {
                        bindSampler("SamplerBaseColor", material.baseColorTexture.view)
                        bindSampler("SamplerLightMap", Gl.samplers.lightMap)
                        bindSampler("SamplerOverlay", Gl.samplers.overlay)
                        bindSampler("SamplerMetallicRoughness", material.metallicRoughnessTexture.view)
                        bindSampler("SamplerNormal", material.normalTexture.view)
                        bindSampler("SamplerOcclusion", material.occlusionTexture.view)
                        bindSampler("SamplerEmissive", material.emissiveTexture.view)
                        setUniform("Lighting", LightingUniformBuffer.writeLevel())
                    }

                    is RenderMaterial.Unlit -> {
                        bindSampler("SamplerBaseColor", material.baseColorTexture.view)
                        bindSampler("SamplerLightMap", Gl.samplers.lightMap)
                        bindSampler("SamplerOverlay", Gl.samplers.overlay)
                    }

                    is RenderMaterial.Vanilla -> {
                        bindSampler("SamplerBaseColor", material.baseColorTexture.view)
                        bindSampler("SamplerLightMap", Gl.samplers.lightMap)
                        bindSampler("SamplerOverlay", Gl.samplers.overlay)
                        setUniform("Lighting", LightingUniformBuffer.writeLevel())
                    }
                }

                if (BlazeRod.debug) {
                    require(material.skinned == (skinBuffer != null)) {
                        "Primitive's skin data ${skinBuffer != null} and material skinned ${material.skinned} not matching"
                    }
                }
                setUniform("InstanceData", instanceDataUniformBufferSlice)
                if (device.supportSsbo) {
                    setStorageBuffer("LocalMatricesData", localMatricesBufferSlice)
                } else {
                    setUniform("LocalMatrices", localMatricesBufferSlice)
                }
                skinJointBufferSlice?.let { skinJointBuffer ->
                    if (device.supportSsbo) {
                        setStorageBuffer("JointsData", skinJointBuffer)
                    } else {
                        setUniform("Joints", skinJointBuffer)
                    }
                }
                skinModelIndicesBufferSlice?.let { skinModelIndices ->
                    setUniform("SkinModelIndices", skinModelIndices)
                }
                morphDataUniformBufferSlice?.let { morphDataUniformBuffer ->
                    setUniform("MorphData", morphDataUniformBuffer)
                }
                morphWeightsBufferSlice?.let { morphWeightsBuffer ->
                    if (device.supportSsbo) {
                        setStorageBuffer("MorphWeightsData", morphWeightsBuffer)
                    } else {
                        setUniform("MorphWeights", morphWeightsBuffer)
                    }
                }
                morphTargetIndicesBufferSlice?.let { morphTargetIndicesBuffer ->
                    if (device.supportSsbo) {
                        setStorageBuffer("MorphTargetIndicesData", morphTargetIndicesBuffer)
                    } else {
                        setUniform("MorphTargetIndices", morphTargetIndicesBuffer)
                    }
                }
                setVertexFormatMode(primitive.vertexFormatMode)
                setVertexBuffer(0, primitive.gpuVertexBuffer!!.inner)
                primitive.targets?.let { targets ->
                    bindMorphTargets(targets)
                }
                primitive.indexBuffer?.let { indices ->
                    setIndexBuffer(indices.buffer.inner, indices.type)
                    drawIndexed(0, 0, indices.length, 1)
                } ?: run {
                    draw(0, primitive.vertices)
                }
            }
        } finally {
            renderPass?.close()
        }
    }

    override fun renderInstanced(
        colorFrameBuffer: GpuTextureView,
        depthFrameBuffer: GpuTextureView?,
        tasks: List<RenderTaskImpl>,
        scene: RenderSceneImpl,
        component: PrimitiveComponent,
    ) {
        val primitive = component.primitive
        val material = primitive.material
        if (!primitive.gpuComplete) {
            return
        }

        val device = Gl.device
        val commandEncoder = device.createCommandEncoder()
        var renderPass: RenderPass? = null
        val instanceDataUniformBufferSlice: GpuBufferSlice
        val localMatricesBufferSlice: GpuBufferSlice
        var skinModelIndicesBufferSlice: GpuBufferSlice? = null
        var skinJointBufferSlice: GpuBufferSlice? = null
        var morphDataUniformBufferSlice: GpuBufferSlice? = null
        var morphWeightsBufferSlice: GpuBufferSlice? = null
        var morphTargetIndicesBufferSlice: GpuBufferSlice? = null
        var unlitData: GpuBufferSlice? = null
        var vanillaData: GpuBufferSlice? = null
        var pbrData: GpuBufferSlice? = null

        val firstTask = tasks.first()
        try {
            instanceDataUniformBufferSlice = InstanceDataUniformBuffer.write {
                primitiveSize = scene.primitiveComponents.size
                this.primitiveIndex = component.primitiveIndex
                this.viewMatrix = Gl.frameContext.modelViewMatrix()
                for ((index, task) in tasks.withIndex()) {
                    val light = task.light
                    val overlay = task.overlay
                    lightVector.set(
                        light and (GlbLightTexture.FULL_BLOCK or 0xFF0F),
                        (light shr 16) and (GlbLightTexture.FULL_BLOCK or 0xFF0F)
                    )
                    overlayVector.set(
                        overlay and 0xFFFF,
                        (overlay shr 16) and 0xFFFF,
                    )
                    this.lightMapUvs[index] = lightVector
                    this.overlayUvs[index] = overlayVector
                    this.modelMatrices[index] = task.modelMatrix
                    this.modelNormalMatrices[index] = task.modelMatrix.normal(normalMatrix)
                }
            }
            localMatricesBufferSlice = dataPool.upload(tasks.map { it.localMatricesBuffer.content.buffer })
            component.skinIndex?.let { skinIndex ->
                val firstSkinBuffer = firstTask.skinBuffer[skinIndex].content
                skinModelIndicesBufferSlice = SkinModelIndicesUniformBuffer.write {
                    skinJoints = firstSkinBuffer.jointSize
                }
                skinJointBufferSlice =
                    dataPool.upload(tasks.map { it.skinBuffer[skinIndex].content.buffer })
            }
            component.morphedPrimitiveIndex?.let { morphedPrimitiveIndex ->
                val targets = primitive.targets ?: error("Morphed primitive index was set but targets were not")
                morphDataUniformBufferSlice = MorphDataUniformBuffer.write {
                    totalVertices = primitive.vertices
                    posTargets = targets.position.targetsCount
                    colorTargets = targets.color.targetsCount
                    texCoordTargets = targets.texCoord.targetsCount
                    totalTargets =
                        targets.position.targetsCount + targets.color.targetsCount + targets.texCoord.targetsCount
                }
                morphWeightsBufferSlice =
                    dataPool.upload(tasks.map { it.morphTargetBuffer[morphedPrimitiveIndex].content.weightsBuffer })
                morphTargetIndicesBufferSlice =
                    dataPool.upload(tasks.map { it.morphTargetBuffer[morphedPrimitiveIndex].content.indicesBuffer })
            }
            when (material) {
                is RenderMaterial.Pbr -> {
                    pbrData = PbrDataUniformBuffer.write {
                        baseColor = material.baseColor
                        emissiveFactor = RgbaColor(
                            material.emissiveFactor.r,
                            material.emissiveFactor.g,
                            material.emissiveFactor.b,
                            1f,
                        )
                        metallicFactor = material.metallicFactor
                        roughnessFactor = material.roughnessFactor
                        normalScale = material.normalScale
                        occlusionStrength = material.occlusionStrength
                        flags = if (material.hasNormalTexture) {
                            PbrDataUniformBuffer.PbrDataLayout.FLAG_NORMAL_TEXTURE
                        } else {
                            0
                        }
                    }
                }

                is RenderMaterial.Unlit -> {
                    unlitData = UnlitDataUniformBuffer.write {
                        baseColor = material.baseColor
                    }
                }

                is RenderMaterial.Vanilla -> {
                    vanillaData = VanillaDataUniformBuffer.write {
                        baseColor = material.baseColor
                    }
                }
            }

            val pipeline = getPipeline(material = material, instanced = true)
            val projectionData = projectionUniform(colorFrameBuffer)
            val fogData = FogUniformBuffer.writeFrom()

            renderPass = commandEncoder.createRenderPass(
                { "BlazeRod render pass (instanced)" },
                colorFrameBuffer,
                OptionalInt.empty(),
                depthFrameBuffer,
                OptionalDouble.empty()
            )

            with(renderPass) {
                setPipeline(pipeline)
                setUniform("Projection", projectionData)
                setUniform("Fog", fogData)
                unlitData?.let {
                    setUniform("UnlitData", unlitData)
                }
                vanillaData?.let {
                    setUniform("VanillaData", vanillaData)
                }
                pbrData?.let {
                    setUniform("PbrData", it)
                }
                when (material) {
                    is RenderMaterial.Pbr -> {
                        bindSampler("SamplerBaseColor", material.baseColorTexture.view)
                        bindSampler("SamplerLightMap", Gl.samplers.lightMap)
                        bindSampler("SamplerOverlay", Gl.samplers.overlay)
                        bindSampler("SamplerMetallicRoughness", material.metallicRoughnessTexture.view)
                        bindSampler("SamplerNormal", material.normalTexture.view)
                        bindSampler("SamplerOcclusion", material.occlusionTexture.view)
                        bindSampler("SamplerEmissive", material.emissiveTexture.view)
                        setUniform("Lighting", LightingUniformBuffer.writeLevel())
                    }

                    is RenderMaterial.Unlit -> {
                        bindSampler("SamplerBaseColor", material.baseColorTexture.view)
                        bindSampler("SamplerLightMap", Gl.samplers.lightMap)
                        bindSampler("SamplerOverlay", Gl.samplers.overlay)
                    }

                    is RenderMaterial.Vanilla -> {
                        bindSampler("SamplerBaseColor", material.baseColorTexture.view)
                        bindSampler("SamplerLightMap", Gl.samplers.lightMap)
                        bindSampler("SamplerOverlay", Gl.samplers.overlay)
                        setUniform("Lighting", LightingUniformBuffer.writeLevel())
                    }
                }

                if (BlazeRod.debug) {
                    require(material.skinned == (component.skinIndex != null)) {
                        "Primitive's skin data and material skinned property not matching"
                    }
                }
                setUniform("InstanceData", instanceDataUniformBufferSlice)
                if (device.supportSsbo) {
                    setStorageBuffer("LocalMatricesData", localMatricesBufferSlice)
                } else {
                    setUniform("LocalMatrices", localMatricesBufferSlice)
                }
                skinJointBufferSlice?.let { skinJointBuffer ->
                    if (device.supportSsbo) {
                        setStorageBuffer("JointsData", skinJointBuffer)
                    } else {
                        setUniform("Joints", skinJointBuffer)
                    }
                }
                skinModelIndicesBufferSlice?.let { skinModelIndices ->
                    setUniform("SkinModelIndices", skinModelIndices)
                }
                morphDataUniformBufferSlice?.let { morphDataUniformBuffer ->
                    setUniform("MorphData", morphDataUniformBuffer)
                }
                morphWeightsBufferSlice?.let { morphWeightsBuffer ->
                    if (device.supportSsbo) {
                        setStorageBuffer("MorphWeightsData", morphWeightsBuffer)
                    } else {
                        setUniform("MorphWeights", morphWeightsBuffer)
                    }
                }
                morphTargetIndicesBufferSlice?.let { morphTargetIndicesBuffer ->
                    if (device.supportSsbo) {
                        setStorageBuffer("MorphTargetIndicesData", morphTargetIndicesBuffer)
                    } else {
                        setUniform("MorphTargetIndices", morphTargetIndicesBuffer)
                    }
                }
                setVertexFormatMode(primitive.vertexFormatMode)
                setVertexBuffer(0, primitive.gpuVertexBuffer!!.inner)
                primitive.targets?.let { targets ->
                    bindMorphTargets(targets)
                }
                primitive.indexBuffer?.let { indices ->
                    setIndexBuffer(indices.buffer.inner, indices.type)
                    drawIndexed(0, 0, indices.length, tasks.size)
                } ?: run {
                    draw(0, primitive.vertices, tasks.size)
                }
            }
        } finally {
            renderPass?.close()
        }
    }

    override fun rotate() {
        dataPool.rotate()
    }

    override fun close() {
        dataPool.close()
    }
}
