package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.npc.GlbNpcConfig
import com.huashanlunjian.glbrender.npc.GlbNpcInventory

/**
 * Inventory tab — CustomNPC `GuiNPCInv` layout and semantics. The item slots themselves are menu slots owned by the
 * screen (`GlbNpcMenu`: armor/hands on the left, nine drop slots on the right, the player inventory below); this tab
 * only draws the chrome between them and wires the nine per-slot death-drop chance fields. Min/Max EXP and the loot
 * mode cycle stay CNPC placeholders (not implemented).
 */
class GlbInventoryTab(ctx: GlbEditorContext) : GlbEditorTab(ctx) {
    override val title = "Inventory"

    override fun build() {
        // CustomNPC GuiNPCInv coordinates, verbatim.
        label(118, 18, "Min EXP")
        stubField(108, 29, 60, "0")
        label(118, 52, "Max EXP")
        stubField(108, 63, 60, "0")
        stubCycle(88, 88, 80, "Normal", "Auto")
        label(191, 5, "NPC Inventory")
        label(8, 101, "Inventory")

        val chances = ctx.dropChances.split('|')
        for (index in 0 until GlbNpcInventory.LOOT_SLOTS) {
            label(211, 19 + index * 21, "Drop ${index + 1}")
            val initial = chances.getOrNull(index)?.trim().orEmpty()
                .ifEmpty { ctx.fmt(GlbNpcConfig.DEFAULT_DROP_CHANCE, 1) }
            // Commits on Enter (no per-keystroke packet); the server clamps to 0..100 and echoes the value back.
            textField(301, 14 + index * 21, 60, initial) { raw ->
                ctx.setDropChance(index, raw.trim().toFloatOrNull() ?: GlbNpcConfig.DEFAULT_DROP_CHANCE)
                ctx.applyConfig()
            }
        }
    }
}
