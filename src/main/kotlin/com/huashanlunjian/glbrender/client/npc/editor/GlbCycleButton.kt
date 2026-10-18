package com.huashanlunjian.glbrender.client.npc.editor

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/**
 * A CustomNPC-style "cycle" control ([cycle] in CNPC's `GuiButtonNop(options, value)`): shows the current option, and
 * advances to the next on click, wrapping. Used as the FUNCTIONAL model picker (clicking cycles the registered GLB
 * model ids) — [onChange] receives the new index; the caller sends the packet.
 */
class GlbCycleButton(
    x: Int,
    y: Int,
    width: Int,
    private val options: Array<String>,
    initialIndex: Int,
    private val onChange: (Int) -> Unit,
) : AbstractWidget(x, y, width, HEIGHT, Component.empty()) {

    var index: Int = if (options.isEmpty()) 0 else initialIndex.coerceIn(0, options.size - 1)

    override fun onClick(mouseX: Double, mouseY: Double) {
        if (options.isEmpty()) return
        index = (index + 1) % options.size
        onChange(index)
    }

    override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val font = Minecraft.getInstance().font
        val hovered = mouseX in x..(x + width) && mouseY in y..(y + HEIGHT)
        g.fill(x, y, x + width, y + HEIGHT, if (hovered) BG_HOVER else BG)
        val text = options.getOrNull(index) ?: "<none>"
        g.drawCenteredString(font, text, x + width / 2, y + (HEIGHT - font.lineHeight) / 2, 0xFFFF80)
    }

    override fun updateWidgetNarration(narrationOutput: NarrationElementOutput) {}

    companion object {
        const val HEIGHT = 20
        private val BG = 0xFF202020.toInt()
        private val BG_HOVER = 0xFF303030.toInt()
    }
}
