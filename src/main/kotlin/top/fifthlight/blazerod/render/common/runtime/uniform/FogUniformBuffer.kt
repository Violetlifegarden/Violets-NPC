package top.fifthlight.blazerod.render.common.runtime.uniform

import org.joml.Vector3f
import top.fifthlight.blazerod.gl.Gl
import top.fifthlight.blazerod.gl.GlbFrameContext
import top.fifthlight.blazerod.gl.GpuBufferSlice
import top.fifthlight.blazerod.render.common.layout.GpuDataLayout
import top.fifthlight.blazerod.render.common.layout.LayoutStrategy

/**
 * Stands in for the default `Fog` uniform block that 1.21.2's `RenderSystem.bindDefaultUniforms` fills
 * in. Left unbound the fog parameters read as zero and every fragment comes out fogged to black, so the
 * block has to be written on every pass like the projection one.
 */
object FogUniformBuffer : UniformBuffer<FogUniformBuffer, FogUniformBuffer.FogLayout>(
    name = "FogUniformBuffer",
) {
    override val layout: FogLayout
        get() = FogLayout

    object FogLayout : GpuDataLayout<FogLayout>() {
        override val strategy: LayoutStrategy
            get() = LayoutStrategy.Std140LayoutStrategy
        var fogColor by vec3()
        var fogShape by float()
        var fogEnvironmentalStart by float()
        var fogEnvironmentalEnd by float()
        var fogRenderDistanceStart by float()
        var fogRenderDistanceEnd by float()
    }

    private val color = Vector3f()

    fun writeFrom(frame: GlbFrameContext = Gl.frameContext): GpuBufferSlice = write {
        frame.fogColor(color)
        fogColor = color
        fogShape = frame.fogShape()
        val start = frame.fogStart()
        val end = frame.fogEnd()
        fogEnvironmentalStart = start
        fogEnvironmentalEnd = end
        // 1.21.1 has only one fog range, so the two 1.21.2 ranges are the same range here.
        fogRenderDistanceStart = start
        fogRenderDistanceEnd = end
    }
}
