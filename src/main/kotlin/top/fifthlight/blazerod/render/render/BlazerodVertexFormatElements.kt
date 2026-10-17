package top.fifthlight.blazerod.render.render

import com.mojang.blaze3d.vertex.VertexFormatElement

object BlazerodVertexFormatElements {
    val JOINT: VertexFormatElement = register(0, VertexFormatElement.Type.USHORT, VertexFormatElement.Usage.GENERIC, 4)
    val WEIGHT: VertexFormatElement = register(0, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 4)

    /**
     * 1.21.1 keeps its element list private, so the next free id is found through the public
     * [VertexFormatElement.byId] lookup instead of reading `ELEMENTS.size` the way 1.21.4+ does.
     */
    private fun register(index: Int, type: VertexFormatElement.Type, usage: VertexFormatElement.Usage, count: Int) =
        (0 until VertexFormatElement.MAX_COUNT)
            .first { VertexFormatElement.byId(it) == null }
            .let { VertexFormatElement.register(it, index, type, usage, count) }
}
