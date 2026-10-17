package top.fifthlight.blazerod.gl

import top.fifthlight.blazerod.render.format.GlbVertexFormat
import net.minecraft.resources.ResourceLocation
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL31

/**
 * The pipeline description the ported renderer builds. Minecraft 1.21.2's
 * `com.mojang.blaze3d.pipeline.RenderPipeline` is a shader-state object there, while 1.21.1's class
 * of the same name only wraps a shader+vertex-format pair, so the shim owns this one and the ported
 * code imports it from this package.
 */
class RenderPipeline private constructor(
    val location: ResourceLocation?,
    val vertexShader: ResourceLocation,
    val fragmentShader: ResourceLocation,
    val vertexFormat: GlbVertexFormat?,
    val isCullEnabled: Boolean,
    val blendFunction: BlendFunction?,
    val uniforms: Map<String, Uniform>,
    val storageBuffers: List<String>,
    val samplers: List<String>,
    val defines: ShaderDefines,
) : AutoCloseable {
    val samplerSlots: Map<String, Int> = samplers.withIndex().associate { (index, name) -> name to index }

    private var compiledProgram: GlProgram? = null

    /**
     * Compiled on first use: building a pipeline happens while models load, and a GL context is not
     * guaranteed to be current there, whereas a draw always runs on the render thread. Render thread
     * only, like the rest of this package.
     */
    val program: GlProgram
        get() = compiledProgram ?: Gl.device.createShaderProgram(
            { label },
            defines,
            Gl.shaderSources.readShader(vertexShader, ".vsh"),
            Gl.shaderSources.readShader(fragmentShader, ".fsh"),
        ).also { compiledProgram = it }

    val label: String get() = location?.toString() ?: "$vertexShader+$fragmentShader"

    fun samplerSlot(name: String): Int = samplerSlots[name]
        ?: throw IllegalArgumentException("Pipeline $location has no sampler $name")

    fun uniform(name: String): Uniform = uniforms[name]
        ?: throw IllegalArgumentException("Pipeline $location has no uniform $name")

    override fun close() {
        compiledProgram?.close()
        compiledProgram = null
    }

    class Builder internal constructor() {
        private var location: ResourceLocation? = null
        private var vertexShader: ResourceLocation? = null
        private var fragmentShader: ResourceLocation? = null
        private var vertexFormat: GlbVertexFormat? = null
        private var isCullEnabled = true
        private var blendFunction: BlendFunction? = null
        private val uniforms = LinkedHashMap<String, Uniform>()
        private val storageBuffers = mutableListOf<String>()
        private val samplers = mutableListOf<String>()
        private val defineFlags = linkedSetOf<String>()
        private val defineValues = LinkedHashMap<String, String>()

        fun withLocation(location: ResourceLocation) = also { this.location = location }

        fun withVertexShader(location: ResourceLocation) = also { this.vertexShader = location }

        fun withFragmentShader(location: ResourceLocation) = also { this.fragmentShader = location }

        fun withVertexFormat(format: GlbVertexFormat?) = also { vertexFormat = format }

        fun withCull(enable: Boolean) = also { isCullEnabled = enable }

        fun withBlend(blend: BlendFunction?) = also { blendFunction = blend }

        fun withSampler(name: String) = also { samplers += name }

        fun withUniform(name: String, type: UniformType) =
            also { uniforms[name] = Uniform(type, null) }

        fun withUniform(name: String, type: UniformType, format: TextureFormat) =
            also { uniforms[name] = Uniform(type, format) }

        fun withStorageBuffer(name: String) = also { storageBuffers += name }

        fun withShaderDefine(name: String) = also { defineFlags += name }

        fun withShaderDefine(name: String, value: String) = also { defineValues[name] = value }

        fun withShaderDefine(name: String, value: Int) = withShaderDefine(name, value.toString())

        fun build(): RenderPipeline = RenderPipeline(
            location = location,
            vertexShader = vertexShader
                ?: throw IllegalStateException("Pipeline $location has no vertex shader"),
            fragmentShader = fragmentShader
                ?: throw IllegalStateException("Pipeline $location has no fragment shader"),
            vertexFormat = vertexFormat,
            isCullEnabled = isCullEnabled,
            blendFunction = blendFunction,
            uniforms = uniforms.toMap(),
            storageBuffers = storageBuffers.toList(),
            samplers = samplers.toList(),
            defines = ShaderDefines(defineFlags.toSet(), defineValues.toMap()),
        )
    }

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

/** Matches Minecraft's convention: a shader location names a file below `shaders/`. */
private const val shaderDirectory = "shaders/"

private fun ShaderSourceProvider.readShader(location: ResourceLocation, suffix: String): String =
    read(location.namespace, "$shaderDirectory${location.path}$suffix")
        ?: throw IllegalArgumentException("Missing shader ${location}$suffix")

enum class UniformType(val glTarget: Int) {
    UNIFORM_BUFFER(GL31.GL_UNIFORM_BUFFER),
    TEXEL_BUFFER(GL31.GL_TEXTURE_BUFFER),
}

data class Uniform(val type: UniformType, val format: TextureFormat?)

/**
 * Fixed-function blend state. Minecraft 1.21.2 keeps this in a JSON asset and in a class of the same
 * name that 1.21.1 does not have; the factors here are GL enum values directly.
 */
data class BlendFunction(
    val colorSourceFactor: Int,
    val colorDestFactor: Int,
    val alphaSourceFactor: Int = colorSourceFactor,
    val alphaDestFactor: Int = colorDestFactor,
) {
    companion object {
        @JvmField
        val TRANSLUCENT = BlendFunction(
            GL11.GL_SRC_ALPHA,
            GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE,
            GL11.GL_ONE_MINUS_SRC_ALPHA,
        )

        @JvmField
        val OPAQUE = BlendFunction(GL11.GL_ONE, GL11.GL_ZERO)

        @JvmField
        val ADDITIVE = BlendFunction(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
    }
}
