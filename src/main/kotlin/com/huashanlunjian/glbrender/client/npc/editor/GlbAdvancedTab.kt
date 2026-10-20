package com.huashanlunjian.glbrender.client.npc.editor

/**
 * Advanced tab — the CustomNPC `GuiNpcAdvanced` surface: the role/job steppers (still placeholders; those systems need
 * world-side controllers we do not have) and the two-column sub-screen launchers. Lines, Sounds and Marks are real:
 * they open sub-screens over [GlbEditorContext.advanced] and commit SetAdvancedPacket. Factions, Dialogs, Day/Night,
 * Linked NPC and Scenes stay placeholders.
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
        button(15, y, 190, "Lines") { ctx.openAdvancedSub(GlbEditorContext.ADVANCED_LINES) }
        stubButton(208, y, 190, "Factions")
        y += 22
        stubButton(15, y, 190, "Dialogs")
        button(208, y, 190, "Sounds") { ctx.openAdvancedSub(GlbEditorContext.ADVANCED_SOUNDS) }
        y += 22
        stubButton(15, y, 190, "Day/Night")
        stubButton(208, y, 190, "Linked NPC")
        y += 22
        stubButton(15, y, 190, "Scenes")
        button(208, y, 190, "Marks") { ctx.openAdvancedSub(GlbEditorContext.ADVANCED_MARKS) }
    }
}
