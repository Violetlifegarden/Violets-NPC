package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.client.npc.world.GlbNpcWorldCache
import com.huashanlunjian.glbrender.npc.world.GlbNpcWorldData
import net.minecraft.client.gui.screens.Screen

/**
 * Global → Linked NPCs. A flat list of names; the per-NPC Advanced block stores one of them as text. There is no entity
 * binding yet (CustomNPC's linked names drive scripts), so this only keeps the names available to the picker.
 */
class GlbWorldLinkedNamesSubScreen(returnTo: () -> Screen?) :
    GlbWorldSubScreen("Linked NPCs", GlbNpcWorldData.SECTION_LINKED, returnTo) {

    private val entries = GlbNpcWorldCache.linkedNames().toMutableList()

    override fun count() = entries.size

    override val emptyHint = "No linked names yet - press Add."

    override fun buildFields() {
        if (entries.isEmpty()) return
        label(20, 62, "Name")
        text(90, 58, 300, entries[index], GlbNpcWorldData.MAX_LINKED_NAME) { value ->
            if (value.isNotBlank()) entries[index] = value.trim()
        }
        label(20, 90, "NPCs pick one of these in Advanced -> Linked NPC.", COLOR_DIM)
    }

    override fun addEntry() {
        entries.add("NPC ${entries.size + 1}")
        index = entries.lastIndex
        commit()
    }

    override fun removeEntry(index: Int) {
        if (entries.isEmpty()) return
        entries.removeAt(index)
        this.index = this.index.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        commit()
    }

    override fun commit() = sendSection(GlbNpcWorldData.stringsTag(entries))
}
