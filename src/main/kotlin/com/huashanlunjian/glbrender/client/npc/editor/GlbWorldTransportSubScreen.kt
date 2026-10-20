package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.client.npc.world.GlbNpcWorldCache
import com.huashanlunjian.glbrender.npc.world.GlbNpcWorldData
import com.huashanlunjian.glbrender.npc.world.GlbTransportLocation
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

/**
 * Global → Transport Locations. Named waypoints a transporter role would move NPCs to; the transporter role itself is
 * still a placeholder, so for now these are data plus an "add the spot I'm standing on" shortcut.
 */
class GlbWorldTransportSubScreen(returnTo: () -> Screen?) :
    GlbWorldSubScreen("Transport", GlbNpcWorldData.SECTION_TRANSPORT, returnTo) {

    private val entries = GlbNpcWorldCache.transport().map { it.copyLocation() }.toMutableList()

    override fun count() = entries.size

    override val addLabel = "Add here"

    override val emptyHint = "No locations yet - press Add here."

    override fun buildFields() {
        if (entries.isEmpty()) return
        val location = entries[index]
        label(20, 62, "Id", COLOR_DIM)
        label(90, 62, location.id.toString())
        label(20, 86, "Name")
        text(90, 82, 300, location.name, GlbTransportLocation.MAX_NAME) { value ->
            if (value.isNotBlank()) location.name = value.trim()
        }
        label(20, 110, "Dimension")
        text(90, 106, 300, location.dimension, MAX_DIMENSION_FIELD) { location.dimension = it.trim() }
        label(90, 128, "X")
        label(190, 128, "Y")
        label(290, 128, "Z")
        text(90, 138, 90, coord(location.x), MAX_COORD_FIELD) { value ->
            value.trim().toDoubleOrNull()?.let { location.x = it }
        }
        text(190, 138, 90, coord(location.y), MAX_COORD_FIELD) { value ->
            value.trim().toDoubleOrNull()?.let { location.y = it }
        }
        text(290, 138, 90, coord(location.z), MAX_COORD_FIELD) { value ->
            value.trim().toDoubleOrNull()?.let { location.z = it }
        }
        label(20, 164, "Add here stores your current position and dimension.", COLOR_DIM)
    }

    override fun addEntry() {
        val player = Minecraft.getInstance().player ?: return
        val id = (entries.maxOfOrNull { it.id } ?: 0) + 1
        entries.add(
            GlbTransportLocation(
                id = id,
                name = "Location $id",
                dimension = player.level().dimension().location().toString(),
                x = player.x,
                y = player.y,
                z = player.z,
            ),
        )
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

    private fun coord(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString()
        else String.format(java.util.Locale.ROOT, "%.2f", value)

    private companion object {
        const val MAX_DIMENSION_FIELD = 64
        const val MAX_COORD_FIELD = 16
    }
}
