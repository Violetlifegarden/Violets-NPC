package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.npc.GlbNpcMenu
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * The NPC editor's client screen — the CustomNPC-style panel that opens on right-click (see
 * [com.huashanlunjian.glbrender.npc.GlbNpc.mobInteract]). It holds no item slots: the container exists purely to bind a
 * registered [net.minecraft.client.gui.screens.Screen] to a [net.minecraft.world.inventory.MenuType], and every edited
 * value travels over the Step5-3 C2S packets. Step5-4 renders only the current model id plus a Close button; the model
 * picker (Step5-5) and the transform/behavior tabs (Step5-6) build on this same panel.
 */
class GlbNpcEditorScreen(menu: GlbNpcMenu, inv: Inventory, title: Component) :
    AbstractContainerScreen<GlbNpcMenu>(menu, inv, title) {

    init {
        imageWidth = 176
        imageHeight = 120
    }

    override fun init() {
        super.init()
        addRenderableWidget(
            Button.builder(Component.literal("Close")) { onClose() }
                .pos(leftPos + imageWidth / 2 - 50, topPos + imageHeight - 30)
                .size(100, 20)
                .build(),
        )
    }

    override fun renderBg(g: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // A plain opaque panel: no texture atlas yet. 0xF0202020 ~= ARGB for the standard dark GUI backdrop.
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xF0202020.toInt())
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(g, mouseX, mouseY, partialTick)
        val modelId = menu.npc?.modelId
        val label = if (modelId.isNullOrEmpty()) "<default>" else modelId
        g.drawCenteredString(font, Component.literal("Model: $label"), leftPos + imageWidth / 2, topPos + 20, 0xFFFFFF)
    }
}
