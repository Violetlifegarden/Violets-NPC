package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.npc.GlbNpcAi
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * CustomNPC `SubGuiNpcMovement` analogue: the AI tab's numeric movement fields live on their own screen. Moving type
 * maps onto the existing `wander` switch (Standing = no wandering), Range onto [GlbNpcAi.walkingRange], and Speed onto
 * [GlbNpcAi.moveSpeed] (CNPC units: the attribute is `moveSpeed / 20`).
 *
 * It edits the host editor's live copies and calls back after every change, so each click commits immediately; the
 * editor is rebuilt over the still-open menu on close, the same pattern as the Stats sub-screens.
 */
class GlbMovementSubScreen(
    private val ai: GlbNpcAi,
    private var wander: Boolean,
    private val onAiCommit: () -> Unit,
    private val onWanderCommit: (Boolean) -> Unit,
    private val returnTo: () -> Screen?,
) : Screen(Component.literal("Movement")) {

    private var left = 0
    private var top = 0
    private val labels = ArrayList<Label>()

    private data class Label(val x: Int, val y: Int, val text: String, val color: Int = COLOR_LABEL)

    override fun init() {
        super.init()
        left = (width - PANEL_W) / 2
        top = (height - PANEL_H) / 2
        labels.clear()
        var y = 34
        label(20, y + 5, "Moving type")
        addRenderableWidget(
            GlbCycleButton(
                left + 150, top + y, 140,
                GlbNpcAi.MOVEMENT_TYPE_OPTIONS,
                if (wander) 1 else 0,
            ) { index ->
                wander = index == 1
                onWanderCommit(wander)
            },
        )
        y += 24
        label(20, y + 5, "Range")
        addRenderableWidget(
            GlbStepper(
                left + 150, top + y, 100,
                ai.walkingRange.toFloat(), 1f, 1f, GlbNpcAi.MAX_WALKING_RANGE.toFloat(),
                { fmt(it, 0) },
            ) { value ->
                ai.walkingRange = value.toInt()
                onAiCommit()
            },
        )
        label(260, y + 5, "blocks from home", COLOR_DIM)
        y += 24
        label(20, y + 5, "Speed")
        addRenderableWidget(
            GlbStepper(
                left + 150, top + y, 100,
                ai.moveSpeed.toFloat(), 1f, 0f, GlbNpcAi.MAX_MOVE_SPEED.toFloat(),
                { fmt(it, 0) },
            ) { value ->
                ai.moveSpeed = value.toInt()
                onAiCommit()
            },
        )
        label(260, y + 5, "CNPC units", COLOR_DIM)
        label(20, y + 38, "Range is the walking radius; Return walks the NPC back inside it.", COLOR_DIM)
        label(20, y + 50, "Speed 5 = 0.25 blocks/tick (vanilla walking).", COLOR_DIM)
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(left + PANEL_W - 66, top + PANEL_H - 26, 56, 20)
                .build(),
        )
    }

    private fun label(x: Int, y: Int, text: String, color: Int = COLOR_LABEL) {
        labels.add(Label(x, y, text, color))
    }

    private fun fmt(value: Float, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

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
        labels.forEach { g.drawString(font, it.text, left + it.x, top + it.y, it.color, false) }
        renderables.forEach { it.render(g, mouseX, mouseY, partialTick) }
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        minecraft?.setScreen(returnTo())
    }

    companion object {
        private const val PANEL_W = 420
        private const val PANEL_H = 200
        private val PANEL_BG = 0xF01E1E1E.toInt()
        private val PANEL_BORDER = 0xFF5A5A5A.toInt()
        private const val COLOR_LABEL = 0xE0E0E0
        private const val COLOR_DIM = 0x909090
    }
}
