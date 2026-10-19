package top.fifthlight.blazerod.render.api.render

import org.joml.Matrix4fc
import top.fifthlight.blazerod.gl.GlbOverlay
import top.fifthlight.blazerod.gl.GpuTextureView
import top.fifthlight.blazerod.model.RgbaColor
import top.fifthlight.blazerod.render.api.resource.MaterialMode
import top.fifthlight.blazerod.render.api.resource.ModelInstance

/**
 * The color and optional depth views a scene is drawn into. Consumers build one from whatever backend
 * they render against (the game's main render target, an offscreen framebuffer) and hand it to
 * [drawScene]; the core renderer stays agnostic of where those views came from.
 */
class GlbRenderTarget(
    val color: GpuTextureView,
    val depth: GpuTextureView? = null,
)

/**
 * Draws [instance]'s current pose into [target] as a scene placed by [modelMatrix], managing the
 * render-task lifetime. The caller is responsible for the two things the core cannot do without the game:
 * applying the pose first (`ModelInstance.setTransform*` + `updateRenderData`) and having the active
 * frame context (`Gl.frameContext`) point at the camera the [target] is composited against. This is the
 * public entry point every consumer - the player replacement today, entity renderers later - draws through.
 */
fun Renderer<*, *>.drawScene(
    instance: ModelInstance,
    target: GlbRenderTarget,
    modelMatrix: Matrix4fc,
    light: Int,
    overlay: Int = GlbOverlay.NO_OVERLAY,
    tint: RgbaColor = RgbaColor(1f, 1f, 1f, 1f),
    materialMode: Int = MaterialMode.DEFAULT,
) {
    val task = instance.createRenderTask(modelMatrix, light, overlay, tint, materialMode)
    try {
        render(target.color, target.depth, task, instance.scene)
    } finally {
        task.release()
    }
}
