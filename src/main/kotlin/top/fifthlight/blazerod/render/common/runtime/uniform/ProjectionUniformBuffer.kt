package top.fifthlight.blazerod.render.common.runtime.uniform

import top.fifthlight.blazerod.render.common.layout.GpuDataLayout
import top.fifthlight.blazerod.render.common.layout.LayoutStrategy

/**
 * Stands in for the default `Projection` uniform block that 1.21.2's `RenderSystem.bindDefaultUniforms`
 * fills in; 1.21.1 has no such call, so the port writes the block itself.
 */
object ProjectionUniformBuffer :
    UniformBuffer<ProjectionUniformBuffer, ProjectionUniformBuffer.ProjectionLayout>(
        name = "ProjectionUniformBuffer",
    ) {
    override val layout: ProjectionLayout
        get() = ProjectionLayout

    object ProjectionLayout : GpuDataLayout<ProjectionLayout>() {
        override val strategy: LayoutStrategy
            get() = LayoutStrategy.Std140LayoutStrategy
        var projMat by mat4()
        var screenSize by vec2()
    }
}
