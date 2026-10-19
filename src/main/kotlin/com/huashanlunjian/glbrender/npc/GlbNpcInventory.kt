package com.huashanlunjian.glbrender.npc

import net.minecraft.world.Container
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * Fixed slot layout shared by the entity storage and the editor menu. The menu mirrors CustomNPC's
 * `ContainerNPCInv` order: armor, main hand, projectile, off hand, then the nine "NPC Inventory" drop slots.
 */
object GlbNpcInventory {
    const val LOOT_SLOTS = 9

    /** Storage index 0 is the projectile/ammo slot (CNPC weapon slot 1); 1..9 are the drop slots. */
    const val PROJECTILE = 0
    const val LOOT_START = 1
    const val STORAGE_SLOTS = LOOT_START + LOOT_SLOTS

    // Menu slot indices, in the order the menu registers them.
    const val MENU_MAIN_HAND = 4
    const val MENU_PROJECTILE = 5
    const val MENU_OFF_HAND = 6
    const val MENU_LOOT_START = 7
    const val NPC_SLOT_COUNT = MENU_LOOT_START + LOOT_SLOTS // 16

    // Player inventory slots follow the NPC slots (3x9 rows, then the hotbar), like InventoryMenu's order.
    const val MENU_PLAYER_START = NPC_SLOT_COUNT
    const val MENU_PLAYER_MAIN_END = MENU_PLAYER_START + 27
    const val MENU_PLAYER_END = MENU_PLAYER_MAIN_END + 9 // 52
}

/**
 * Exposes the NPC's six vanilla equipment slots as a [Container] so the editor can use plain
 * [net.minecraft.world.inventory.Slot]s. Writes go straight to `LivingEntity.setItemSlot`, which means the
 * client sees them through vanilla's equipment sync while the menu is closed, the equipment persists in NBT,
 * and armor feeds the normal damage-reduction path without any extra glue.
 *
 * A null [npc] is the client-side fallback when the menu is opened before the entity arrives: the slot count
 * must stay constant, so the container still reports six slots and simply reads as empty.
 */
class GlbNpcEquipmentContainer(private val npc: GlbNpc?) : Container {
    override fun getContainerSize(): Int = SLOTS.size

    override fun isEmpty(): Boolean = SLOTS.indices.all { getItem(it).isEmpty }

    override fun getItem(slot: Int): ItemStack =
        npc?.getItemBySlot(SLOTS[slot]) ?: ItemStack.EMPTY

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val stack = getItem(slot)
        if (stack.isEmpty || amount <= 0) return ItemStack.EMPTY
        val taken = stack.split(amount)
        setItem(slot, stack)
        return taken
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        val stack = getItem(slot)
        setItem(slot, ItemStack.EMPTY)
        return stack
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        npc?.setItemSlot(SLOTS[slot], stack)
    }

    /** Equipment is not a listener-backed container; the menu syncs by polling every tick. */
    override fun setChanged() {}

    override fun clearContent() {
        val entity = npc ?: return
        SLOTS.forEach { entity.setItemSlot(it, ItemStack.EMPTY) }
    }

    override fun stillValid(player: Player): Boolean = npc != null && !npc.isRemoved

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean {
        val entity = npc ?: return true
        val equipmentSlot = SLOTS[slot]
        // Hands accept anything; armor follows the same rule as vanilla ArmorSlot.
        return equipmentSlot.type != EquipmentSlot.Type.HUMANOID_ARMOR || stack.canEquip(equipmentSlot, entity)
    }

    companion object {
        val SLOTS = arrayOf(
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND,
        )
    }
}
