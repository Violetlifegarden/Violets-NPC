package top.fifthlight.blazerod.render.extension

import com.mojang.blaze3d.platform.NativeImage
import top.fifthlight.blazerod.gl.CommandEncoder
import top.fifthlight.blazerod.gl.GpuTexture

/** Uploads a whole decoded image; the shim's own entry point stays buffer based. */
fun CommandEncoder.writeToTexture(texture: GpuTexture, image: NativeImage) {
    writeToTexture(
        texture,
        NativeImageExt.pixels(image),
        image.format(),
        0,
        0,
        0,
        0,
        image.width,
        image.height,
    )
}
