package top.fifthlight.blazerod.model

import org.joml.Vector4f

data class RgbaColor(
    val r: Float,
    val g: Float,
    val b: Float,
    val a: Float,
) {
    constructor(rgba: FloatArray): this(rgba[0], rgba[1], rgba[2], rgba[3])
    constructor(rgbColor: RgbColor): this(rgbColor.r, rgbColor.g, rgbColor.b, 1f)
}

/**
 * Component-wise multiply, the per-instance tint primitive: a material's `baseColor` times a task's tint gives the
 * final vertex color. Identity for a white `(1,1,1,1)` tint, so an untinted draw is byte-identical to before.
 */
operator fun RgbaColor.times(other: RgbaColor): RgbaColor = RgbaColor(
    r * other.r,
    g * other.g,
    b * other.b,
    a * other.a,
)

/** Builds a tint from a packed `0xAARRGGBB` int (opaque white = `0xFFFFFFFF` = identity for [times]). */
fun rgbaFromArgb(argb: Int): RgbaColor = RgbaColor(
    r = ((argb shr 16) and 0xFF) / 255.0f,
    g = ((argb shr 8) and 0xFF) / 255.0f,
    b = (argb and 0xFF) / 255.0f,
    a = ((argb shr 24) and 0xFF) / 255.0f,
)

fun RgbaColor.toVector4f(vector4f: Vector4f): Vector4f = vector4f.set(r, g, b, a)

data class RgbColor(
    val r: Float,
    val g: Float,
    val b: Float,
) {
    constructor(rgba: FloatArray): this(rgba[0], rgba[1], rgba[2])
}
