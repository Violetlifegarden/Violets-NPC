package top.fifthlight.blazerod.model.loader

import top.fifthlight.blazerod.model.Texture

data class LoadParam @JvmOverloads constructor(
    val samplerMagFilter: Texture.Sampler.MagFilter? = null,
    val samplerMinFilter: Texture.Sampler.MinFilter? = null,
    val loaderParams: Map<String, Any> = mapOf(),
)
