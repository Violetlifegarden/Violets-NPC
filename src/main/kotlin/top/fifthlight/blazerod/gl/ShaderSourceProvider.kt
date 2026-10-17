package top.fifthlight.blazerod.gl

import java.io.InputStreamReader

/**
 * Reads shader text by `namespace:path`, the way a `#moj_import <ns:path>` names it.
 * At runtime this is Minecraft's resource manager; offscreen it is the classpath, which lets the
 * shim be tested without a game.
 */
fun interface ShaderSourceProvider {
    fun read(namespace: String, path: String): String?

    companion object {
        /** Loads from this process's classpath, i.e. `assets/<namespace>/<path>`. */
        fun classpath(): ShaderSourceProvider = ShaderSourceProvider { namespace, path ->
            val resource = "/assets/$namespace/$path"
            ShaderSourceProvider::class.java.getResourceAsStream(resource)?.use { stream ->
                InputStreamReader(stream, Charsets.UTF_8).readText()
            }
        }
    }
}
