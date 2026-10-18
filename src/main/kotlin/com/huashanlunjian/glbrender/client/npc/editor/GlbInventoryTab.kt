package com.huashanlunjian.glbrender.client.npc.editor

/**
 * Inventory tab — the static chrome of CustomNPC `GuiNPCInv` (exp fields, drop-chance rows, section labels) as
 * placeholders. CNPC's real item slots come from its container; our [com.huashanlunjian.glbrender.npc.GlbNpcMenu] is
 * slot-less, so there are no slots to render and the region is left as an empty framed box. Behavior deferred.
 */
class GlbInventoryTab(ctx: GlbEditorContext) : GlbEditorTab(ctx) {
    override val title = "Inventory"

    override fun build() {
        label(118, 23, "Min EXP")
        stubField(108, 18, 60)
        label(118, 57, "Max EXP")
        stubField(108, 52, 60)
        stubCycle(88, 88, 80, "Normal", "Auto")
        label(191, 5, "NPC Inventory")
        label(8, 101, "Inventory")

        for (i in 0 until 9) {
            val y = 19 + i * 21
            label(211, y + 5, "Drop ${i + 1}")
            stubField(301, y, 60)
        }
    }
}
