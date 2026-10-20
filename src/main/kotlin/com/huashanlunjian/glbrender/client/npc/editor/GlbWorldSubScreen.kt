package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.npc.net.SetWorldSectionPacket
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Shared shell for the Global tab's world managers (Factions / Dialogs / Linked NPCs / Transport). Each manager edits a
 * local copy of one [com.huashanlunjian.glbrender.npc.world.GlbNpcWorldData] section and pushes the WHOLE section to
 * the server after every change; the server re-clamps it and broadcasts the authoritative snapshot, so the client
 * never has to reason about id allocation races.
 *
 * One entry is shown per page: a 420px panel only fits one name/color/coordinate block comfortably, and CustomNPC's
 * list-plus-detail layout inside one screen does not fit our fixed panel either.
 *
 * Edits are operator-gated on the SERVER (permission level 2). The bottom hint is the only client-side signal, because
 * a client cannot read its own permission level; a rejected edit simply never comes back in the next snapshot.
 */
abstract class GlbWorldSubScreen(
    title: String,
    protected val section: Int,
    private val returnTo: () -> Screen?,
) : Screen(Component.literal(title)) {

    protected var left = 0
    protected var top = 0
    protected var index = 0

    private val labels = ArrayList<Label>()

    private data class Label(val x: Int, val y: Int, val text: String, val color: Int)

    /** Number of entries in the local section copy. */
    protected abstract fun count(): Int

    /** Builds the field widgets for the entry at [index]; must tolerate an empty section. */
    protected abstract fun buildFields()

    protected abstract fun addEntry()

    protected abstract fun removeEntry(index: Int)

    /** Pushes the edited section to the server; called after every field change. */
    protected abstract fun commit()

    /** Label on the add button; Transport overrides it with "Add here" (captures the player's position). */
    protected open val addLabel: String get() = "Add"

    protected open val emptyHint: String get() = "Nothing here yet - press $addLabel."

    protected open val canRemove: Boolean get() = count() > 0

    override fun init() {
        super.init()
        left = (width - PANEL_W) / 2
        top = (height - PANEL_H) / 2
        labels.clear()
        index = index.coerceIn(0, (count() - 1).coerceAtLeast(0))

        addRenderableWidget(
            Button.builder(Component.literal("<")) { page(-1) }
                .bounds(left + 20, top + 26, 20, 20)
                .build()
                .also { it.active = count() > 1 },
        )
        addRenderableWidget(
            Button.builder(Component.literal(">")) { page(1) }
                .bounds(left + 144, top + 26, 20, 20)
                .build()
                .also { it.active = count() > 1 },
        )
        addRenderableWidget(
            Button.builder(Component.literal(addLabel)) {
                addEntry()
                rebuildWidgets()
            }.bounds(left + 210, top + 26, 90, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Remove")) {
                removeEntry(index)
                rebuildWidgets()
            }.bounds(left + 310, top + 26, 90, 20)
                .build()
                .also { it.active = canRemove },
        )
        buildFields()
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(left + PANEL_W - 66, top + PANEL_H - 26, 56, 20)
                .build(),
        )
    }

    private fun page(delta: Int) {
        val total = count()
        if (total <= 1) return
        index = Math.floorMod(index + delta, total)
        rebuildWidgets()
    }

    protected fun label(x: Int, y: Int, text: String, color: Int = COLOR_LABEL) {
        labels.add(Label(x, y, text, color))
    }

    /** A text field that commits on Enter and on focus loss (so clicking Done never drops an edit). */
    protected fun text(x: Int, y: Int, w: Int, initial: String, maxLength: Int, assign: (String) -> Unit) {
        addRenderableWidget(
            GlbCommitField(font, left + x, top + y, w, initial, maxLength, true) { value ->
                assign(value)
                commit()
            },
        )
    }

    protected fun sendSection(tag: CompoundTag) {
        PacketDistributor.sendToServer(SetWorldSectionPacket(section, tag))
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(g, mouseX, mouseY, partialTick)
        val right = left + PANEL_W
        val bottom = top + PANEL_H
        g.fill(left, top, right, bottom, PANEL_BG)
        g.fill(left, top, right, top + 1, PANEL_BORDER)
        g.fill(left, bottom - 1, right, bottom, PANEL_BORDER)
        g.fill(left, top, left + 1, bottom, PANEL_BORDER)
        g.fill(right - 1, top, right, bottom, PANEL_BORDER)
        g.drawCenteredString(font, title, left + PANEL_W / 2, top + 8, 0xFFFFFF)
        g.drawCenteredString(font, "${index + 1} / ${maxOf(count(), 1)}", left + 92, top + 32, 0xFFFFFF)
        labels.forEach { g.drawString(font, it.text, left + it.x, top + it.y, it.color, false) }
        if (count() == 0) {
            g.drawCenteredString(font, Component.literal(emptyHint), left + PANEL_W / 2, top + 110, COLOR_DIM)
        }
        g.drawString(font, OP_HINT, left + 20, top + 196, COLOR_DIM, false)
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
        private const val OP_HINT = "Global edits require operator permission (level 2)."
        private val PANEL_BG = 0xF01E1E1E.toInt()
        private val PANEL_BORDER = 0xFF5A5A5A.toInt()
    }
}
