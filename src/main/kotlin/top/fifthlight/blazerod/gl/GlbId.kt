package top.fifthlight.blazerod.gl

/**
 * A namespaced resource id (`namespace:path`), the identifier the GL layer uses to name pipelines and
 * shaders. Minecraft represents this as `ResourceLocation`; core keeps its own so the render library
 * carries no game dependency, and the glb-mc seam translates at the resource boundary.
 */
class GlbId(val namespace: String, val path: String) {
    override fun toString(): String = "$namespace:$path"

    override fun equals(other: Any?): Boolean = other is GlbId && other.namespace == namespace && other.path == path

    override fun hashCode(): Int = 31 * namespace.hashCode() + path.hashCode()

    companion object {
        fun fromNamespaceAndPath(namespace: String, path: String): GlbId = GlbId(namespace, path)
    }
}
