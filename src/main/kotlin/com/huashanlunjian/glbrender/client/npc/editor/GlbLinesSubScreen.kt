package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.npc.GlbNpcAdvanced
import net.minecraft.client.gui.screens.Screen

/**
 * CustomNPC `SubGuiNpcLines`: pick one of the six line categories, then edit its eight slots — line text plus an
 * optional sound id played with it. [GlbNpcAdvanced.orderedLines] is the category-wide Random/Ordered toggle. The
 * triggers for each category live on the entity; see `GlbNpc.sayLine`.
 */
class GlbLinesSubScreen(
    advanced: GlbNpcAdvanced,
    onCommit: () -> Unit,
    returnTo: () -> Screen?,
) : GlbAdvancedSubScreen("Lines", advanced, onCommit, returnTo) {

    private var category = 0

    override fun build() {
        var y = 28
        label(20, y + 5, "Category")
        addRenderableWidget(
            GlbCycleButton(left + 90, top + y, 130, GlbNpcAdvanced.LINE_CATEGORY_NAMES, category) { index ->
                category = index
                // The eight rows show the selected category, so rebuild them against it.
                rebuildWidgets()
            },
        )
        label(240, y + 5, "Order", COLOR_DIM)
        addRenderableWidget(
            GlbCycleButton(
                left + 290, top + y, 100,
                arrayOf("Random", "Ordered"),
                if (advanced.orderedLines) 1 else 0,
            ) { index ->
                advanced.orderedLines = index == 1
                commit()
            },
        )
        y += 23
        label(20, y, "Text", COLOR_DIM)
        label(250, y, "Sound id", COLOR_DIM)
        y += 10
        for (slot in 0 until GlbNpcAdvanced.LINES_PER_CATEGORY) {
            val line = advanced.line(category, slot)
            label(20, y + 5, "${slot + 1}", COLOR_DIM)
            text(40, y, 195, line.text, GlbNpcAdvanced.MAX_TEXT) { line.text = it }
            text(250, y, 150, line.sound, GlbNpcAdvanced.MAX_SOUND) { line.sound = it }
            y += 18
        }
        label(20, 214, "@target = the other party's name", COLOR_DIM)
    }
}
