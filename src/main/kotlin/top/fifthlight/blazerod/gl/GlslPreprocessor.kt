package top.fifthlight.blazerod.gl

import kotlin.math.max

/**
 * Turns one `#moj_import` rooted shader into a single GLSL translation unit, then resolves the
 * `#blazerod_version` / `#blazerod_extension` directives the ported shaders use.
 *
 * Minecraft 1.21.2 splits this between its own GlslPreprocessor (imports, defines) and BlazeRod's
 * GlslExtensionProcessor (version, extensions); 1.21.1 has neither, so both passes live here. The
 * order matters: imports must be merged first, because an included file is what usually asks for the
 * higher GLSL version and the extension requirements.
 */
class GlslPreprocessor(private val sources: ShaderSourceProvider) {

    fun process(source: String, defines: ShaderDefines): String =
        process(source, defines, Gl.majorVersion, Gl.minorVersion)

    internal fun process(source: String, defines: ShaderDefines, major: Int, minor: Int): String {
        val merged = expandImports(normalizeLineEndings(source), mutableSetOf())
        return resolveDirectives(merged, defines, major, minor)
    }

    private fun normalizeLineEndings(source: String) = source.replace("\r\n", "\n")

    private fun expandImports(source: String, visited: MutableSet<String>): String {
        val out = StringBuilder()
        for (line in source.lines()) {
            val import = importPattern.matchEntire(line.trim())
            if (import == null) {
                out.appendLine(line)
                continue
            }
            val (namespace, path) = import.destructured
            val name = if (namespace.isEmpty()) defaultNamespace to path else namespace to path
            val key = "${name.first}:${name.second}"
            // A repeated include is skipped rather than re-emitted: the alternative is duplicate
            // uniform block declarations, which the compiler rejects.
            if (key in visited) continue
            visited += key
            val content = sources.read(name.first, "$includeDirectory${name.second}")
                ?: throw IllegalArgumentException("Missing shader import <$key>")
            out.appendLine(expandImports(normalizeLineEndings(content), visited))
        }
        // appendLine on the final line adds one newline the source did not have; harmless for GLSL.
        return out.toString().trimEnd('\n') + "\n"
    }

    private fun resolveDirectives(source: String, defines: ShaderDefines, major: Int, minor: Int): String {
        var finalVersion = 0
        val extensions = mutableMapOf<String, String>()
        val body = StringBuilder()
        val continued = StringBuilder()

        for (line in source.lines()) {
            val processed: String? = when {
                rawVersionPattern.matches(line) -> {
                    val version = rawVersionPattern.matchEntire(line)!!.groupValues[1]
                    finalVersion = max(finalVersion, version.toInt())
                    "/* $line */"
                }

                blazerodVersionPattern.matches(line) -> {
                    val groups = blazerodVersionPattern.matchEntire(line)!!.groupValues
                    val chosen = if (isMet(groups[1], major, minor, defines)) groups[2] else groups[3]
                    finalVersion = max(finalVersion, chosen.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid version $chosen in: $line"))
                    "/* $line */"
                }

                blazerodExtensionPattern.matches(line) -> {
                    val groups = blazerodExtensionPattern.matchEntire(line)!!.groupValues
                    if (isMet(groups[1], major, minor, defines)) {
                        extensions[groups[2]] = groups[3]
                    }
                    "/* $line */"
                }

                line.endsWith("\\") -> {
                    continued.append(line.dropLast(1))
                    null
                }

                else -> line
            }
            if (processed != null) {
                if (continued.isNotEmpty()) {
                    body.append(continued)
                    continued.clear()
                }
                body.appendLine(processed)
            }
        }
        if (continued.isNotEmpty()) body.appendLine(continued)
        if (finalVersion == 0) {
            throw IllegalArgumentException("Shader source has no version: ${source.take(120)}")
        }

        return buildString {
            appendLine("#version $finalVersion")
            for ((extension, status) in extensions) {
                appendLine("#extension $extension : $status")
            }
            for (flag in defines.flags.filterNot { defines.values.containsKey(it) }) {
                appendLine("#define $flag")
            }
            for ((name, value) in defines.values) {
                appendLine("#define $name $value")
            }
            appendLine("#line 1 0")
            append(body)
        }
    }

    private fun isMet(conditions: String, major: Int, minor: Int, defines: ShaderDefines): Boolean =
        conditions.split("&&").all { condition ->
            val trimmed = condition.trim()
            versionPattern.matchEntire(trimmed)?.let {
                val (operator, wantMajor, wantMinor) = it.destructured
                val wantMajorNum = wantMajor.toInt()
                val wantMinorNum = wantMinor.toInt()
                when (operator) {
                    "<" -> major < wantMajorNum || (major == wantMajorNum && minor < wantMinorNum)
                    "<=" -> major < wantMajorNum || (major == wantMajorNum && minor <= wantMinorNum)
                    ">" -> major > wantMajorNum || (major == wantMajorNum && minor > wantMinorNum)
                    ">=" -> major > wantMajorNum || (major == wantMajorNum && minor >= wantMinorNum)
                    else -> throw IllegalArgumentException("Invalid operator $operator in: $trimmed")
                }
            } ?: definePattern.matchEntire(trimmed)?.let {
                defines.hasFlag(it.destructured.component1())
            } ?: throw IllegalArgumentException("Invalid condition $trimmed")
        }

    companion object {
        private const val defaultNamespace = "blazerod"

        /** Minecraft's include convention: a `#moj_import` names a file below `shaders/include/`. */
        private const val includeDirectory = "shaders/include/"

        private val importPattern =
            """#moj_import\s*<\s*(?:(?<namespace>[^:>]+):)?(?<path>[^:>]+)\s*>""".toRegex()

        private val rawVersionPattern = """#version\s+(\d+).*""".toRegex()

        private val versionPattern =
            """version\((?<condition><=|>=|>|<)(?<major>\d+)\.(?<minor>\d+)\)""".toRegex()

        private val definePattern = """defined\((?<define>\w+)\)""".toRegex()

        private val blazerodVersionPattern =
            """#blazerod_version\s+(?<condition>\S+)\s*\?\s*(?<first>\d+)\s*:\s*(?<second>\d+)""".toRegex()

        private val blazerodExtensionPattern =
            """#blazerod_extension\s+(?<condition>[^;]+)\s*;\s*(?<extension>\w+)\s*:\s*(?<status>require|enable|warn|disable)""".toRegex()
    }
}
