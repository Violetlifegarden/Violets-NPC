package top.fifthlight.blazerod.render.common.runtime.uniform

import org.joml.Vector3f
import top.fifthlight.blazerod.render.common.layout.GpuDataLayout
import top.fifthlight.blazerod.render.common.layout.LayoutStrategy

/**
 * Stands in for the `Lighting` uniform block that 1.21.2's `RenderSystem.bindDefaultUniforms` fills
 * from the shader light directions. 1.21.1 has no such call and `RenderSystem` keeps the directions
 * private, so the port writes the overworld level directions from `Lighting.DIFFUSE_LIGHT_0/1`.
 */
object LightingUniformBuffer : UniformBuffer<LightingUniformBuffer, LightingUniformBuffer.LightingLayout>(
    name = "LightingUniformBuffer",
) {
    override val layout: LightingLayout
        get() = LightingLayout

    object LightingLayout : GpuDataLayout<LightingLayout>() {
        override val strategy: LayoutStrategy
            get() = LayoutStrategy.Std140LayoutStrategy
        var light0 by vec3()
        var light1 by vec3()
    }

    private val diffuseLight0 = Vector3f(0.2f, 1f, -0.7f).normalize()
    private val diffuseLight1 = Vector3f(-0.2f, 1f, 0.7f).normalize()

    fun writeLevel() = write {
        light0 = diffuseLight0
        light1 = diffuseLight1
    }
}
