package com.huashanlunjian.glbrender.client.npc.editor

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/**
 * A CustomNPC-style bi-directional stepper: a `<` hit-zone, the current value centered, and a `>` hit-zone (mirrors
 * CNPC's `GuiButtonBiDirectional` arrow buttons). Clicking an arrow moves [value] by `step`, clamps to the range, and
 * fires [onChange]. Visual/geometry only — no CNPC logic is copied.
 */
class GlbStepper(
    x: Int,
    y: Int,
    width: Int,
    initial: Float,
    private val step: Float,
    private val min: Float,
    private val max: Float,
    private val format: (Float) -> String,
    private val onChange: (Float) -> Unit,
) : AbstractWidget(x, y, width, HEIGHT, Component.empty()) {

    var value: Float = initial.coerceIn(min, max)

    override fun onClick(mouseX: Double, mouseY: Double) {
        val delta = when {
            mouseX < (x + ARROW).toDouble() -> -step
            mouseX > (x + width - ARROW).toDouble() -> step
            else -> 0f
        }
        if (delta != 0f) {
            value = (value + delta).coerceIn(min, max)
            onChange(value)
        }
    }

    override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val font = Minecraft.getInstance().font
        g.fill(x, y, x + width, y + HEIGHT, FIELD_BG)
        val hoverLeft = mouseX < x + ARROW
        val hoverRight = mouseX > x + width - ARROW
        g.fill(x, y, x + ARROW, y + HEIGHT, if (hoverLeft) ARROW_HOVER else ARROW_BG)
        g.fill(x + width - ARROW, y, x + width, y + HEIGHT, if (hoverRight) ARROW_HOVER else ARROW_BG)
        val textY = y + (HEIGHT - font.lineHeight) / 2
        g.drawString(font, "<", x + (ARROW - font.width("<")) / 2, textY, 0xFFFFFF, false)
        g.drawString(
            font, ">", x + width - ARROW + (ARROW - font.width(">")) / 2, textY, 0xFFFFFF, false,
        )
        g.drawCenteredString(font, format(value), x + width / 2, textY, 0xFFFF80)
    }

    override fun updateWidgetNarration(narrationOutput: NarrationElementOutput) {}

    companion object {
        const val HEIGHT = 20
        private const val ARROW = 18
        private val FIELD_BG = 0xFF202020.toInt()
        private val ARROW_BG = 0xFF3A3A3A.toInt()
        private val ARROW_HOVER = 0xFF5A5A5A.toInt()
    }
}
