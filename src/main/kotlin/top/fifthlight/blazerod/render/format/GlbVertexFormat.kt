package top.fifthlight.blazerod.render.format

import org.lwjgl.opengl.GL11

/**
 * Core-owned vertex-format description. Mirrors the layouts `BlazerodVertexFormats` built on
 * `com.mojang.blaze3d.vertex.VertexFormat` so `glb-core` can describe attributes without importing
 * Minecraft. Element offsets/`vertexSize` are the exact running totals of the original mojang
 * builders (including their `.padding()` calls), so GL attribute setup produces identical pointers.
 * Padding is folded into the following element's offset rather than stored as a list entry.
 */
enum class GlbElementType(val glType: Int, val bytes: Int) {
    BYTE(GL11.GL_BYTE, 1),
    UBYTE(GL11.GL_UNSIGNED_BYTE, 1),
    SHORT(GL11.GL_SHORT, 2),
    USHORT(GL11.GL_UNSIGNED_SHORT, 2),
    INT(GL11.GL_INT, 4),
    UINT(GL11.GL_UNSIGNED_INT, 4),
    FLOAT(GL11.GL_FLOAT, 4),
}

enum class GlbElementUsage { POSITION, NORMAL, COLOR, UV, GENERIC }

enum class GlbPrimitiveMode(val asGLMode: Int) {
    LINES(GL11.GL_LINES),
    LINE_STRIP(GL11.GL_LINE_STRIP),
    TRIANGLES(GL11.GL_TRIANGLES),
    TRIANGLE_STRIP(GL11.GL_TRIANGLE_STRIP),
    TRIANGLE_FAN(GL11.GL_TRIANGLE_FAN),
}

enum class GlbIndexType(val bytes: Int, val asGLType: Int) {
    SHORT(2, GL11.GL_UNSIGNED_SHORT),
    INT(4, GL11.GL_UNSIGNED_INT),
}

class GlbVertexFormatElement(
    val name: String,
    val usage: GlbElementUsage,
    val type: GlbElementType,
    val count: Int,
    val offset: Int,
) {
    val byteSize: Int get() = type.bytes * count
}

class GlbVertexFormat(
    val elements: List<GlbVertexFormatElement>,
    val vertexSize: Int,
) {
    fun offset(element: GlbVertexFormatElement): Int = element.offset

    fun getElementName(element: GlbVertexFormatElement): String = element.name

    fun element(name: String): GlbVertexFormatElement =
        elements.first { it.name == name }
}

private fun position(offset: Int) =
    GlbVertexFormatElement("Position", GlbElementUsage.POSITION, GlbElementType.FLOAT, 3, offset)

private fun color(offset: Int) =
    GlbVertexFormatElement("Color", GlbElementUsage.COLOR, GlbElementType.UBYTE, 4, offset)

private fun uv0(offset: Int) =
    GlbVertexFormatElement("UV0", GlbElementUsage.UV, GlbElementType.FLOAT, 2, offset)

private fun uv1(offset: Int) =
    GlbVertexFormatElement("UV1", GlbElementUsage.UV, GlbElementType.SHORT, 2, offset)

private fun uv2(offset: Int) =
    GlbVertexFormatElement("UV2", GlbElementUsage.UV, GlbElementType.SHORT, 2, offset)

private fun normal(offset: Int) =
    GlbVertexFormatElement("Normal", GlbElementUsage.NORMAL, GlbElementType.BYTE, 3, offset)

private fun joint(offset: Int) =
    GlbVertexFormatElement("Joint", GlbElementUsage.GENERIC, GlbElementType.USHORT, 4, offset)

private fun weight(offset: Int) =
    GlbVertexFormatElement("Weight", GlbElementUsage.GENERIC, GlbElementType.FLOAT, 4, offset)

object GlbVertexFormats {
    // 12
    val POSITION = GlbVertexFormat(
        elements = listOf(position(0)),
        vertexSize = 12,
    )

    // Position 12 | Color 16 | UV0 24 | padding 8 -> 32
    val POSITION_COLOR_TEXTURE = GlbVertexFormat(
        elements = listOf(position(0), color(12), uv0(16)),
        vertexSize = 32,
    )

    // Position 12 | Color 16 | UV0 24 | Joint 32 | Weight 48
    val POSITION_COLOR_TEXTURE_JOINT_WEIGHT = GlbVertexFormat(
        elements = listOf(position(0), color(12), uv0(16), joint(24), weight(32)),
        vertexSize = 48,
    )

    // Position 12 | Color 16 | UV0 24 | Normal 27 | padding 5 -> 32
    val POSITION_COLOR_TEXTURE_NORMAL = GlbVertexFormat(
        elements = listOf(position(0), color(12), uv0(16), normal(24)),
        vertexSize = 32,
    )

    // Position 12 | Color 16 | UV0 24 | Normal 27 | pad5 32 | Joint 40 | pad8 48 | Weight 64
    val POSITION_COLOR_TEXTURE_NORMAL_JOINT_WEIGHT = GlbVertexFormat(
        elements = listOf(position(0), color(12), uv0(16), normal(24), joint(32), weight(48)),
        vertexSize = 64,
    )

    // Position 12 | Color 16 | UV0 24 | UV1 28 | UV2 32 | Normal 35 | padding 13 -> 48
    val ENTITY_PADDED = GlbVertexFormat(
        elements = listOf(position(0), color(12), uv0(16), uv1(24), uv2(28), normal(32)),
        vertexSize = 48,
    )
}
