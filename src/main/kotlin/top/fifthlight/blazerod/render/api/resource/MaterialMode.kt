package top.fifthlight.blazerod.render.api.resource

/**
 * Per-task material override for primitives that carry load-time material alternatives.
 * Primitives without an alternative fall back to their own material.
 */
object MaterialMode {
    const val DEFAULT = 0
    const val VANILLA = 1
    const val UNLIT = 2
}
