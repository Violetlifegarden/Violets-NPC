package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.npc.GlbModels
import com.huashanlunjian.glbrender.npc.GlbNpcMenu
import com.huashanlunjian.glbrender.npc.net.SetModelPacket
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.neoforged.neoforge.network.PacketDistributor

/**
 * The NPC editor's client screen — the CustomNPC-style panel that opens on right-click (see
 * [com.huashanlunjian.glbrender.npc.GlbNpc.mobInteract]). It holds no item slots: the container exists purely to bind a
 * registered [net.minecraft.client.gui.screens.Screen] to a [net.minecraft.world.inventory.MenuType], and every edited
 * value travels over the Step5-3 C2S packets.
 *
 * The model grid lists every id in the common [GlbModels] table; clicking one sends [SetModelPacket]. The server
 * applies it to the NPC's synced `modelId`, which auto-replicates back, so the "Model:" label and the world NPC update
 * live with no extra client work. A detached TLM-style picker screen with an [net.minecraft.client.gui.screens.inventory.InventoryScreen]
 * 3D preview was considered but deferred: opening a second [Screen] over a live container churns the slot-listener and
 * widget lifecycle, and rendering a GLB NPC inside GUI space is a separate effort from the in-world render path.
 */
class GlbNpcEditorScreen(menu: GlbNpcMenu, inv: Inventory, title: Component) :
    AbstractContainerScreen<GlbNpcMenu>(menu, inv, title) {

    private val modelIds: List<String> = GlbModels.ids()

    init {
        imageWidth = 176
        // Grow the panel to fit the model grid (2 columns), leaving room for the header and the Close button.
        val rows = if (modelIds.isEmpty()) 0 else (modelIds.size + GRID_COLUMNS - 1) / GRID_COLUMNS
        imageHeight = (GRID_TOP + rows * GRID_ROW_HEIGHT + 40).coerceAtLeast(120)
    }

    override fun init() {
        super.init()
        val npcId = menu.npc?.id

        modelIds.forEachIndexed { index, id ->
            val col = index % GRID_COLUMNS
            val row = index / GRID_COLUMNS
            val x = leftPos + GRID_LEFT + col * GRID_COLUMN_STRIDE
            val y = topPos + GRID_TOP + row * GRID_ROW_HEIGHT
            addRenderableWidget(
                Button.builder(Component.literal(id)) {
                    if (npcId != null) PacketDistributor.sendToServer(SetModelPacket(npcId, id))
                }
                    .pos(x, y)
                    .size(GRID_BUTTON_WIDTH, 20)
                    .build(),
            )
        }

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
        g.drawCenteredString(font, Component.literal("Model: $label"), leftPos + imageWidth / 2, topPos + 12, 0xFFFFFF)
    }

    private companion object {
        const val GRID_COLUMNS = 2
        const val GRID_LEFT = 8
        const val GRID_TOP = 24
        const val GRID_COLUMN_STRIDE = 84
        const val GRID_ROW_HEIGHT = 24
        const val GRID_BUTTON_WIDTH = 80
    }
}
