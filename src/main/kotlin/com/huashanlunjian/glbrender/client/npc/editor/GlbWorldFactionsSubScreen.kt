package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.client.npc.world.GlbNpcWorldCache
import com.huashanlunjian.glbrender.npc.world.GlbFaction
import com.huashanlunjian.glbrender.npc.world.GlbNpcWorldData
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import java.util.Locale

/**
 * Global → Factions. Creates, renames and recolors the world factions an NPC can be assigned in Advanced → Factions.
 * Deleting a faction here leaves member NPCs pointing at a dead id, which reads as neutral until they are reassigned.
 */
class GlbWorldFactionsSubScreen(returnTo: () -> Screen?) :
    GlbWorldSubScreen("Factions", GlbNpcWorldData.SECTION_FACTIONS, returnTo) {

    private val entries = GlbNpcWorldCache.factions().map { it.copyFaction() }.toMutableList()

    override fun count() = entries.size

    override fun buildFields() {
        if (entries.isEmpty()) return
        val faction = entries[index]
        label(20, 62, "Id", COLOR_DIM)
        label(90, 62, faction.id.toString())
        label(20, 86, "Name")
        text(90, 82, 300, faction.name, GlbFaction.MAX_NAME) { value ->
            if (value.isNotBlank()) faction.name = value.trim()
        }
        label(20, 110, "Color (hex)")
        text(90, 106, 80, hex(faction.color), 6) { value ->
            value.trim().removePrefix("#").toIntOrNull(16)?.let { faction.color = it and 0xFFFFFF }
        }
        label(20, 146, "Members are assigned in Advanced -> Factions.", COLOR_DIM)
    }

    override fun addEntry() {
        val id = (entries.maxOfOrNull { it.id } ?: 0) + 1
        entries.add(GlbFaction(id, "Faction $id", GlbFaction.DEFAULT_COLOR))
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

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(g, mouseX, mouseY, partialTick)
        entries.getOrNull(index)?.let { faction ->
            g.fill(left + 180, top + 106, left + 204, top + 118, OPAQUE or (faction.color and 0xFFFFFF))
        }
    }

    private fun hex(color: Int): String = String.format(Locale.ROOT, "%06X", color and 0xFFFFFF)

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
    }
}
