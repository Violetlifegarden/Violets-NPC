package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.client.npc.world.GlbNpcWorldCache
import com.huashanlunjian.glbrender.npc.GlbNpcAdvanced
import net.minecraft.client.gui.screens.Screen

/**
 * Advanced → Dialogs (CustomNPC's twelve option slots): each slot binds one world dialog id, and a plain right-click
 * on the NPC offers the filled slots in a chooser. The twelve rows are split over two pages because a 20px cycle
 * control only fits six per panel. Empty slots are simply not offered.
 */
class GlbNpcDialogsSubScreen(
    advanced: GlbNpcAdvanced,
    onCommit: () -> Unit,
    returnTo: () -> Screen?,
) : GlbAdvancedSubScreen("Dialogs", advanced, onCommit, returnTo) {

    private var page = 0

    override fun build() {
        val dialogs = GlbNpcWorldCache.dialogs()
        val options = arrayOf(NONE_LABEL) + dialogs.map { it.title }.toTypedArray()

        // Page switch is local state only; going through cycle() would send an unchanged advanced packet.
        addRenderableWidget(
            GlbCycleButton(left + 150, top + 8, 160, PAGE_LABELS, page) { index ->
                page = index
                rebuildWidgets()
            },
        )

        val first = page * SLOTS_PER_PAGE
        for (row in 0 until SLOTS_PER_PAGE) {
            val slot = first + row
            val y = 40 + row * ROW_PITCH
            label(20, y + 6, "Slot ${slot + 1}")
            val initial = dialogs.indexOfFirst { it.id == advanced.dialogIds[slot] } + 1
            cycle(130, y, 250, options, initial.coerceAtLeast(0)) { index ->
                advanced.dialogIds[slot] = dialogs.getOrNull(index - 1)?.id ?: 0
            }
        }

        label(20, 202, "Dialogs are defined in Global -> Dialogs.", COLOR_DIM)
    }

    private companion object {
        const val NONE_LABEL = "None"
        const val SLOTS_PER_PAGE = 6
        const val ROW_PITCH = 26
        val PAGE_LABELS = arrayOf("Slots 1-6", "Slots 7-12")
    }
}
