package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.client.npc.world.GlbNpcWorldCache
import com.huashanlunjian.glbrender.npc.world.GlbDialogDef
import com.huashanlunjian.glbrender.npc.world.GlbNpcWorldData
import net.minecraft.client.gui.screens.Screen

/**
 * Global → Dialogs. A dialog is a title plus the lines the talk screen walks one click at a time; the NPC's Advanced →
 * Dialogs slots pick which ones a plain right-click offers. Lines are edited as one `|`-separated string because a
 * 420px panel has no room for sixteen editable rows.
 */
class GlbWorldDialogsSubScreen(returnTo: () -> Screen?) :
    GlbWorldSubScreen("Dialogs", GlbNpcWorldData.SECTION_DIALOGS, returnTo) {

    private val entries = GlbNpcWorldCache.dialogs().map { it.copyDialog() }.toMutableList()

    override fun count() = entries.size

    override fun buildFields() {
        if (entries.isEmpty()) return
        val dialog = entries[index]
        label(20, 62, "Id", COLOR_DIM)
        label(90, 62, dialog.id.toString())
        label(20, 86, "Title")
        text(90, 82, 300, dialog.title, GlbDialogDef.MAX_TITLE) { value ->
            if (value.isNotBlank()) dialog.title = value.trim()
        }
        label(20, 110, "Lines")
        text(90, 106, 300, dialog.lines.joinToString(LINE_SEPARATOR), MAX_LINES_FIELD) { value ->
            dialog.lines = value.split(LINE_SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(GlbDialogDef.MAX_LINES)
                .toMutableList()
        }
        label(20, 134, "${dialog.lines.size} line(s), separated by '$LINE_SEPARATOR'.", COLOR_DIM)
        label(20, 160, "NPCs offer dialogs in Advanced -> Dialogs.", COLOR_DIM)
    }

    override fun addEntry() {
        val id = (entries.maxOfOrNull { it.id } ?: 0) + 1
        entries.add(GlbDialogDef(id, "Dialog $id", mutableListOf()))
        index = entries.lastIndex
        commit()
    }

    override fun removeEntry(index: Int) {
        if (entries.isEmpty()) return
        entries.removeAt(index)
        this.index = this.index.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        commit()
    }

    override fun commit() = sendSection(GlbNpcWorldData.entriesTag(entries.map { it.toTag() }))

    private companion object {
        const val LINE_SEPARATOR = "|"

        /** Room for [GlbDialogDef.MAX_LINES] lines that are mostly shorter than [GlbDialogDef.MAX_LINE_LENGTH]. */
        const val MAX_LINES_FIELD = 2048
    }
}
