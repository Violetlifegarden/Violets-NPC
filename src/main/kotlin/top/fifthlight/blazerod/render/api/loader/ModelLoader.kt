package top.fifthlight.blazerod.render.api.loader

import top.fifthlight.blazerod.model.Model
import top.fifthlight.blazerod.render.api.resource.RenderScene
import java.util.concurrent.CompletableFuture

interface ModelLoader {
    suspend fun loadModel(model: Model): RenderScene?

    fun loadModelAsFuture(model: Model): CompletableFuture<out RenderScene?>

    interface Factory {
        fun create(): ModelLoader

        companion object : Factory {
            var impl: Factory? = null

            override fun create(): ModelLoader =
                requireNotNull(impl) { "ModelLoader.Factory has no implementation" }.create()
        }
    }
}
