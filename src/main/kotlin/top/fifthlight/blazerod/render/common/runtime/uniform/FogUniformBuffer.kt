package top.fifthlight.blazerod.render.common.runtime.uniform

import com.mojang.blaze3d.systems.RenderSystem
import org.joml.Vector3f
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

    fun writeFromRenderSystem(): GpuBufferSlice = write {
        val fogColor = RenderSystem.getShaderFogColor()
        color.set(fogColor[0], fogColor[1], fogColor[2])
        this.fogColor = color
        fogShape = RenderSystem.getShaderFogShape().index.toFloat()
        val start = RenderSystem.getShaderFogStart()
        val end = RenderSystem.getShaderFogEnd()
        fogEnvironmentalStart = start
        fogEnvironmentalEnd = end
        // 1.21.1 has only one fog range, so the two 1.21.2 ranges are the same range here.
        fogRenderDistanceStart = start
        fogRenderDistanceEnd = end
    }
}
