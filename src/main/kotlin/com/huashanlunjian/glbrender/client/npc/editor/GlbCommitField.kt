package com.huashanlunjian.glbrender.client.npc.editor

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component

/**
 * A CustomNPC-style editable text field (`GuiTextFieldNop`) that commits on Enter — the one placeholder-turned-real
 * input in the editor (the Display·Name row). It deliberately does NOT send a packet on every keystroke (that would
 * spam the server); it fires [onCommit] only when the player presses Enter, mirroring how CNPC defers the save to
 * focus-loss / tab-switch. Sub-screens whose text must survive clicking "Done" straight away pass [commitOnBlur].
 */
class GlbCommitField(
    font: Font,
    x: Int,
    y: Int,
    width: Int,
    initial: String,
    private val maxLength: Int = MAX_LENGTH,
    private val commitOnBlur: Boolean = false,
    private val onCommit: (String) -> Unit,
) : EditBox(font, x, y, width, HEIGHT, Component.empty()) {

    init {
        setMaxLength(maxLength)
        value = initial
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (isEnter(keyCode)) {
            onCommit(value)
            setFocused(false)
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun setFocused(focused: Boolean) {
        val wasFocused = isFocused
        super.setFocused(focused)
        if (commitOnBlur && wasFocused && !focused) onCommit(value)
    }

    private fun isEnter(keyCode: Int): Boolean =
        keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER

    companion object {
        const val HEIGHT = 18
        const val MAX_LENGTH = 64
    }
}
