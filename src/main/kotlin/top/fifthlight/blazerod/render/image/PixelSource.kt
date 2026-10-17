package top.fifthlight.blazerod.render.image

import org.lwjgl.stb.STBImage
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import top.fifthlight.blazerod.gl.TextureFormat
import top.fifthlight.blazerod.model.Texture
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A decoded image's raw texels, decoupled from Minecraft's `NativeImage`. [pixels] is a direct buffer of
 * [width] x [height] texels in [format], tightly packed and positioned at 0. It owns that native memory and
 * frees it on [close], the way `NativeImage` used to; [close] is idempotent because a holder may both own the
 * source and be closed through a delegating wrapper.
 */
class PixelSource(
    val width: Int,
    val height: Int,
    val format: TextureFormat,
    val pixels: ByteBuffer,
) : AutoCloseable {
    private var released = false

    override fun close() {
        if (!released) {
            released = true
            MemoryUtil.memFree(pixels)
        }
    }
}

/**
 * GLB texture decoding, on stb directly. Minecraft's `NativeImage.read` validates a PNG signature before
 * handing the buffer to stb, so it cannot decode the JPEG a GLB may embed, and it keeps the texels behind a
 * private field that the upload path only reached through reflection. Producing a [PixelSource] here drops
 * both the format restriction and the reflection.
 */
object GlbImageDecoder {
    fun decode(
        expectedFormat: TextureFormat?,
        textureType: Texture.TextureType?,
        buffer: ByteBuffer,
    ): PixelSource {
        require(
            expectedFormat == null ||
                    expectedFormat == TextureFormat.RGBA8 ||
                    expectedFormat == TextureFormat.RGB8
        ) { "Don't know how to decode format $expectedFormat" }
        require(MemoryUtil.memAddress(buffer) != 0L) { "Invalid buffer" }
        textureType?.let { type ->
            require(buffer.remaining() >= type.magic.size) {
                "Bad image size: ${buffer.remaining()}, type: $type"
            }
            require(buffer.slice(0, type.magic.size).mismatch(ByteBuffer.wrap(type.magic)) == -1) {
                "Bad image magic for type $type"
            }
        }

        val format = expectedFormat ?: TextureFormat.RGBA8
        return MemoryStack.stackPush().use { memoryStack ->
            val width = memoryStack.mallocInt(1)
            val height = memoryStack.mallocInt(1)
            // stbi always wants these three out-pointers, only the channel count is decided by us.
            val channels = memoryStack.mallocInt(1)
            // Left to itself stb reports 1 or 2 channels for a grayscale image, and the sampler wants rgba,
            // so texels are widened to the requested count here, the way NativeImage.read did.
            val decoded = STBImage.stbi_load_from_memory(
                buffer,
                width,
                height,
                channels,
                format.componentCount,
            ) ?: throw IOException("Could not load image: ${STBImage.stbi_failure_reason()}")
            try {
                val w = width.get(0)
                val h = height.get(0)
                val size = w.toLong() * h * format.pixelSize
                val pixels = MemoryUtil.memAlloc(size.toInt())
                MemoryUtil.memCopy(MemoryUtil.memAddress(decoded), MemoryUtil.memAddress(pixels), size)
                PixelSource(w, h, format, pixels)
            } finally {
                STBImage.stbi_image_free(decoded)
            }
        }
    }
}
