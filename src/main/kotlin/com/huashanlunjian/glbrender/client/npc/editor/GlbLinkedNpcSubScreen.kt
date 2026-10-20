package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.client.npc.world.GlbNpcWorldCache
import com.huashanlunjian.glbrender.npc.GlbNpcAdvanced
import net.minecraft.client.gui.screens.Screen

/**
 * Advanced → Linked NPC: picks one name from the world linked-NPC list. CNPC uses the link as a scripting hook; we
 * store and display it so the Advanced surface is complete, but no behavior consumes it yet.
 */
class GlbLinkedNpcSubScreen(
    advanced: GlbNpcAdvanced,
    onCommit: () -> Unit,
    returnTo: () -> Screen?,
) : GlbAdvancedSubScreen("Linked NPC", advanced, onCommit, returnTo) {

    override fun build() {
        val names = GlbNpcWorldCache.linkedNames()
        val options = arrayOf(NONE_LABEL) + names.toTypedArray()
        val initial = names.indexOf(advanced.linkedName) + 1

        label(20, 34, "Linked NPC")
        cycle(150, 28, 240, options, initial.coerceAtLeast(0)) { index ->
            advanced.linkedName = names.getOrNull(index - 1) ?: ""
        }

        label(20, 72, "Names are managed in Global -> Linked NPCs.", COLOR_DIM)
        if (advanced.linkedName.isNotEmpty()) {
            label(20, 96, "Currently linked to: ${advanced.linkedName}")
        }
    }

    private companion object {
        const val NONE_LABEL = "None"
    }
}
