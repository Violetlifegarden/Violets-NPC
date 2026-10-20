package com.huashanlunjian.glbrender.client.npc.editor

/**
 * Advanced tab — the CustomNPC `GuiNpcAdvanced` surface: the role/job steppers (still placeholders; those systems need
 * controllers we do not have) and the two-column sub-screen launchers. Lines, Sounds, Marks, Factions, Dialogs and
 * Linked NPC are real: they open sub-screens over [GlbEditorContext.advanced] and commit SetAdvancedPacket. Day/Night
 * and Scenes stay placeholders.
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
        button(208, y, 190, "Factions") { ctx.openAdvancedSub(GlbEditorContext.ADVANCED_FACTION) }
        y += 22
        button(15, y, 190, "Dialogs") { ctx.openAdvancedSub(GlbEditorContext.ADVANCED_DIALOGS) }
        button(208, y, 190, "Sounds") { ctx.openAdvancedSub(GlbEditorContext.ADVANCED_SOUNDS) }
        y += 22
        stubButton(15, y, 190, "Day/Night")
        button(208, y, 190, "Linked NPC") { ctx.openAdvancedSub(GlbEditorContext.ADVANCED_LINKED) }
        y += 22
        stubButton(15, y, 190, "Scenes")
        button(208, y, 190, "Marks") { ctx.openAdvancedSub(GlbEditorContext.ADVANCED_MARKS) }
    }
}
