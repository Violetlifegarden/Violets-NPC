package top.fifthlight.blazerod.gl

/**
 * Overlay atlas coordinates the shaders fetch (`SamplerOverlay`), mirroring vanilla's `OverlayTexture`. The value
 * packs `u | v << 16`, exactly like `OverlayTexture.pack`.
 *
 * [NO_OVERLAY] is vanilla's `OverlayTexture.NO_OVERLAY` (`u = 0`, `v = 10`): in that atlas, rows 8-15 are a white
 * gradient whose column 0 is fully opaque, so sampling it and mixing leaves the color unchanged. (Zero is NOT
 * neutral — `(0, 0)` lands in the red damage-tint rows, which is why this constant exists.)
 */
object GlbOverlay {
    const val NO_OVERLAY = 0x000A0000

    /** Packs a `u`/`v` texel index pair into the per-instance overlay value. */
    fun pack(u: Int, v: Int): Int = (u and 0xFFFF) or ((v and 0xFFFF) shl 16)
}
