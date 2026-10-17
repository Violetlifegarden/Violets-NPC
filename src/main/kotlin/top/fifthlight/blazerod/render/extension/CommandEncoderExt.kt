package top.fifthlight.blazerod.render.extension

import top.fifthlight.blazerod.gl.CommandEncoder
import top.fifthlight.blazerod.gl.GpuTexture
import top.fifthlight.blazerod.render.image.PixelSource

/** Uploads a whole decoded image; the shim's own entry point stays buffer based. */
fun CommandEncoder.writeToTexture(texture: GpuTexture, source: PixelSource) {
    writeToTexture(
        texture,
        source.pixels,
        source.format,
        0,
        0,
        0,
        0,
        source.width,
        source.height,
    )
}
