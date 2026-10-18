package com.huashanlunjian.glbrender.client.npc.editor

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/**
 * A CustomNPC-style editor tab: a flat bar sitting on the tab strip above the panel. The selected tab renders at full
 * height in the highlight color; an unselected one is 2px shorter and gray (mirrors CNPC's `GuiMenuTopButton` active/idle
 * rows). Width is set by the screen to `font.width(label) + 12` and tabs are chained with no gap.
 *
 * Only the visual + click shell — behavior is the screen's `onPress` callback.
 */
class GlbTabButton(
    x: Int,
    y: Int,
    width: Int,
    message: Component,
    private val onPress: () -> Unit,
) : AbstractWidget(x, y, width, HEIGHT, message) {

    var selected: Boolean = false

    override fun onClick(mouseX: Double, mouseY: Double) {
        onPress()
    }

    override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val font = Minecraft.getInstance().font
        val top = if (selected) y else y + HEIGHT_OFFSET
        g.fill(x, top, x + width, y + HEIGHT, TAB_BG)
        val color = if (selected || isHovered) COLOR_ACTIVE else COLOR_IDLE
        g.drawCenteredString(font, message, x + width / 2, top + (HEIGHT - HEIGHT_OFFSET - font.lineHeight) / 2, color)
    }

    override fun updateWidgetNarration(narrationOutput: NarrationElementOutput) {}

    companion object {
        const val HEIGHT = 20

        /** How much shorter an idle tab is than the selected one (CNPC: 20 active vs 18 idle). */
        private const val HEIGHT_OFFSET = 2
        private val TAB_BG = 0xFF2A2A2A.toInt()
        private const val COLOR_ACTIVE = 0xFFFF00
        private const val COLOR_IDLE = 0xE0E0E0
    }
}
