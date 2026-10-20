package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.client.npc.world.GlbNpcWorldCache
import com.huashanlunjian.glbrender.npc.GlbNpcAdvanced
import net.minecraft.client.gui.screens.Screen

/**
 * Advanced → Factions (CustomNPC `GuiNpcFactions`): assigns the NPC one world faction and flips the two behavior
 * switches. Factions themselves are created/renamed in Global → Factions; here only the id is stored, so a rename
 * there updates this label immediately.
 */
class GlbFactionSubScreen(
    advanced: GlbNpcAdvanced,
    onCommit: () -> Unit,
    returnTo: () -> Screen?,
) : GlbAdvancedSubScreen("Factions", advanced, onCommit, returnTo) {

    override fun build() {
        val factions = GlbNpcWorldCache.factions()
        val options = arrayOf(NONE_LABEL) + factions.map { it.name }.toTypedArray()
        val initial = factions.indexOfFirst { it.id == advanced.faction } + 1

        label(20, 34, "Faction")
        cycle(150, 28, 240, options, initial.coerceAtLeast(0)) { index ->
            advanced.faction = factions.getOrNull(index - 1)?.id ?: GlbNpcAdvanced.FACTION_NONE
        }

        label(20, 62, "Attack other factions")
        toggle(150, 56, 80, advanced.attackOtherFactions) { advanced.attackOtherFactions = it }

        label(20, 90, "Defending faction")
        toggle(150, 84, 80, advanced.defendFaction) { advanced.defendFaction = it }

        val current = factions.firstOrNull { it.id == advanced.faction }
        if (current != null) {
            label(20, 124, "Color", COLOR_DIM)
            label(150, 124, "#%06X".format(current.color and 0xFFFFFF))
        } else {
            label(20, 124, "None = neutral: no allies, never attacks over faction.", COLOR_DIM)
        }
        label(20, 160, "Create and recolor factions in Global -> Factions.", COLOR_DIM)
    }

    private companion object {
        const val NONE_LABEL = "None"
    }
}
