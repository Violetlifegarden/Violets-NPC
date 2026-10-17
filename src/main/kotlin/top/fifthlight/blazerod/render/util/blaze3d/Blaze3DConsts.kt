package top.fifthlight.blazerod.render.util.blaze3d

import top.fifthlight.blazerod.render.format.GlbElementType
import top.fifthlight.blazerod.render.format.GlbPrimitiveMode
import top.fifthlight.blazerod.gl.AddressMode
import top.fifthlight.blazerod.gl.FilterMode
import top.fifthlight.blazerod.model.Accessor.ComponentType
import top.fifthlight.blazerod.model.Primitive
import top.fifthlight.blazerod.model.Texture

val Primitive.Mode.blaze3d: GlbPrimitiveMode?
    get() = when (this) {
        Primitive.Mode.POINTS -> null
        Primitive.Mode.LINE_STRIP -> GlbPrimitiveMode.LINE_STRIP
        Primitive.Mode.LINE_LOOP -> null
        Primitive.Mode.LINES -> GlbPrimitiveMode.LINES
        Primitive.Mode.TRIANGLES -> GlbPrimitiveMode.TRIANGLES
        Primitive.Mode.TRIANGLE_STRIP -> GlbPrimitiveMode.TRIANGLE_STRIP
        Primitive.Mode.TRIANGLE_FAN -> GlbPrimitiveMode.TRIANGLE_FAN
    }

val Primitive.Attributes.Key.usageName
    get() = when (this) {
        Primitive.Attributes.Key.POSITION -> "Position"
        Primitive.Attributes.Key.NORMAL -> "Normal"
        Primitive.Attributes.Key.TANGENT -> "Tangent"
        Primitive.Attributes.Key.TEXCOORD -> "UV0"
        Primitive.Attributes.Key.COLORS -> "Color"
        Primitive.Attributes.Key.JOINTS -> "Joint"
        Primitive.Attributes.Key.WEIGHTS -> "Weight"
    }

val ComponentType.blaze3d: GlbElementType
    get() = when (this) {
        ComponentType.BYTE -> GlbElementType.BYTE
        ComponentType.UNSIGNED_BYTE -> GlbElementType.UBYTE
        ComponentType.SHORT -> GlbElementType.SHORT
        ComponentType.UNSIGNED_SHORT -> GlbElementType.USHORT
        ComponentType.UNSIGNED_INT -> GlbElementType.UINT
        ComponentType.FLOAT -> GlbElementType.FLOAT
    }

val Texture.Sampler.WrapMode.blaze3d: AddressMode
    get() = when (this) {
        Texture.Sampler.WrapMode.REPEAT -> AddressMode.REPEAT
        Texture.Sampler.WrapMode.MIRRORED_REPEAT -> AddressMode.MIRRORED_REPEAT
        Texture.Sampler.WrapMode.CLAMP_TO_EDGE -> AddressMode.CLAMP_TO_EDGE
    }

val Texture.Sampler.MinFilter.blaze3d: FilterMode
    get() = when (this) {
        Texture.Sampler.MinFilter.NEAREST -> FilterMode.NEAREST
        Texture.Sampler.MinFilter.LINEAR -> FilterMode.LINEAR
        Texture.Sampler.MinFilter.NEAREST_MIPMAP_NEAREST -> FilterMode.NEAREST
        Texture.Sampler.MinFilter.LINEAR_MIPMAP_NEAREST -> FilterMode.LINEAR
        Texture.Sampler.MinFilter.NEAREST_MIPMAP_LINEAR -> FilterMode.NEAREST
        Texture.Sampler.MinFilter.LINEAR_MIPMAP_LINEAR -> FilterMode.LINEAR
    }

val Texture.Sampler.MinFilter.useMipmap
    get() = when (this) {
        Texture.Sampler.MinFilter.NEAREST_MIPMAP_NEAREST,
        Texture.Sampler.MinFilter.LINEAR_MIPMAP_NEAREST,
        Texture.Sampler.MinFilter.NEAREST_MIPMAP_LINEAR,
        Texture.Sampler.MinFilter.LINEAR_MIPMAP_LINEAR,
            -> true

        else -> false
    }

val Texture.Sampler.MagFilter.blaze3d: FilterMode
    get() = when (this) {
        Texture.Sampler.MagFilter.NEAREST -> FilterMode.NEAREST
        Texture.Sampler.MagFilter.LINEAR -> FilterMode.LINEAR
    }
