package top.fifthlight.blazerod.render.common.runtime.uniform

import top.fifthlight.blazerod.render.common.layout.GpuDataLayout
import top.fifthlight.blazerod.render.common.layout.LayoutStrategy

object PbrDataUniformBuffer : UniformBuffer<PbrDataUniformBuffer, PbrDataUniformBuffer.PbrDataLayout>(
    name = "PbrDataUniformBuffer",
) {
    override val layout: PbrDataLayout
        get() = PbrDataLayout

    object PbrDataLayout : GpuDataLayout<PbrDataLayout>() {
        override val strategy: LayoutStrategy
            get() = LayoutStrategy.Std140LayoutStrategy
        var baseColor by rgba()

        /** RGB used, A ignored; a separate vec4 keeps the std140 offsets predictable. */
        var emissiveFactor by rgba()
        var metallicFactor by float()
        var roughnessFactor by float()
        var normalScale by float()
        var occlusionStrength by float()

        /** Bit 0: a tangent-space normal map with a real sampler is bound. */
        var flags by int()

        val FLAG_NORMAL_TEXTURE = 1
    }
}
