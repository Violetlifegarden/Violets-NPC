package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.npc.GlbNpcAdvanced
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Shared shell for the three Advanced sub-screens (Lines / Sounds / Marks). It owns the 420x236 panel, the static label
 * list and the "Done" button, and edits the host editor's live [GlbNpcAdvanced] copy: every control change calls
 * [GlbNpcAdvanced]'s owner back through [onCommit] (one SetAdvancedPacket). Like the Stats sub-screens, closing
 * returns to a rebuilt editor over the still-open menu.
 */
abstract class GlbAdvancedSubScreen(
    title: String,
    protected val advanced: GlbNpcAdvanced,
    private val onCommit: () -> Unit,
    private val returnTo: () -> Screen?,
) : Screen(Component.literal(title)) {

    protected var left = 0
    protected var top = 0
    private val labels = ArrayList<Label>()

    private data class Label(val x: Int, val y: Int, val text: String, val color: Int = COLOR_LABEL)

    protected abstract fun build()

    override fun init() {
        super.init()
        left = (width - PANEL_W) / 2
        top = (height - PANEL_H) / 2
        labels.clear()
        build()
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(left + PANEL_W - 66, top + PANEL_H - 26, 56, 20)
                .build(),
        )
    }

    protected fun label(x: Int, y: Int, text: String, color: Int = COLOR_LABEL) {
        labels.add(Label(x, y, text, color))
    }

    /** Pushes the edited block to the server; called after every control change. */
    protected fun commit() = onCommit()

    /** A text field that commits on Enter and on focus loss (so clicking Done never drops an edit). */
    protected fun text(x: Int, y: Int, w: Int, initial: String, maxLength: Int, assign: (String) -> Unit) {
        addRenderableWidget(
            GlbCommitField(font, left + x, top + y, w, initial, maxLength, true) { value ->
                assign(value)
                commit()
            },
        )
    }

    protected fun cycle(x: Int, y: Int, w: Int, options: Array<String>, initial: Int, assign: (Int) -> Unit) {
        addRenderableWidget(
            GlbCycleButton(left + x, top + y, w, options, initial.coerceIn(0, options.lastIndex)) { index ->
                assign(index)
                commit()
            },
        )
    }

    protected fun toggle(x: Int, y: Int, w: Int, initial: Boolean, assign: (Boolean) -> Unit) {
        addRenderableWidget(
            GlbToggleYesNo(left + x, top + y, w, initial) { value ->
                assign(value)
                commit()
            },
        )
    }

    open override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(g, mouseX, mouseY, partialTick)
        val right = left + PANEL_W
        val bottom = top + PANEL_H
        g.fill(left, top, right, bottom, PANEL_BG)
        g.fill(left, top, right, top + 1, PANEL_BORDER)
        g.fill(left, bottom - 1, right, bottom, PANEL_BORDER)
        g.fill(left, top, left + 1, bottom, PANEL_BORDER)
        g.fill(right - 1, top, right, bottom, PANEL_BORDER)
        g.drawCenteredString(font, title, left + PANEL_W / 2, top + 8, 0xFFFFFF)
        labels.forEach { g.drawString(font, it.text, left + it.x, top + it.y, it.color, false) }
        renderables.forEach { it.render(g, mouseX, mouseY, partialTick) }
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        minecraft?.setScreen(returnTo())
    }

    companion object {
        const val PANEL_W = 420
        const val PANEL_H = 236
        const val COLOR_LABEL = 0xE0E0E0
        const val COLOR_DIM = 0x909090
        private val PANEL_BG = 0xF01E1E1E.toInt()
        private val PANEL_BORDER = 0xFF5A5A5A.toInt()
    }
}
