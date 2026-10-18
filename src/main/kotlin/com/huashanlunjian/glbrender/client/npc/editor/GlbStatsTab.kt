package com.huashanlunjian.glbrender.client.npc.editor

/**
 * Stats tab — a replica of CustomNPC `GuiNpcStats`' number/cycle/edit-button grid. Only Health is functional (a real
 * [stepper] over the synced max-health override); every other control (aggro, resistances, creature type, …) is a
 * placeholder, since we own none of CNPC's stat pipeline yet.
 */
class GlbStatsTab(ctx: GlbEditorContext) : GlbEditorTab(ctx) {
    override val title = "Stats"

    override fun build() {
        var y = 10
        label(4, y + 5, "Health")
        stepper(85, y, 60, ctx.maxHealth, HEALTH_STEP, HEALTH_MIN, HEALTH_MAX, 0) { ctx.maxHealth = it }
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
