package top.fifthlight.blazerod.render.api.render

import top.fifthlight.blazerod.gl.GpuTextureView
import top.fifthlight.blazerod.render.api.resource.RenderScene
import top.fifthlight.blazerod.render.api.resource.RenderTask

interface Renderer<R : Renderer<R, T>, T : Renderer.Type<R, T>> : AutoCloseable {
    abstract class Type<R : Renderer<R, T>, T : Type<R, T>> {
        abstract val id: String
        abstract val isAvailable: Boolean
        abstract val supportScheduling: Boolean
        abstract fun create(): R
    }

    val type: T

    fun render(
        colorFrameBuffer: GpuTextureView,
        depthFrameBuffer: GpuTextureView?,
        task: RenderTask,
        scene: RenderScene,
    )

    fun rotate()

    interface Factory {
        fun createVertexShaderTransform(): Renderer<*, *>

        companion object : Factory {
            var impl: Factory? = null

            private fun requireImpl() = requireNotNull(impl) { "Renderer.Factory has no implementation" }

            override fun createVertexShaderTransform() = requireImpl().createVertexShaderTransform()
        }
    }
}

/**
 * Upstream generated these `of()` factories with mergetools codegen; the implementation registers
 * itself into `impl`, so callers keep the same shape. 1.21.8's cpu and compute-shader renderers draw
 * through GPU abstractions 1.21.1 does not have (and a GLB model does not need), so only the
 * vertex-shader path is part of this port.
 */
interface RendererTypeHolder {
    val types: List<Renderer.Type<*, *>>

    val vertexShaderTransform: Renderer.Type<*, *>

    interface Factory {
        fun of(): RendererTypeHolder

        companion object : Factory {
            var impl: Factory? = null

            override fun of(): RendererTypeHolder =
                requireNotNull(impl) { "RendererTypeHolder.Factory has no implementation" }.of()
        }
    }

    companion object : RendererTypeHolder by RendererTypeHolder.Factory.of()
}

interface ScheduledRenderer<R : ScheduledRenderer<R, T>, T : Renderer.Type<R, T>> : Renderer<R, T> {
    fun schedule(task: RenderTask)

    fun executeTasks(
        colorFrameBuffer: GpuTextureView,
        depthFrameBuffer: GpuTextureView?,
    )
}
