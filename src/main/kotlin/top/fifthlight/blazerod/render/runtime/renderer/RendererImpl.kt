package top.fifthlight.blazerod.render.runtime.renderer

import top.fifthlight.blazerod.gl.GpuTextureView
import top.fifthlight.blazerod.render.api.resource.RenderScene
import top.fifthlight.blazerod.render.api.resource.RenderTask
import top.fifthlight.blazerod.render.common.runtime.data.MorphTargetBuffer
import top.fifthlight.blazerod.render.common.runtime.data.RenderSkinBuffer
import top.fifthlight.blazerod.render.api.render.Renderer
import top.fifthlight.blazerod.render.api.render.RendererTypeHolder
import top.fifthlight.blazerod.render.api.render.ScheduledRenderer
import top.fifthlight.blazerod.render.runtime.RenderSceneImpl
import top.fifthlight.blazerod.render.runtime.RenderTaskImpl
import top.fifthlight.blazerod.render.runtime.TaskMap
import top.fifthlight.blazerod.render.runtime.node.component.PrimitiveComponent
import top.fifthlight.blazerod.render.runtime.resource.RenderPrimitive

abstract class RendererImpl<R : RendererImpl<R, T>, T : Renderer.Type<R, T>> : Renderer<R, T> {
    override fun render(
        colorFrameBuffer: GpuTextureView,
        depthFrameBuffer: GpuTextureView?,
        task: RenderTask,
        scene: RenderScene,
    ) {
        val instance = (task as RenderTaskImpl).instance
        val scene = (scene as RenderSceneImpl)
        for (component in scene.primitiveComponents) {
            render(
                colorFrameBuffer = colorFrameBuffer,
                depthFrameBuffer = depthFrameBuffer,
                scene = scene,
                primitive = component.primitive,
                primitiveIndex = component.primitiveIndex,
                task = task,
                skinBuffer = component.skinIndex?.let {
                    (instance.modelData.skinBuffers.getOrNull(it)?.content
                        ?: error("Has skin but no skin buffer"))
                },
                targetBuffer = component.morphedPrimitiveIndex?.let {
                    (instance.modelData.targetBuffers.getOrNull(it)?.content
                        ?: error("Has morph target but no morph target buffer"))
                },
            )
        }
    }

    abstract fun render(
        colorFrameBuffer: GpuTextureView,
        depthFrameBuffer: GpuTextureView?,
        scene: RenderSceneImpl,
        primitive: RenderPrimitive,
        primitiveIndex: Int,
        task: RenderTaskImpl,
        skinBuffer: RenderSkinBuffer?,
        targetBuffer: MorphTargetBuffer?,
    )

    companion object
}

object RendererTypeHolderImpl : RendererTypeHolder {
    override val types = listOf(
        VertexShaderTransformRenderer.Type,
    )

    override val vertexShaderTransform: Renderer.Type<*, *>
        get() = VertexShaderTransformRenderer.Type
}

abstract class ScheduledRendererImpl<R, T : Renderer.Type<R, T>> : RendererImpl<R, T>(), ScheduledRenderer<R, T>
        where R : ScheduledRenderer<R, T>, R : RendererImpl<R, T>

abstract class TaskMapScheduledRenderer<R, T : Renderer.Type<R, T>> :
    ScheduledRendererImpl<R, T>()
        where R : ScheduledRenderer<R, T>, R : ScheduledRendererImpl<R, T> {
    private val taskMap = TaskMap()

    override fun schedule(task: RenderTask) = taskMap.addTask(task as RenderTaskImpl)

    override fun executeTasks(colorFrameBuffer: GpuTextureView, depthFrameBuffer: GpuTextureView?) {
        taskMap.executeTasks { scene, tasks ->
            when (tasks.size) {
                0 -> {}
                1 -> {
                    render(colorFrameBuffer, depthFrameBuffer, tasks[0], scene)
                }

                else -> {
                    for (component in scene.primitiveComponents) {
                        renderInstanced(
                            colorFrameBuffer = colorFrameBuffer,
                            depthFrameBuffer = depthFrameBuffer,
                            tasks = tasks,
                            scene = scene,
                            component = component,
                        )
                    }
                }
            }
        }
    }

    abstract fun renderInstanced(
        colorFrameBuffer: GpuTextureView,
        depthFrameBuffer: GpuTextureView?,
        tasks: List<RenderTaskImpl>,
        scene: RenderSceneImpl,
        component: PrimitiveComponent,
    )
}
