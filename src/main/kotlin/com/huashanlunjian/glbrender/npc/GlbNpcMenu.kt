package com.huashanlunjian.glbrender.npc

import com.huashanlunjian.glbrender.GlbRenderContent
import com.huashanlunjian.glbrender.npc.net.GlbNpcEditing
import com.mojang.datafixers.util.Pair
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * The NPC editor's container: the 6-tab shell plus, on the Inventory tab, CustomNPC's `ContainerNPCInv` slot layout —
 * armor, main hand, projectile, off hand on the left, the nine drop-loot slots on the right, and the player's main
 * inventory + hotbar below. Slots only render and accept clicks while that tab is selected ([inventoryTabActive]);
 * `Slot.isActive` gates both the screen's slot loop and its hit testing, so no manual hiding is needed.
 *
 * Equipment slots write through to the entity's vanilla equipment ([GlbNpcEquipmentContainer]), while the projectile
 * and loot slots use [GlbNpc.storage]. The menu polls both containers every tick via the base `broadcastChanges`, which
 * is why neither container needs change listeners.
 *
 * The entity is resolved differently per side: the server constructs the menu with the live [npc] (from
 * `mobInteract`), the client resolves it from the entity id in the open-screen extra data via [client] (a client
 * `Level` indexes entities by network id, so `getEntity(int)` works — it is only UUID lookup that does not).
 */
class GlbNpcMenu(windowId: Int, val npc: GlbNpc?, playerInventory: Inventory) :
    AbstractContainerMenu(GlbRenderContent.GLB_NPC_MENU.get(), windowId) {

    /** Set by the editor screen when the Inventory tab is selected; gates every slot's visibility and hit box. */
    var inventoryTabActive = false

    private val equipment: Container = GlbNpcEquipmentContainer(npc)

    // Constant slot count: if the client cannot resolve the entity yet, the slots simply read as empty.
    private val storage: Container = npc?.storage ?: SimpleContainer(GlbNpcInventory.STORAGE_SLOTS)

    init {
        // CustomNPC ContainerNPCInv coordinates, verbatim (18px armor pitch, 21px drop-slot pitch).
        gatedSlot(equipment, 0, 9, 22, HELMET_ICON)
        gatedSlot(equipment, 1, 9, 40, CHEST_ICON)
        gatedSlot(equipment, 2, 9, 58, LEGS_ICON)
        gatedSlot(equipment, 3, 9, 76, BOOTS_ICON)
        gatedSlot(equipment, 4, 81, 22, SWORD_ICON)
        gatedSlot(storage, GlbNpcInventory.PROJECTILE, 81, 40, null)
        gatedSlot(equipment, 5, 81, 58, SHIELD_ICON)
        for (index in 0 until GlbNpcInventory.LOOT_SLOTS) {
            gatedSlot(storage, GlbNpcInventory.LOOT_START + index, 191, 16 + index * 21, null)
        }

        for (row in 0..2) {
            for (col in 0..8) {
                gatedSlot(playerInventory, col + row * 9 + 9, 8 + col * 18, 113 + row * 18, null)
            }
        }
        for (col in 0..8) {
            gatedSlot(playerInventory, col, 8 + col * 18, 171, null)
        }
    }

    private fun gatedSlot(
        container: Container,
        index: Int,
        x: Int,
        y: Int,
        icon: Pair<ResourceLocation, ResourceLocation>?,
    ) {
        addSlot(ScreenSlot(container, index, x, y, icon) { inventoryTabActive })
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val snapshot = stack.copy()
        val moved = when {
            index < GlbNpcInventory.NPC_SLOT_COUNT ->
                moveItemStackTo(stack, GlbNpcInventory.MENU_PLAYER_START, GlbNpcInventory.MENU_PLAYER_END, true)
            // Player -> NPC: matching equipment first (armor/hands, filtered by Slot.mayPlace), then the drop slots.
            else ->
                moveItemStackTo(stack, 0, GlbNpcInventory.MENU_LOOT_START, false) ||
                    moveItemStackTo(stack, GlbNpcInventory.MENU_LOOT_START, GlbNpcInventory.NPC_SLOT_COUNT, false)
        }
        if (!moved) return ItemStack.EMPTY
        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        // Return the pre-move snapshot: the base click handler loops while the source still holds the same item, which
        // is what lets a partial transfer (stack didn't fit in one target) continue into the next slots.
        return snapshot
    }

    override fun stillValid(player: Player): Boolean {
        val npc = npc ?: return false
        return !npc.isRemoved && player.canInteractWithEntity(npc, REACH)
    }

    override fun removed(player: Player) {
        super.removed(player)
        // End the editing session server-side so the Step5-3 packets stop authorizing this player for this NPC.
        if (!player.level().isClientSide) GlbNpcEditing.close(player)
    }

    /**
     * A slot that disappears (rendering + hit testing) while [active] is false. The ghost icon is vanilla's empty-slot
     * sprite for the equipment slots, so an empty hand/armor slot still reads as what it accepts.
     */
    private class ScreenSlot(
        container: Container,
        index: Int,
        x: Int,
        y: Int,
        private val icon: Pair<ResourceLocation, ResourceLocation>?,
        private val active: () -> Boolean,
    ) : Slot(container, index, x, y) {
        override fun isActive(): Boolean = active()

        override fun getNoItemIcon(): Pair<ResourceLocation, ResourceLocation>? = icon
    }

    companion object {
        const val REACH = 6.0

        private val HELMET_ICON = Pair.of(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_HELMET)
        private val CHEST_ICON = Pair.of(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE)
        private val LEGS_ICON = Pair.of(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS)
        private val BOOTS_ICON = Pair.of(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS)
        private val SWORD_ICON =
            Pair.of(InventoryMenu.BLOCK_ATLAS, ResourceLocation.withDefaultNamespace("item/empty_slot_sword"))
        private val SHIELD_ICON = Pair.of(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD)

        /** Client-side factory target: resolve the NPC from the network id written into the open-screen buffer. */
        fun client(windowId: Int, inv: Inventory, entityId: Int): GlbNpcMenu {
            val npc = inv.player.level().getEntity(entityId) as? GlbNpc
            return GlbNpcMenu(windowId, npc, inv)
        }
    }
}
