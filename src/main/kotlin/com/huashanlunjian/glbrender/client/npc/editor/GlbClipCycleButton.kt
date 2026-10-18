package com.huashanlunjian.glbrender.client.npc.editor

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/**
 * The animation-preview counterpart of [GlbCycleButton]: unlike the model picker it reads its options live, because
 * the clip list depends on the NPC's current model and only exists after that model's scene finishes loading. Clicking
 * walks `Auto -> clip 1 -> ... -> clip n -> Auto`, with `Auto` represented by the empty string.
 */
class GlbClipCycleButton(
    x: Int,
    y: Int,
    width: Int,
    private val options: () -> List<String>,
    private val current: () -> String,
    private val onSelect: (String) -> Unit,
) : AbstractWidget(x, y, width, HEIGHT, Component.empty()) {

    override fun onClick(mouseX: Double, mouseY: Double) {
        val entries = listOf("") + options()
        // A stale name (model switched, or its clip disappeared) maps to -1 and therefore wraps back to Auto.
        val index = entries.indexOfFirst { it.equals(current(), ignoreCase = true) }
        onSelect(entries[(index + 1).mod(entries.size)])
    }

    override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val font = Minecraft.getInstance().font
        val hovered = mouseX in x..(x + width) && mouseY in y..(y + HEIGHT)
        g.fill(x, y, x + width, y + HEIGHT, if (hovered) BG_HOVER else BG)
        val name = current()
        val text = name.ifEmpty { "Auto" }
        g.drawCenteredString(font, text, x + width / 2, y + (HEIGHT - font.lineHeight) / 2, 0xFFFF80)
    }

    override fun updateWidgetNarration(narrationOutput: NarrationElementOutput) {}

    companion object {
        const val HEIGHT = 20
        private val BG = 0xFF202020.toInt()
        private val BG_HOVER = 0xFF303030.toInt()
    }
}
