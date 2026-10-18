package com.huashanlunjian.glbrender.client.npc

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * A minimal CustomNPC-style dialog box: the NPC's name over the current line of text, advanced one line per click or
 * Enter and closing after the last. Unlike the editor it opens straight from the client interaction (the dialog lines
 * are already synced on the NPC's config), so it carries no container/menu and does no server round trip.
 */
class GlbDialogScreen(
    private val npcName: Component,
    private val lines: List<String>,
) : Screen(Component.literal("Dialog")) {

    private var index = 0

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(g, mouseX, mouseY, partialTick)
        val boxWidth = (width * 3) / 5
        if (boxWidth < MIN_BOX_WIDTH) {
            // Screen too narrow to lay out the box text; wait for a resize rather than clip.
            return
        }
        val text = Component.literal(lines.getOrElse(index) { "" })
        val textHeight = font.split(text, boxWidth - 16).size * font.lineHeight
        val boxHeight = NAME_LINE_GAP + textHeight + HINT_LINE_GAP
        val x = (width - boxWidth) / 2
        val y = height - BOX_MARGIN - boxHeight
        g.fill(x - 2, y - 2, x + boxWidth + 2, y + boxHeight + 2, PANEL_BG)
        g.drawCenteredString(font, npcName, width / 2, y + 6, NAME_COLOR)
        g.drawWordWrap(font, text, x + 8, y + NAME_LINE_GAP, boxWidth - 16, TEXT_COLOR)
        val hint = if (index + 1 < lines.size) CONTINUE_HINT else CLOSE_HINT
        g.drawCenteredString(font, Component.literal(hint), width / 2, y + boxHeight - 12, HINT_COLOR)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        advance()
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        return if (keyCode == KEY_ENTER || keyCode == KEY_SPACE) {
            advance()
            true
        } else {
            super.keyPressed(keyCode, scanCode, modifiers)
        }
    }

    private fun advance() {
        index++
        if (index >= lines.size) {
            onClose()
        }
    }

    private companion object {
        const val MIN_BOX_WIDTH = 120
        const val BOX_MARGIN = 40
        const val NAME_LINE_GAP = 22
        const val HINT_LINE_GAP = 22

        const val NAME_COLOR = 0xFFEFB4
        const val TEXT_COLOR = 0xF0F0F0
        const val HINT_COLOR = 0xA0A0A0
        val PANEL_BG = 0xE01E1E1E.toInt()

        const val CONTINUE_HINT = "[ click / enter to continue ]"
        const val CLOSE_HINT = "[ click / enter to close ]"

        const val KEY_ENTER = 257
        const val KEY_SPACE = 32
    }
}
