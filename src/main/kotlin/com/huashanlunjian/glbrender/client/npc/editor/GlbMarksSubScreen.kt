package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.npc.GlbNpcAdvanced
import com.huashanlunjian.glbrender.npc.GlbNpcMark
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import java.util.Locale

/**
 * CustomNPC `GuiNPCMarks`: nine mark slots, each a type (None / question / exclamation / pointer / skull / cross /
 * star) and a color. Non-None marks are drawn in a row above the NPC's name tag by `GlbNpcRenderer`. Colors use the
 * pack format's 6-digit hex form; the little swatch next to each field previews the parsed value.
 */
class GlbMarksSubScreen(
    advanced: GlbNpcAdvanced,
    onCommit: () -> Unit,
    returnTo: () -> Screen?,
) : GlbAdvancedSubScreen("Marks", advanced, onCommit, returnTo) {

    override fun build() {
        var y = 28
        label(20, y + 5, "Type")
        label(170, y + 5, "Color (hex)")
        y += 20
        for (slot in 0 until GlbNpcAdvanced.MAX_MARKS) {
            val mark = advanced.marks[slot]
            label(20, y + 5, "${slot + 1}", COLOR_DIM)
            addRenderableWidget(
                GlbCycleButton(left + 40, top + y, 120, GlbNpcMark.TYPE_OPTIONS, mark.type) { index ->
                    mark.type = index
                    commit()
                },
            )
            text(170, y, 80, hex(mark.color), 6) { value ->
                val parsed = value.trim().removePrefix("#").toIntOrNull(16)
                if (parsed != null) mark.color = parsed and 0xFFFFFF
            }
            y += 18
        }
        label(20, 214, "Marks appear above the name tag; None hides the slot.", COLOR_DIM)
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(g, mouseX, mouseY, partialTick)
        for (slot in 0 until GlbNpcAdvanced.MAX_MARKS) {
            val y = 48 + slot * 18
            g.fill(left + 262, top + y + 3, left + 278, top + y + 15, OPAQUE or (advanced.marks[slot].color and 0xFFFFFF))
        }
    }

    private fun hex(color: Int): String = String.format(Locale.ROOT, "%06X", color and 0xFFFFFF)

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
    }
}
