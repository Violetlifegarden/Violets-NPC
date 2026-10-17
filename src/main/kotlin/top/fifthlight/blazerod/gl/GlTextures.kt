package top.fifthlight.blazerod.gl

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL42
import java.nio.ByteBuffer
import java.util.function.Supplier

/**
 * The subset of `com.mojang.blaze3d.textures` the ported renderer needs. The format list is BlazeRod's
 * own (1.21.2 has no float formats in `TextureFormat`, it injects them with a mixin), so they are plain
 * entries here.
 */
enum class TextureFormat(
    val internalFormat: Int,
    val externalFormat: Int,
    val externalType: Int,
    val componentCount: Int,
    /** Bytes per texel; 1.21.8 mixes this into Minecraft's `TextureFormat`, it is a plain entry here. */
    val pixelSize: Int,
) {
    RGBA8(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 4, 4),
    RGB8(GL11.GL_RGB8, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, 3, 3),
    R32F(GL30.GL_R32F, GL30.GL_RED, GL11.GL_FLOAT, 1, 4),
    RG32F(GL30.GL_RG32F, GL30.GL_RG, GL11.GL_FLOAT, 2, 8),
    RGB32F(GL30.GL_RGB32F, GL11.GL_RGB, GL11.GL_FLOAT, 3, 12),
    RGBA32F(GL30.GL_RGBA32F, GL11.GL_RGBA, GL11.GL_FLOAT, 4, 16),
    R32I(GL30.GL_R32I, GL30.GL_RED_INTEGER, GL11.GL_INT, 1, 4),
    DEPTH24(GL14.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT, 1, 4),
    DEPTH24_STENCIL8(GL30.GL_DEPTH24_STENCIL8, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, 2, 4),

    ;

    val isDepthFormat get() = this == DEPTH24 || this == DEPTH24_STENCIL8
}

enum class AddressMode {
    REPEAT,
    CLAMP_TO_EDGE,
    MIRRORED_REPEAT,

    ;

    internal val glValue: Int get() = when (this) {
        REPEAT -> GL11.GL_REPEAT
        CLAMP_TO_EDGE -> GL12.GL_CLAMP_TO_EDGE
        MIRRORED_REPEAT -> GL14.GL_MIRRORED_REPEAT
    }
}

enum class FilterMode {
    NEAREST,
    LINEAR,

    ;

    internal val glValue: Int get() = when (this) {
        NEAREST -> GL11.GL_NEAREST
        LINEAR -> GL11.GL_LINEAR
    }
}

class GpuTexture private constructor(
    val id: Int,
    val format: TextureFormat,
    val width: Int,
    val height: Int,
    val depth: Int,
    val levels: Int,
    private val usage: Int,
    private var label: String?,
    private val owned: Boolean = true,
) : AutoCloseable {
    fun usage(): Int = usage

    fun setAddressMode(minFilter: AddressMode, magFilter: AddressMode) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, minFilter.glValue)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, magFilter.glValue)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        Gl.checkError("setAddressMode ${label ?: "unnamed"}")
    }

    fun setTextureFilter(minFilter: FilterMode, magFilter: FilterMode, useMipmap: Boolean) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minFilter.glValue)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magFilter.glValue)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        Gl.checkError("setTextureFilter ${label ?: "unnamed"}")
    }

    fun createView(viewFormat: TextureFormat): GpuTextureView = GpuTextureView(this, viewFormat)

    fun rename(newLabel: String?) {
        label = newLabel
        Gl.setTextureLabel(id, newLabel)
    }

    override fun close() {
        // An adopted texture belongs to Minecraft, which deletes it itself.
        if (owned) {
            GL11.glDeleteTextures(id)
            Gl.checkError("delete texture ${label ?: "unnamed"}")
        }
    }

    companion object {
        const val USAGE_TEXTURE_BINDING = 1
        const val USAGE_COPY_DST = 1 shl 1
        const val USAGE_RENDER_TARGET = 1 shl 2

        /**
         * Wraps a texture name Minecraft created and still owns -- 1.21.1 keeps its lightmap and
         * overlay atlases as plain GL ids -- so our pipelines can sample them. The wrapper borrows the
         * id and its current size, and never deletes it.
         */
        fun adopt(id: Int, format: TextureFormat): GpuTexture {
            Gl.init()
            val size = IntArray(1)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
            GL11.glGetTexLevelParameteriv(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH, size)
            val width = size[0]
            GL11.glGetTexLevelParameteriv(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT, size)
            val height = size[0]
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            Gl.checkError("adopt texture $id")
            return GpuTexture(
                id = id,
                format = format,
                width = width,
                height = height,
                depth = 1,
                levels = 1,
                usage = USAGE_TEXTURE_BINDING,
                label = null,
                owned = false,
            )
        }

        internal fun create(
            labelGetter: Supplier<String>?,
            usage: Int,
            format: TextureFormat,
            width: Int,
            height: Int,
            depth: Int,
            levels: Int,
        ): GpuTexture {
            Gl.init()
            val name = Gl.label(labelGetter)
            val id = GL11.glGenTextures()
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
            val mipLevels = if (levels <= 0) 1 else levels
            if (Gl.isAtLeast(4, 2)) {
                GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, mipLevels, format.internalFormat, width, height)
            } else {
                GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D, 0, format.internalFormat, width, height, 0,
                    format.externalFormat, format.externalType, null as ByteBuffer?,
                )
            }
            // Nearest + clamp is MC's own default for model textures; callers override as needed.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            Gl.setTextureLabel(id, name)
            Gl.checkError("create texture ${name ?: "unnamed"}")
            return GpuTexture(id, format, width, height, depth, mipLevels, usage, name)
        }
    }
}

/**
 * 1.21.1 has no texture-view objects, so a view only carries the reinterpretation format that
 * `setUniform`/`bindSampler` need, plus the whole-texture range.
 */
class GpuTextureView internal constructor(
    val texture: GpuTexture,
    val format: TextureFormat,
) {
    val width get() = texture.width
    val height get() = texture.height

    internal fun bind(unit: Int) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.id)
    }
}
