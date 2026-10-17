package top.fifthlight.blazerod.gl

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL43
import org.lwjgl.opengl.GL44
import org.lwjgl.opengl.GL45
import org.lwjgl.system.MemoryUtil
import java.util.function.Supplier

/**
 * Own GPU backend for the ported BlazeRod renderer: Minecraft 1.21.1 has no
 * com.mojang.blaze3d.{buffers,textures,opengl} abstraction layer, so this package reimplements the
 * member names the ported code calls. Every entry point must run on the render thread.
 */
object Gl {
    var majorVersion = 0
        private set
    var minorVersion = 0
        private set

    val device by lazy { GpuDevice() }

    /**
     * Where `#moj_import <ns:path>` reads from. The classpath serves the offscreen self test; the
     * mod replaces this with Minecraft's resource pack loader before any shader is compiled.
     */
    var shaderSources: ShaderSourceProvider = ShaderSourceProvider.classpath()

    fun init() {
        if (majorVersion != 0) return
        val version = GL11.glGetString(GL11.GL_VERSION)
            ?: throw IllegalStateException("No OpenGL context on this thread")
        val match = Regex("(\\d+)\\.(\\d+)").find(version)
            ?: throw IllegalStateException("Unparseable GL version string: $version")
        majorVersion = match.groupValues[1].toInt()
        minorVersion = match.groupValues[2].toInt()
    }

    fun isAtLeast(major: Int, minor: Int = 0) =
        majorVersion > major || (majorVersion == major && minorVersion >= minor)

    val isSsboSupported: Boolean get() = isAtLeast(4, 3)
    val isBufferStorageSupported: Boolean get() = isAtLeast(4, 4)
    val isTextureBufferSupported: Boolean get() = isAtLeast(4, 3)
    val isComputeSupported: Boolean get() = isAtLeast(4, 3)
    val isMemoryBarrierSupported: Boolean get() = isAtLeast(4, 2)
    val isDebugLabelsSupported: Boolean get() = isAtLeast(4, 3)

    fun checkError(operation: String) {
        var error = GL11.glGetError()
        while (error != GL11.GL_NO_ERROR) {
            throw GLException(error, operation)
            error = GL11.glGetError()
        }
    }

    internal fun label(getter: Supplier<String>?): String? = getter?.get()

    internal fun setBufferLabel(id: Int, text: String?) {
        if (text != null && isDebugLabelsSupported) {
            GL43.glObjectLabel(GL43.GL_BUFFER, id, text)
        }
    }

    internal fun setTextureLabel(id: Int, text: String?) {
        if (text != null && isDebugLabelsSupported) {
            GL43.glObjectLabel(GL43.GL_TEXTURE, id, text)
        }
    }

    internal fun setProgramLabel(id: Int, text: String?) {
        if (text != null && isDebugLabelsSupported) {
            GL43.glObjectLabel(GL43.GL_PROGRAM, id, text)
        }
    }
}

class GLException(val code: Int, operation: String) :
    RuntimeException("OpenGL error 0x${code.toString(16)} during $operation")
