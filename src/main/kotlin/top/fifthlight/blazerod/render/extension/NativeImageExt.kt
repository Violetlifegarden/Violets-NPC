package top.fifthlight.blazerod.render.extension

import com.mojang.blaze3d.platform.NativeImage
import org.lwjgl.stb.STBImage
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import top.fifthlight.blazerod.model.Texture
import java.io.IOException
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.nio.ByteBuffer

/**
 * GLB texture decoding.
 *
 * 1.21.1's `NativeImage.read` validates a PNG signature before handing the buffer to stb, so it cannot be
 * used for the JPEG images a GLB may embed, and it keeps the decoded texels behind a private constructor.
 * Decoding goes through stb directly and copies into a normally allocated image; [pixels] exposes the same
 * storage for the upload path.
 */
object NativeImageExt {
    fun read(pixelFormat: NativeImage.Format?, textureType: Texture.TextureType?, buffer: ByteBuffer): NativeImage {
        require(pixelFormat?.supportedByStb() != false) { "Don't know how to read format $pixelFormat" }
        require(MemoryUtil.memAddress(buffer) != 0L) { "Invalid buffer" }
        textureType?.let { type ->
            require(buffer.remaining() >= type.magic.size) {
                "Bad image size: ${buffer.remaining()}, type: $type"
            }
            require(buffer.slice(0, type.magic.size).mismatch(ByteBuffer.wrap(type.magic)) == -1) {
                "Bad image magic for type $type"
            }
        }

        return MemoryStack.stackPush().use { memoryStack ->
            val width = memoryStack.mallocInt(1)
            val height = memoryStack.mallocInt(1)
            // stbi always wants these three out-pointers, only the channel count is decided by us.
            val channels = memoryStack.mallocInt(1)
            // Left to itself stb reports 1 or 2 channels for a grayscale image, and the sampler wants rgba,
            // so texels are widened to 4 channels here, the way NativeImage.read does it.
            val decoded = STBImage.stbi_load_from_memory(
                buffer,
                width,
                height,
                channels,
                pixelFormat?.components() ?: 4,
            ) ?: throw IOException("Could not load image: ${STBImage.stbi_failure_reason()}")
            try {
                val format = pixelFormat ?: NativeImage.Format.RGBA
                NativeImage(format, width.get(0), height.get(0), false).also { image ->
                    MemoryUtil.memCopy(
                        MemoryUtil.memAddress(decoded),
                        pixelsAddress(image),
                        image.width.toLong() * image.height * format.components(),
                    )
                }
            } finally {
                STBImage.stbi_image_free(decoded)
            }
        }
    }

    /** The image texels, as the buffer the GPU upload path wants. */
    fun pixels(image: NativeImage): ByteBuffer {
        val address = pixelsAddress(image)
        // NativeImage.close() zeroes this field, so a zero address means the texels are already gone and an
        // upload from them would fault inside the driver.
        require(address != 0L) { "Texture ${image.width}x${image.height} is already closed" }
        val size = image.width.toLong() * image.height * image.format().components()
        return MemoryUtil.memByteBuffer(address, size.toInt())
    }

    private fun pixelsAddress(image: NativeImage): Long = pixelsHandle.invoke(image) as Long

    /**
     * Read reflectively instead of with a mixin accessor: the offscreen render harness runs the same code
     * without Mixin in front of it, and NeoForge keeps these names at runtime.
     */
    private val pixelsHandle: MethodHandle =
        MethodHandles.privateLookupIn(NativeImage::class.java, MethodHandles.lookup())
            .findGetter(NativeImage::class.java, "pixels", java.lang.Long.TYPE)
}
