package top.fifthlight.blazerod.render.runtime

import top.fifthlight.blazerod.render.api.animation.AnimationItem
import top.fifthlight.blazerod.render.api.animation.AnimationItemInstance
import top.fifthlight.blazerod.render.api.loader.ModelLoader
import top.fifthlight.blazerod.render.api.render.Renderer
import top.fifthlight.blazerod.render.api.render.RendererTypeHolder
import top.fifthlight.blazerod.render.api.resource.ModelInstance
import top.fifthlight.blazerod.render.api.resource.RenderScene
import top.fifthlight.blazerod.model.animation.Animation
import top.fifthlight.blazerod.render.common.runtime.load.ModelLoaderImpl
import top.fifthlight.blazerod.render.runtime.animation.AnimationItemImpl
import top.fifthlight.blazerod.render.runtime.animation.AnimationItemInstanceImpl
import top.fifthlight.blazerod.render.runtime.renderer.RendererTypeHolderImpl
import top.fifthlight.blazerod.render.runtime.renderer.VertexShaderTransformRenderer

/**
 * Upstream wired the runtime implementations into the API factories with generated code; this port
 * registers them by hand. Call [register] once during mod construction, on the client.
 */
object BlazeRodRuntime :
    Renderer.Factory,
    RendererTypeHolder.Factory,
    ModelLoader.Factory,
    ModelInstance.Factory,
    AnimationItem.Factory,
    AnimationItemInstance.Factory {

    override fun createVertexShaderTransform(): Renderer<*, *> = VertexShaderTransformRenderer.create()

    override fun of(): RendererTypeHolder = RendererTypeHolderImpl

    override fun create(): ModelLoader = ModelLoaderImpl

    override fun of(scene: RenderScene): ModelInstance = ModelInstanceImpl(scene)

    override fun load(scene: RenderScene, animation: Animation): AnimationItem =
        AnimationItemImpl.load(scene, animation)

    override fun of(animation: AnimationItem): AnimationItemInstance =
        AnimationItemInstanceImpl(animation)

    fun register() {
        Renderer.Factory.impl = this
        RendererTypeHolder.Factory.impl = this
        ModelLoader.Factory.impl = this
        ModelInstance.Factory.impl = this
        AnimationItem.Factory.impl = this
        AnimationItemInstance.Factory.impl = this
    }
}
