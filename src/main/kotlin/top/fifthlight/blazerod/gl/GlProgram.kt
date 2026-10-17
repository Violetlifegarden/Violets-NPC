package top.fifthlight.blazerod.gl

import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL43
import org.slf4j.LoggerFactory
import java.util.function.Supplier

/**
 * A linked vertex+fragment program, with the resource names the ported render pass binds by
 * already resolved to GL indices: uniform blocks and shader storage blocks to their own binding
 * ranges, samplers and vertex attributes to locations.
 */
class GlProgram private constructor(
    val id: Int,
    val name: String?,
    private val uniformBlockBindings: Map<String, Int>,
    private val storageBlockBindings: Map<String, Int>,
) : AutoCloseable {
    private val locations = mutableMapOf<String, Int>()

    val uniformBlockNames: Set<String> get() = uniformBlockBindings.keys
    val storageBlockNames: Set<String> get() = storageBlockBindings.keys

    fun uniformBlockBinding(name: String): Int = uniformBlockBindings[name]
        ?: throw IllegalArgumentException("Program ${this.name ?: "unnamed"} has no uniform block $name")

    fun storageBlockBinding(name: String): Int = storageBlockBindings[name]
        ?: throw IllegalArgumentException("Program ${this.name ?: "unnamed"} has no storage block $name")

    /** -1 when the name is not an active uniform, which is how a missing sampler shows up. */
    fun uniformLocation(name: String): Int = locations.getOrPut(name) {
        GL20.glGetUniformLocation(id, name)
    }

    /** -1 when the name is not an active attribute. */
    fun attributeLocation(name: String): Int = locations.getOrPut("attribute:$name") {
        GL20.glGetAttribLocation(id, name)
    }

    override fun close() {
        GL20.glDeleteProgram(id)
        Gl.checkError("delete program ${name ?: "unnamed"}")
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("blazerod-gl/GLSL")

        @JvmStatic
        fun create(
            labelGetter: Supplier<String>?,
            vertexSource: String,
            fragmentSource: String,
        ): GlProgram {
            val name = Gl.label(labelGetter)
            val vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource, name)
            try {
                val fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource, name)
                try {
                    return link(vertex, fragment, name)
                } finally {
                    GL20.glDeleteShader(fragment)
                }
            } finally {
                GL20.glDeleteShader(vertex)
            }
        }

        private fun compile(type: Int, source: String, name: String?): Int {
            val shader = GL20.glCreateShader(type)
            check(shader != 0) { "glCreateShader returned 0 for ${if (type == GL20.GL_VERTEX_SHADER) "vertex" else "fragment"} shader" }
            GL20.glShaderSource(shader, source)
            GL20.glCompileShader(shader)
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
                val log = GL20.glGetShaderInfoLog(shader, 32768)
                val kind = if (type == GL20.GL_VERTEX_SHADER) "vertex" else "fragment"
                LOGGER.error("Failed to compile {} shader {}: {}\n{}", kind, name, log, numberedSource(source))
                GL20.glDeleteShader(shader)
                throw ShaderCompilationException("$kind shader ${name ?: "unnamed"}: $log")
            }
            return shader
        }

        private fun link(vertex: Int, fragment: Int, name: String?): GlProgram {
            Gl.checkError("before linking ${name ?: "unnamed"}")
            val program = GL20.glCreateProgram()
            check(program != 0) { "glCreateProgram returned 0" }
            GL20.glAttachShader(program, vertex)
            GL20.glAttachShader(program, fragment)
            GL20.glLinkProgram(program)
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                val log = GL20.glGetProgramInfoLog(program, 32768)
                GL20.glDeleteProgram(program)
                throw ShaderCompilationException("program ${name ?: "unnamed"}: $log")
            }
            val infoLog = GL20.glGetProgramInfoLog(program, 32768)
            if (infoLog.isNotBlank()) LOGGER.info("Link log for {}: {}", name, infoLog)

            val uniformBlocks = bindUniformBlocks(program)
            val storageBlocks = bindStorageBlocks(program)
            Gl.setProgramLabel(program, name)
            Gl.checkError("link program ${name ?: "unnamed"}")
            return GlProgram(program, name, uniformBlocks, storageBlocks)
        }

        private fun bindUniformBlocks(program: Int): Map<String, Int> {
            val count = GL20.glGetProgrami(program, GL31.GL_ACTIVE_UNIFORM_BLOCKS)
            val bindings = LinkedHashMap<String, Int>()
            for (index in 0 until count) {
                val blockName = GL31.glGetActiveUniformBlockName(program, index) ?: continue
                // Binding points are per target, so uniform blocks start their own numbering;
                // two blocks with the same name in different targets stay distinct.
                GL31.glUniformBlockBinding(program, index, index)
                bindings[blockName] = index
            }
            return bindings
        }

        private fun bindStorageBlocks(program: Int): Map<String, Int> {
            if (!Gl.isSsboSupported) return emptyMap()
            // Shader storage blocks have no GL31-style named query, so the ARB_program_interface_query
            // enumeration is the only way to get their names.
            val count = GL43.glGetProgramInterfacei(program, GL43.GL_SHADER_STORAGE_BLOCK, GL43.GL_ACTIVE_RESOURCES)
            val bindings = LinkedHashMap<String, Int>()
            for (index in 0 until count) {
                val blockName = GL43.glGetProgramResourceName(program, GL43.GL_SHADER_STORAGE_BLOCK, index)
                    ?: continue
                GL43.glShaderStorageBlockBinding(program, index, index)
                bindings[blockName] = index
            }
            return bindings
        }

        private fun numberedSource(source: String): String =
            source.lines().mapIndexed { index, line -> "${index + 1}\t$line" }.joinToString("\n")
    }

    class ShaderCompilationException(message: String) : RuntimeException(message)
}
