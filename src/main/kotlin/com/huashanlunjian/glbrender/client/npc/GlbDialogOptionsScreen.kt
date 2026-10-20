package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.npc.world.GlbDialogDef
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * The talk chooser for an NPC whose Advanced dialog slots point at world dialogs (CNPC's dialog options). Each slot
 * becomes a button; clicking one hands its lines to the existing [GlbDialogScreen]. The definitions come from
 * [com.huashanlunjian.glbrender.client.npc.world.GlbNpcWorldCache], so opening this screen needs no server round trip.
 */
class GlbDialogOptionsScreen(
    private val npcName: Component,
    private val dialogs: List<GlbDialogDef>,
) : Screen(Component.literal("Dialogs")) {

    override fun init() {
        super.init()
        val buttonWidth = 220
        val startY = height / 2 - dialogs.size * ROW_HEIGHT / 2
        dialogs.forEachIndexed { index, dialog ->
            addRenderableWidget(
                Button.builder(Component.literal(dialog.title)) {
                    minecraft?.setScreen(GlbDialogScreen(npcName, dialog.displayLines()))
                }.bounds((width - buttonWidth) / 2, startY + index * ROW_HEIGHT, buttonWidth, 20).build(),
            )
        }
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(g, mouseX, mouseY, partialTick)
        g.drawCenteredString(font, npcName, width / 2, height / 2 - dialogs.size * ROW_HEIGHT / 2 - 36, NAME_COLOR)
        g.drawCenteredString(font, Component.literal("Choose a dialog"), width / 2, height / 2 - dialogs.size * ROW_HEIGHT / 2 - 20, HINT_COLOR)
        renderables.forEach { it.render(g, mouseX, mouseY, partialTick) }
    }

    private companion object {
        const val ROW_HEIGHT = 24
        const val NAME_COLOR = 0xFFEFB4
        const val HINT_COLOR = 0xA0A0A0
    }
}
