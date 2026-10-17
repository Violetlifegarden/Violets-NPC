package top.fifthlight.blazerod.gl

/**
 * Shader specialisation constants. Minecraft 1.21.2 has net.minecraft.client.renderer.ShaderDefines;
 * 1.21.1 does not, so the ported pipeline builder uses this. A flag becomes `#define NAME`, a value
 * becomes `#define NAME VALUE`.
 */
class ShaderDefines(
    val flags: Set<String> = emptySet(),
    val values: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = flags.isEmpty() && values.isEmpty()

    fun and(other: ShaderDefines): ShaderDefines {
        if (isEmpty) return other
        if (other.isEmpty) return this
        return ShaderDefines(flags + other.flags, values + other.values)
    }

    fun hasFlag(name: String) = name in flags || name in values

    override fun toString(): String =
        (flags.map { it } + values.map { "${it.key}=${it.value}" }).sorted().toString()

    companion object {
        @JvmField
        val EMPTY = ShaderDefines()

        fun of(vararg flags: String): ShaderDefines = ShaderDefines(flags.toSet())

        fun of(name: String, value: String): ShaderDefines =
            ShaderDefines(setOf(name), mapOf(name to value))

        fun of(name: String, value: Int): ShaderDefines = of(name, value.toString())
    }
}
