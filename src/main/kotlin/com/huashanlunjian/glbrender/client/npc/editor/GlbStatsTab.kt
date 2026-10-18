package com.huashanlunjian.glbrender.client.npc.editor

/**
 * Stats tab — a faithful but non-functional replica of CustomNPC `GuiNpcStats`: the number/cycle/edit-button grid from
 * the decompile, every control a placeholder. We own none of CNPC's stat pipeline (health, aggro, resistances, …), so
 * this tab is present purely to show the surface the later feature pass will target.
 */
class GlbStatsTab(ctx: GlbEditorContext) : GlbEditorTab(ctx) {
    override val title = "Stats"

    override fun build() {
        var y = 10
        label(4, y + 5, "Health")
        stubField(85, y, 50)
        label(140, y + 5, "Aggro")
        stubField(220, y, 50)
        label(275, y + 5, "Creature type")
        stubCycle(355, y, 56, "Normal", "Undead", "Arthropod")
        y += 22

        label(4, y + 5, "Respawn")
        stubButton(82, y, 56, "Edit")
        y += 22

        label(4, y + 5, "Melee")
        stubButton(82, y, 56, "Edit")
        label(140, y + 5, "Ranged")
        stubButton(217, y, 56, "Edit")
        y += 22

        label(4, y + 5, "Resistance")
        stubButton(82, y, 56, "Edit")
        y += 22

        label(4, y + 5, "Fire immune")
        stubYesNo(82, y, 56, false)
        label(140, y + 5, "Can drown")
        stubYesNo(217, y, 56, false)
        label(275, y + 5, "Regen")
        stubField(355, y, 56)
        y += 22

        label(4, y + 5, "Burn in sun")
        stubYesNo(82, y, 56, false)
        label(140, y + 5, "No fall dmg")
        stubYesNo(217, y, 56, false)
        label(275, y + 5, "Combat regen")
        stubField(355, y, 56)
        y += 22

        label(4, y + 5, "Potion immune")
        stubYesNo(82, y, 56)
        label(140, y + 5, "Cobweb")
        stubYesNo(217, y, 56, false)
    }
}
