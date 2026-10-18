package com.huashanlunjian.glbrender.client.npc.editor

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/**
 * A CustomNPC-style yes/no toggle: a single control whose text flips between Yes and No and whose background tints by
 * state (CNPC's `GuiButtonYesNo`). Clicking flips [value] and fires [onChange]. The leading label ("Wander", …) is
 * drawn by the screen, not this widget. Visual only — no CNPC logic copied.
 */
class GlbToggleYesNo(
    x: Int,
    y: Int,
    width: Int,
    initial: Boolean,
    private val onChange: (Boolean) -> Unit,
) : AbstractWidget(x, y, width, HEIGHT, Component.empty()) {

    var value: Boolean = initial

    override fun onClick(mouseX: Double, mouseY: Double) {
        value = !value
        onChange(value)
    }

    override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val font = Minecraft.getInstance().font
        val hovered = mouseX in x..(x + width) && mouseY in y..(y + HEIGHT)
        val bg = if (value) YES_BG else NO_BG
        g.fill(x, y, x + width, y + HEIGHT, if (hovered) bg + 0x151515 else bg)
        val text = if (value) "Yes" else "No"
        g.drawCenteredString(font, text, x + width / 2, y + (HEIGHT - font.lineHeight) / 2, 0xFFFFFF)
    }

    override fun updateWidgetNarration(narrationOutput: NarrationElementOutput) {}

    companion object {
        const val HEIGHT = 20
        private val YES_BG = 0xFF2F6B2F.toInt()
        private val NO_BG = 0xFF6B2F2F.toInt()
    }
}
