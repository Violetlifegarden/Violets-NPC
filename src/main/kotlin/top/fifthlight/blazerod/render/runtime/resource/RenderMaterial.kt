package top.fifthlight.blazerod.render.runtime.resource

import top.fifthlight.blazerod.render.format.GlbVertexFormat
import top.fifthlight.blazerod.render.common.util.refcount.AbstractRefCount
import top.fifthlight.blazerod.model.Material.AlphaMode
import top.fifthlight.blazerod.model.Material.AlphaMode.OPAQUE
import top.fifthlight.blazerod.model.RgbColor
import top.fifthlight.blazerod.model.RgbaColor
import top.fifthlight.blazerod.render.format.GlbVertexFormats

sealed class RenderMaterial<Desc : RenderMaterial.Descriptor> : AbstractRefCount() {
    companion object {
        val defaultMaterial by lazy {
            // Increase reference count to avoid being closed
            Unlit(name = "Default").apply { increaseReferenceCount() }
        }
    }

    abstract val name: String?
    abstract val baseColor: RgbaColor
    abstract val baseColorTexture: RenderTexture?
    abstract val alphaMode: AlphaMode
    abstract val alphaCutoff: Float
    abstract val doubleSided: Boolean
    abstract val skinned: Boolean
    abstract val morphed: Boolean

    abstract val descriptor: Desc

    abstract class Descriptor(
        val id: Int,
        val name: String,
    ) {
        val typeId = "material_$name"
    }

    abstract val vertexFormat: GlbVertexFormat

    override val typeId: String
        get() = descriptor.typeId

    abstract override fun onClosed()

    class Pbr(
        override val name: String?,
        override val baseColor: RgbaColor = RgbaColor(1f, 1f, 1f, 1f),
        override val baseColorTexture: RenderTexture = RenderTexture.WHITE_RGBA_TEXTURE,
        val metallicFactor: Float = 1f,
        val roughnessFactor: Float = 1f,
        val metallicRoughnessTexture: RenderTexture = RenderTexture.WHITE_RGBA_TEXTURE,
        val normalTexture: RenderTexture = RenderTexture.WHITE_RGBA_TEXTURE,
        val hasNormalTexture: Boolean = false,
        val normalScale: Float = 1f,
        val occlusionTexture: RenderTexture = RenderTexture.WHITE_RGBA_TEXTURE,
        val occlusionStrength: Float = 1f,
        val emissiveTexture: RenderTexture = RenderTexture.WHITE_RGBA_TEXTURE,
        val emissiveFactor: RgbColor = RgbColor(0f, 0f, 0f),
        override val alphaMode: AlphaMode = OPAQUE,
        override val alphaCutoff: Float = .5f,
        override val doubleSided: Boolean,
        override val skinned: Boolean,
        override val morphed: Boolean,
    ) : RenderMaterial<Pbr.Descriptor>() {
        init {
            baseColorTexture.increaseReferenceCount()
            metallicRoughnessTexture.increaseReferenceCount()
            normalTexture.increaseReferenceCount()
            occlusionTexture.increaseReferenceCount()
            emissiveTexture.increaseReferenceCount()
        }

        override val descriptor
            get() = Descriptor

        override val vertexFormat: GlbVertexFormat
            get() = if (skinned) {
                GlbVertexFormats.POSITION_COLOR_TEXTURE_NORMAL_JOINT_WEIGHT
            } else {
                GlbVertexFormats.POSITION_COLOR_TEXTURE_NORMAL
            }

        override fun onClosed() {
            baseColorTexture.decreaseReferenceCount()
            metallicRoughnessTexture.decreaseReferenceCount()
            normalTexture.decreaseReferenceCount()
            occlusionTexture.decreaseReferenceCount()
            emissiveTexture.decreaseReferenceCount()
        }

        companion object Descriptor : RenderMaterial.Descriptor(
            id = 1,
            name = "pbr",
        )
    }

    class Unlit(
        override val name: String?,
        override val baseColor: RgbaColor = RgbaColor(1f, 1f, 1f, 1f),
        override val baseColorTexture: RenderTexture = RenderTexture.WHITE_RGBA_TEXTURE,
        override val alphaMode: AlphaMode = OPAQUE,
        override val alphaCutoff: Float = .5f,
        override val doubleSided: Boolean = false,
        override val skinned: Boolean = false,
        override val morphed: Boolean = false,
        /**
         * Use the NORMAL-inclusive vertex layouts. Needed when the shader ignores lighting but the vertex buffer
         * was laid out for a PBR material, since the two layouts differ in stride for skinned meshes.
         */
        val normalLayout: Boolean = false,
    ) : RenderMaterial<RenderMaterial.Descriptor>() {
        init {
            baseColorTexture.increaseReferenceCount()
        }

        override val descriptor: RenderMaterial.Descriptor
            get() = if (normalLayout) NormalLayoutDescriptor else Descriptor

        override val vertexFormat: GlbVertexFormat
            get() = when {
                normalLayout && skinned -> GlbVertexFormats.POSITION_COLOR_TEXTURE_NORMAL_JOINT_WEIGHT
                normalLayout -> GlbVertexFormats.POSITION_COLOR_TEXTURE_NORMAL
                skinned -> GlbVertexFormats.POSITION_COLOR_TEXTURE_JOINT_WEIGHT
                else -> GlbVertexFormats.POSITION_COLOR_TEXTURE
            }

        override fun onClosed() {
            baseColorTexture.decreaseReferenceCount()
        }

        companion object {
            object Descriptor : RenderMaterial.Descriptor(
                id = 0,
                name = "unlit",
            )

            object NormalLayoutDescriptor : RenderMaterial.Descriptor(
                id = 3,
                name = "unlit_normals",
            )
        }
    }


    class Vanilla(
        override val name: String?,
        override val baseColor: RgbaColor = RgbaColor(1f, 1f, 1f, 1f),
        override val baseColorTexture: RenderTexture = RenderTexture.WHITE_RGBA_TEXTURE,
        override val alphaMode: AlphaMode = OPAQUE,
        override val alphaCutoff: Float = .5f,
        override val doubleSided: Boolean = false,
        override val skinned: Boolean = false,
        override val morphed: Boolean = false,
    ) : RenderMaterial<Vanilla.Descriptor>() {
        init {
            baseColorTexture.increaseReferenceCount()
        }

        override val descriptor
            get() = Descriptor

        override val vertexFormat: GlbVertexFormat
            get() = if (skinned) {
                GlbVertexFormats.POSITION_COLOR_TEXTURE_NORMAL_JOINT_WEIGHT
            } else {
                GlbVertexFormats.POSITION_COLOR_TEXTURE_NORMAL
            }

        override fun onClosed() {
            baseColorTexture.decreaseReferenceCount()
        }

        companion object Descriptor : RenderMaterial.Descriptor(
            id = 2,
            name = "vanilla",
        )
    }
}
