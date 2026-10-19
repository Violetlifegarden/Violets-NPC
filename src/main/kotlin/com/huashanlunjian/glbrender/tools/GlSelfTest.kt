package com.huashanlunjian.glbrender.tools

import top.fifthlight.blazerod.render.format.GlbElementType
import top.fifthlight.blazerod.render.format.GlbElementUsage
import top.fifthlight.blazerod.render.format.GlbIndexType
import top.fifthlight.blazerod.render.format.GlbPrimitiveMode
import top.fifthlight.blazerod.render.format.GlbVertexFormat
import top.fifthlight.blazerod.render.format.GlbVertexFormatElement
import top.fifthlight.blazerod.gl.GlbId
import org.lwjgl.glfw.Callbacks
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL32
import org.lwjgl.opengl.GL43
import org.lwjgl.system.MemoryUtil
import top.fifthlight.blazerod.gl.AddressMode
import top.fifthlight.blazerod.gl.BlendFunction
import top.fifthlight.blazerod.gl.CommandEncoder
import top.fifthlight.blazerod.gl.FilterMode
import top.fifthlight.blazerod.gl.Gl
import top.fifthlight.blazerod.gl.GlFramebuffers
import top.fifthlight.blazerod.gl.GlslPreprocessor
import top.fifthlight.blazerod.gl.GpuBuffer
import top.fifthlight.blazerod.gl.GpuBufferSlice
import top.fifthlight.blazerod.gl.GpuDevice
import top.fifthlight.blazerod.gl.GpuTexture
import top.fifthlight.blazerod.gl.RenderPipeline
import top.fifthlight.blazerod.gl.ShaderDefines
import top.fifthlight.blazerod.gl.ShaderSourceProvider
import top.fifthlight.blazerod.gl.TextureFormat
import top.fifthlight.blazerod.gl.UniformType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.OptionalDouble
import java.util.OptionalInt

/**
 * Exercises the GL shim against a real context without launching Minecraft, so a shim bug is never
 * confused with a port bug. Usage: gradlew glSelfTest
 */
fun main() {
    runPreprocessorChecks()
    GLFWErrorCallback.createPrint(System.err).set()
    check(GLFW.glfwInit()) { "glfwInit failed" }
    GLFW.glfwDefaultWindowHints()
    GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
    GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE)
    // KHR_debug is inert, and glDebugMessageCallback is an invalid operation, unless the context was
    // created with the debug bit.
    GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_DEBUG_CONTEXT, GLFW.GLFW_TRUE)
    val window = GLFW.glfwCreateWindow(1, 1, "glbrender self test", MemoryUtil.NULL, MemoryUtil.NULL)
    check(window != MemoryUtil.NULL) { "glfwCreateWindow failed" }
    try {
        GLFW.glfwMakeContextCurrent(window)
        GL.createCapabilities()
        // The driver names the call that set the error flag, which glGetError alone cannot tell us.
        GL11.glEnable(GL43.GL_DEBUG_OUTPUT)
        GL11.glEnable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS)
        GL43.glDebugMessageCallback({ source, type, _, _, length, message, _ ->
            println(
                "gl debug    = src=0x${source.toString(16)} type=0x${type.toString(16)} " +
                        MemoryUtil.memUTF8(message, length.toInt())
            )
        }, MemoryUtil.NULL)
        // Report, then clear, whatever the setup itself raised, so the checks below start clean.
        var setupError = GL11.glGetError()
        while (setupError != GL11.GL_NO_ERROR) {
            println("debug setup error = 0x${setupError.toString(16)}")
            setupError = GL11.glGetError()
        }
        runChecks()
    } finally {
        Callbacks.glfwFreeCallbacks(window)
        GLFW.glfwDestroyWindow(window)
        GLFW.glfwTerminate()
        GLFW.glfwSetErrorCallback(null)?.free()
    }
}

private fun runChecks() {
    Gl.init()
    println("gl version  = ${Gl.majorVersion}.${Gl.minorVersion}")
    val device = Gl.device
    println(
        "caps        = ssbo=${device.supportSsbo} tbo=${device.supportTextureBufferSlice} " +
                "compute=${device.supportComputeShader} barrier=${device.supportMemoryBarrier} " +
                "packing=${device.supportShaderPacking} labels=${device.debugLabels}"
    )
    println(
        "alignments  = ubo=${device.uniformOffsetAlignment} ssbo=${device.ssboOffsetAlignment} " +
                "tbo=${device.textureBufferOffsetAlignment}"
    )
    println(
        "ssbo slots  = bindings=${device.maxSsboBindings} vertex=${device.maxSsboInVertexShader} " +
                "fragment=${device.maxSsboInFragmentShader}"
    )

    val encoder = device.createCommandEncoder()

    // The map-then-advance pattern UniformBufferStorage uses for every uniform write.
    val ubo = device.createBuffer(
        { "selftest ubo" },
        GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE,
        256,
    )
    val slice = ubo.slice(0, 16)
    encoder.mapBuffer(slice, false, true).use { mapped ->
        mapped.data().order(ByteOrder.LITTLE_ENDIAN).putFloat(0, 12.5f).putInt(12, 7)
    }
    val uboBytes = readBytes(ubo, 16)
    check(uboBytes.order(ByteOrder.LITTLE_ENDIAN).float == 12.5f) { "ubo float write did not land" }
    check(uboBytes.order(ByteOrder.LITTLE_ENDIAN).position(12).int == 7) { "ubo int write did not land" }
    ubo.close()
    println("ubo         = alloc/map/getBufferSubData OK")

    // A vertex buffer is what the CpuTransform path uploads skinning results into.
    val vertex = device.createBuffer({ "selftest vbo" }, GpuBuffer.USAGE_VERTEX, floatBuffer(1f, 2f, 3f))
    val vertexBytes = readBytes(vertex, 12)
    check(vertexBytes.order(ByteOrder.LITTLE_ENDIAN).float == 1f) { "vertex upload lost" }
    check(vertexBytes.order(ByteOrder.LITTLE_ENDIAN).position(8).float == 3f) { "vertex upload tail lost" }
    vertex.close()
    println("vbo         = data upload OK")

    // The storage-buffer usage combination the vertex-shader skinning path needs.
    val ssbo = device.createBuffer(
        { "selftest ssbo" },
        GpuBuffer.USAGE_MAP_WRITE,
        GpuBuffer.EXTRA_USAGE_STORAGE_BUFFER,
        1024,
    )
    ssbo.close()
    println("ssbo        = extra usage bit accepted")

    // Morph targets take this path, which is the only place immutable storage is required.
    if (device.supportTextureBufferSlice) {
        val tbo = device.createBuffer(
            { "selftest tbo" },
            GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER,
            GpuBuffer.EXTRA_USAGE_STORAGE_BUFFER,
            1024,
        )
        tbo.close()
        println("tbo         = immutable storage alloc OK")
    } else {
        println("tbo         = unsupported on this driver, skipped")
    }

    val texture = device.createTexture(
        { "selftest texture" },
        GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST,
        TextureFormat.RGBA8,
        4,
        4,
        1,
        1,
    )
    texture.setAddressMode(AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE)
    texture.setTextureFilter(FilterMode.LINEAR, FilterMode.LINEAR, false)
    val view = device.createTextureView(texture)
    check(view.width == 4 && view.height == 4 && view.format == TextureFormat.RGBA8)
    check(GL11.glGetError() == GL11.GL_NO_ERROR) { "texture state raised an error" }
    texture.close()
    println("texture     = create/filter/view/close OK")

    val floatData = device.createBuffer(
        { "selftest rgba32f" },
        GpuBuffer.USAGE_COPY_DST,
        GpuBuffer.EXTRA_USAGE_STORAGE_BUFFER,
        floatBuffer(0.25f, 0.5f, 0.75f, 1f),
    )
    check(readBytes(floatData, 16).order(ByteOrder.LITTLE_ENDIAN).position(4).float == 0.5f)
    floatData.close()

    val fence = encoder.createFence()
    val status = fence.awaitCompletion(Long.MAX_VALUE)
    fence.close()
    check(status != GL32.GL_WAIT_FAILED) { "fence wait failed" }
    encoder.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT)
    println("fence       = signal/wait status=$status")

    runShaderChecks(device)
    runRenderPassChecks(device)
    println("RESULT      = PASS")
}

/**
 * Draws a triangle with a real ported shader into an offscreen target and reads the pixel back, so
 * the pass layer (pipeline compile, uniform and storage binding, attribute setup, FBO, blend) is
 * checked by an outcome rather than by an absence of errors.
 */
private fun runRenderPassChecks(device: GpuDevice) {
    val color = device.createTexture(
        { "selftest pass color" },
        GpuTexture.USAGE_RENDER_TARGET or GpuTexture.USAGE_TEXTURE_BINDING,
        TextureFormat.RGBA8,
        16,
        16,
        1,
        1,
    )
    val depth = device.createTexture(
        { "selftest pass depth" },
        GpuTexture.USAGE_RENDER_TARGET,
        TextureFormat.DEPTH24,
        16,
        16,
        1,
        1,
    )
    val baseColor = fillTexture(device.createTexture(
        { "selftest base" },
        GpuTexture.USAGE_TEXTURE_BINDING,
        TextureFormat.RGBA8,
        4,
        4,
        1,
        1,
    ))
    val lightMap = fillTexture(device.createTexture(
        { "selftest lightmap" },
        GpuTexture.USAGE_TEXTURE_BINDING,
        TextureFormat.RGBA8,
        16,
        16,
        1,
        1,
    ))
    // Vanilla's "no overlay" texel is white and opaque: the fragment shader's mix() keeps the vertex
    // colour at alpha 1, so an identity overlay lets the tint through unchanged.
    val overlay = fillTexture(device.createTexture(
        { "selftest overlay" },
        GpuTexture.USAGE_TEXTURE_BINDING,
        TextureFormat.RGBA8,
        4,
        4,
        1,
        1,
    ))

    // The plain unlit specialisation: SSBO matrices, no skinning, no morphing. The format is built
    // rather than taken from DefaultVertexFormat so that it matches unlit.vsh attribute for attribute.
    val meshFormat = GlbVertexFormat(
        elements = listOf(
            GlbVertexFormatElement("Position", GlbElementUsage.POSITION, GlbElementType.FLOAT, 3, 0),
            GlbVertexFormatElement("Color", GlbElementUsage.COLOR, GlbElementType.UBYTE, 4, 12),
            GlbVertexFormatElement("UV0", GlbElementUsage.UV, GlbElementType.FLOAT, 2, 16),
        ),
        vertexSize = 24,
    )
    val pipeline = RenderPipeline.builder()
        .withLocation(GlbId.fromNamespaceAndPath("blazerod", "selftest_unlit"))
        .withVertexShader(GlbId.fromNamespaceAndPath("blazerod", "core/unlit"))
        .withFragmentShader(GlbId.fromNamespaceAndPath("blazerod", "core/unlit"))
        .withVertexFormat(meshFormat)
        .withCull(false)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withSampler("SamplerBaseColor")
        .withSampler("SamplerLightMap")
        .withSampler("SamplerOverlay")
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .withUniform("Fog", UniformType.UNIFORM_BUFFER)
        .withUniform("UnlitData", UniformType.UNIFORM_BUFFER)
        .withUniform("InstanceData", UniformType.UNIFORM_BUFFER)
        .withStorageBuffer("LocalMatricesData")
        .withShaderDefine("SUPPORT_SSBO")
        .withShaderDefine("INSTANCE_SIZE", 16)
        .build()

    // InstanceData holds the per-instance arrays (matrices, light/overlay, tint), so it is by far the largest
    // block; one arena covers every block in the pipeline the way UniformBufferStorage does.
    val arena = device.createBuffer({ "selftest uniform arena" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE, 8192)
    val encoder = device.createCommandEncoder()
    // Fresh GL buffer storage is undefined, not zero, and the shader reads arrays this test does not
    // always write (light/overlay UVs, the unused tint slots), so start from a known state.
    upload(encoder, arena.slice()) { mapped -> for (i in 0 until 8192) mapped.put(i, 0.toByte()) }
    upload(encoder, arena.slice(0, 96)) { it.identity4(0).putFloat(64, 16f).putFloat(68, 16f) }
    upload(encoder, arena.slice(128, 64)) {
        it.putFloat(16, 1000f).putFloat(20, 2000f).putFloat(24, 1000f).putFloat(28, 2000f)
    }
    upload(encoder, arena.slice(256, 16)) { it.putFloat(0, 1f).putFloat(4, 1f).putFloat(8, 1f).putFloat(12, 1f) }
    // PrimitiveSize, PrimitiveIndex, ViewMatrix, then ModelMatrices[0] at the block's own offsets.
    // Tints[0] is a deliberate non-white value (orange) so the readback proves the shader picks the
    // per-instance tint up; zero would discard the triangle instead.
    upload(encoder, arena.slice(512, 2896)) {
        it.identity4(16).identity4(80)
            .putFloat(2640, 1f).putFloat(2644, 0.5f).putFloat(2648, 0.25f).putFloat(2652, 1f)
    }

    val matrices = device.createBuffer(
        { "selftest local matrices" },
        GpuBuffer.USAGE_MAP_WRITE,
        GpuBuffer.EXTRA_USAGE_STORAGE_BUFFER,
        256,
    )
    upload(encoder, matrices.slice()) { it.identity4(0).identity4(64) }

    val stride = meshFormat.vertexSize
    val positions = floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, 0f, 1f, 0f)
    // The layout is float3 + four normalised ubytes + float2, so the color cannot go through a
    // floatBuffer; 255 is what a full-brightness vertex color means in that layout.
    val vertices = ByteBuffer.allocateDirect(positions.size / 3 * stride).order(ByteOrder.LITTLE_ENDIAN)
    for (vertex in positions.indices step 3) {
        vertices.putFloat(positions[vertex]).putFloat(positions[vertex + 1]).putFloat(positions[vertex + 2])
        repeat(4) { vertices.put(255.toByte()) }
        vertices.putFloat(0.5f).putFloat(0.5f)
    }
    vertices.rewind()
    check(vertices.remaining() == 3 * stride) { "vertex size ${vertices.remaining()} does not match stride $stride" }
    val vertex = device.createBuffer({ "selftest mesh vbo" }, GpuBuffer.USAGE_VERTEX, vertices)
    val indices = shortBuffer(0, 1, 2)
    val index = device.createBuffer({ "selftest mesh ibo" }, GpuBuffer.USAGE_INDEX, indices)

    // The pass enables depth testing (the game leaves it wherever its last render type put it), so the
    // depth buffer has to start at the far plane or the triangle is rejected against undefined contents.
    val pass = encoder.createRenderPass(
        { "selftest pass" },
        device.createTextureView(color),
        OptionalInt.of(0),
        device.createTextureView(depth),
        OptionalDouble.of(1.0),
    )
    pass.setPipeline(pipeline)
    // The vertex format enables attribute location i for element i, exactly like 1.21.1 does, so a
    // linker that put Position somewhere else silently draws nothing.
    val program = pipeline.program
    run {
        val block = GL31.glGetUniformBlockIndex(program.id, "InstanceData")
        val size = GL31.glGetActiveUniformBlocki(program.id, block, GL31.GL_UNIFORM_BLOCK_DATA_SIZE)
        // INSTANCE_SIZE=16: 80 header + 2*1024 matrices + 2*256 uv + 256 tint = 2896. A shorter block
        // would mean the shader declared fewer arrays than the CPU-side layout writes.
        check(size == 2896) { "InstanceData is $size bytes, expected 2896 with the tint array" }
    }
    check(
        program.attributeLocation("Position") == 0 &&
                program.attributeLocation("Color") == 1 &&
                program.attributeLocation("UV0") == 2
    ) { "attribute locations do not follow the vertex format order" }
    check(
        listOf("Projection", "Fog", "UnlitData", "InstanceData").all { it in program.uniformBlockNames } &&
                "LocalMatricesData" in program.storageBlockNames
    ) { "the program is missing a block the pipeline declares" }
    pass.setUniform("Projection", arena.slice(0, 96))
    pass.setUniform("Fog", arena.slice(128, 64))
    pass.setUniform("UnlitData", arena.slice(256, 16))
    pass.setUniform("InstanceData", arena.slice(512, 2896))
    pass.setStorageBuffer("LocalMatricesData", matrices.slice())
    pass.bindSampler("SamplerBaseColor", device.createTextureView(baseColor))
    pass.bindSampler("SamplerLightMap", device.createTextureView(lightMap))
    pass.bindSampler("SamplerOverlay", device.createTextureView(overlay))
    pass.setVertexFormatMode(GlbPrimitiveMode.TRIANGLES)
    pass.setVertexBuffer(0, vertex)
    pass.setIndexBuffer(index.slice(), GlbIndexType.SHORT)
    pass.drawIndexed(0, 0, 3, 1)
    pass.close()
    pipeline.close()

    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, GlFramebuffers.framebufferFor(device.createTextureView(color), null))
    val pixels = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.LITTLE_ENDIAN)
    GL11.glReadPixels(0, 0, 16, 16, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
    Gl.checkError("glReadPixels")
    pixels.position((8 * 16 + 8) * 4)
    val center = intArrayOf(
        pixels.get().toInt() and 0xFF,
        pixels.get().toInt() and 0xFF,
        pixels.get().toInt() and 0xFF,
        pixels.get().toInt() and 0xFF,
    )
    val corner = run {
        pixels.clear()
        intArrayOf(
            pixels.get().toInt() and 0xFF,
            pixels.get().toInt() and 0xFF,
            pixels.get().toInt() and 0xFF,
            pixels.get().toInt() and 0xFF,
        )
    }
    // Orange and white differ in every channel, so a tint that never reached the shader (or that came
    // back as identity) cannot pass: the expected value is exactly the per-instance tint.
    check(center[0] > 250 && center[1] in 124..132 && center[2] in 60..68) {
        "triangle did not draw the per-instance tint: ${center.toList()}"
    }
    check(corner[3] == 0) { "the target was not empty outside the triangle: ${corner.toList()}" }
    println("render pass = drew center=${center.toList()} corner=${corner.toList()}")

    index.close()
    vertex.close()
    matrices.close()
    arena.close()
    baseColor.close()
    lightMap.close()
    overlay.close()
    color.close()
    depth.close()
}

private fun upload(encoder: CommandEncoder, slice: GpuBufferSlice, writes: (ByteBuffer) -> Unit) {
    encoder.mapBuffer(slice, false, true).use { mapped ->
        writes(mapped.data().order(ByteOrder.LITTLE_ENDIAN))
    }
}

private fun ByteBuffer.identity4(offset: Int): ByteBuffer {
    for (row in 0 until 4) putFloat(offset + row * 16 + row * 4, 1f)
    return this
}

private fun fillTexture(texture: GpuTexture): GpuTexture {
    val white = ByteBuffer.allocateDirect(texture.width * texture.height * 4)
    repeat(white.capacity()) { white.put(it, 0xFF.toByte()) }
    GL30.glBindTexture(GL11.GL_TEXTURE_2D, texture.id)
    GL11.glTexSubImage2D(
        GL11.GL_TEXTURE_2D, 0, 0, 0, texture.width, texture.height,
        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, white,
    )
    GL30.glBindTexture(GL11.GL_TEXTURE_2D, 0)
    Gl.checkError("fill texture")
    return texture
}

private fun shortBuffer(vararg values: Int): ByteBuffer =
    ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { buffer ->
        values.forEach { buffer.putShort(it.toShort()) }
        buffer.rewind()
    }

/**
 * Text-only checks for the GLSL front end: they pin down import inlining, the version/extension
 * decisions and define emission without a driver involved, so a compile failure below is a shader
 * problem rather than a preprocessor problem.
 */
private fun runPreprocessorChecks() {
    val preprocessor = GlslPreprocessor(ShaderSourceProvider.classpath())
    val sources = ShaderSourceProvider.classpath()
    val vertex = sources.read("blazerod", "shaders/core/unlit.vsh")
        ?: throw IllegalStateException("shaders/core/unlit.vsh is not on the classpath")

    val ssboDefines = ShaderDefines(
        flags = setOf("SUPPORT_SSBO", "SKINNED", "MORPHED", "INSTANCED"),
        values = mapOf(
            "SUPPORT_SSBO" to "",
            "INSTANCE_SIZE" to "16",
            "MAX_ENABLED_MORPH_TARGETS" to "8",
        ),
    )
    val processed = preprocessor.process(vertex, ssboDefines, 4, 6)
    check(!processed.contains("#moj_import")) { "an import survived preprocessing" }
    check(processed.lines().none { it.trimStart().startsWith("#blazerod_") }) {
        "a directive line was left for the driver to parse"
    }
    check(processed.startsWith("#version 430\n")) { "expected the 4.3 include to raise the version" }
    check("#extension" !in processed) { "a modern context must not require a legacy extension" }
    check("#define INSTANCE_SIZE 16" in processed) { "define values were not emitted" }
    check("mat4 getJointPositionMatrix" in processed) { "skin.glsl did not pull in joint.glsl" }
    check(
        processed.lines().count { it.contains("instance_t get_instance()") } == 1
    ) { "an include was emitted twice" }
    println("glsl        = imports/version/defines OK")

    // The pre-4.3 branch: the same source must ask for the SSBO extension and stay on GLSL 150.
    val legacy = preprocessor.process(vertex, ssboDefines, 4, 2)
    check(legacy.startsWith("#version 150\n")) { "expected the fallback version, got ${legacy.lineSequence().first()}" }
    check("#extension GL_ARB_shader_storage_buffer_object : require" in legacy)
    check("#define SUPPORT_SSBO" in legacy)
    println("glsl legacy = version 150 + extension require OK")

    // Without SUPPORT_SSBO the flag must not reach the shader at all, or the TBO path is dead code.
    val tbo = preprocessor.process(vertex, ShaderDefines.of("SKINNED"), 4, 6)
    check("#define SUPPORT_SSBO" !in tbo)
    check("uniform samplerBuffer LocalMatrices" in tbo) { "expected the texel-buffer declaration" }
    println("glsl tbo    = texel buffer path selected")
}

private fun runShaderChecks(device: GpuDevice) {
    val sources = ShaderSourceProvider.classpath()
    fun core(name: String): String = sources.read("blazerod", "shaders/core/$name")
        ?: throw IllegalStateException("shaders/core/$name is not on the classpath")

    val defines = ShaderDefines(
        flags = setOf("SUPPORT_SSBO", "SKINNED", "MORPHED"),
        values = mapOf("INSTANCE_SIZE" to "16", "MAX_ENABLED_MORPH_TARGETS" to "8"),
    )
    val unlit = device.createShaderProgram(
        { "selftest unlit" },
        defines,
        core("unlit.vsh"),
        core("unlit.fsh"),
    )
    check(unlit.uniformBlockNames.containsAll(listOf("Projection", "InstanceData", "Fog", "UnlitData"))) {
        "uniform blocks = ${unlit.uniformBlockNames}"
    }
    check("LocalMatricesData" in unlit.storageBlockNames) { "storage blocks = ${unlit.storageBlockNames}" }
    check(unlit.uniformLocation("SamplerBaseColor") >= 0) { "SamplerBaseColor has no location" }
    check(unlit.uniformLocation("SamplerLightMap") >= 0) { "SamplerLightMap has no location" }
    check(unlit.attributeLocation("Position") >= 0) { "Position has no location" }
    check(unlit.attributeLocation("Joint") >= 0) { "Joint has no location" }
    println(
        "unlit       = linked ubo=${unlit.uniformBlockNames} ssbo=${unlit.storageBlockNames}"
    )
    unlit.close()

    // The plain path must compile with every optional feature switched off, which is what the
    // non-skinned, non-morphed meshes in a GLB take. INSTANCE_SIZE still has to carry a value:
    // instance.glsl sizes its model matrix arrays with it even without INSTANCED.
    val plain = device.createShaderProgram(
        { "selftest unlit plain" },
        ShaderDefines(values = mapOf("INSTANCE_SIZE" to "16", "MAX_ENABLED_MORPH_TARGETS" to "8")),
        core("unlit.vsh"),
        core("unlit.fsh"),
    )
    check("LocalMatricesData" !in plain.storageBlockNames)
    check(plain.attributeLocation("Joint") < 0) { "Joint must be optimized away without SKINNED" }
    println("unlit plain = linked ubo=${plain.uniformBlockNames}")
    plain.close()

    val vanilla = device.createShaderProgram(
        { "selftest vanilla" },
        defines,
        core("vanilla.vsh"),
        core("vanilla.fsh"),
    )
    check("Lighting" in vanilla.uniformBlockNames) { "uniform blocks = ${vanilla.uniformBlockNames}" }
    check(vanilla.uniformLocation("SamplerOverlay") >= 0) { "SamplerOverlay has no location" }
    check(vanilla.attributeLocation("Normal") >= 0) { "Normal has no location" }
    println("vanilla     = linked ubo=${vanilla.uniformBlockNames} ssbo=${vanilla.storageBlockNames}")
    vanilla.close()
}

private fun floatBuffer(vararg values: Float): ByteBuffer =
    ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { buffer ->
        values.forEach(buffer::putFloat)
        buffer.rewind()
    }

private fun readBytes(buffer: GpuBuffer, count: Int): ByteBuffer {
    GL30.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer.id)
    val out = ByteBuffer.allocateDirect(count).order(ByteOrder.LITTLE_ENDIAN)
    GL30.glGetBufferSubData(GL31.GL_COPY_WRITE_BUFFER, 0L, out)
    GL30.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0)
    out.rewind()
    return out
}
