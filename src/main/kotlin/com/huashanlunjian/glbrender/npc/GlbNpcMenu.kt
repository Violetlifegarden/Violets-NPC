package com.huashanlunjian.glbrender.npc

import com.huashanlunjian.glbrender.GlbRenderContent
import com.huashanlunjian.glbrender.npc.net.GlbNpcEditing
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack

/**
 * The NPC editor's container. It holds no item slots — it exists purely so the client gets a registered
 * [net.minecraft.client.gui.screens.Screen] bound to a [net.minecraft.world.inventory.MenuType] and the server gets a
 * `stillValid` gate, exactly like TouhouLittleMaid's config-only tabs. The edited values travel over the Step5-3 C2S
 * packets, not through container slots.
 *
 * The entity is resolved differently per side: the server constructs the menu with the live [npc] (from
 * `mobInteract`), the client resolves it from the entity id in the open-screen extra data via [client] (a client
 * `Level` indexes entities by network id, so `getEntity(int)` works — it is only UUID lookup that does not).
 */
class GlbNpcMenu(windowId: Int, val npc: GlbNpc?) :
    AbstractContainerMenu(GlbRenderContent.GLB_NPC_MENU.get(), windowId) {

    override fun stillValid(player: Player): Boolean {
        val npc = npc ?: return false
        return !npc.isRemoved && player.canInteractWithEntity(npc, REACH)
    }

    // No item slots, so shift-click has nothing to move. The base method is abstract and must be overridden.
    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun removed(player: Player) {
        super.removed(player)
        // End the editing session server-side so the Step5-3 packets stop authorizing this player for this NPC.
        if (!player.level().isClientSide) GlbNpcEditing.close(player)
    }

    companion object {
        const val REACH = 6.0

        /** Client-side factory target: resolve the NPC from the network id written into the open-screen buffer. */
        fun client(windowId: Int, inv: Inventory, entityId: Int): GlbNpcMenu {
            val npc = inv.player.level().getEntity(entityId) as? GlbNpc
            return GlbNpcMenu(windowId, npc)
        }
    }
}
