package com.huashanlunjian.glbrender.client.npc.editor

/**
 * Advanced tab — a non-functional replica of CustomNPC `GuiNpcAdvanced`: the role/job bi-directional steppers (with
 * their edit buttons) and the two-column sub-screen launcher buttons (lines, factions, dialogs, sounds, day/night,
 * linked chunks, scenes, marks). None of these systems exist in our mod yet; the tab shows the surface area.
 */
class GlbAdvancedTab(ctx: GlbEditorContext) : GlbEditorTab(ctx) {
    override val title = "Advanced"

    override fun build() {
        var y = 8
        label(5, y + 5, "Role")
        stubStepper(85, y, 155, "None")
        stubButton(245, y, 52, "Edit")
        y += 22
        label(5, y + 5, "Job")
        stubStepper(85, y, 155, "None")
        stubButton(245, y, 52, "Edit")
        y += 22
        stubButton(15, y, 190, "Lines")
        stubButton(208, y, 190, "Factions")
        y += 22
        stubButton(15, y, 190, "Dialogs")
        stubButton(208, y, 190, "Sounds")
        y += 22
        stubButton(15, y, 190, "Day/Night")
        stubButton(208, y, 190, "Linked chunks")
        y += 22
        stubButton(15, y, 190, "Scenes")
        stubButton(208, y, 190, "Marks")
    }
}
