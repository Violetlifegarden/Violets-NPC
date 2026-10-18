package com.huashanlunjian.glbrender.client.npc.editor

/**
 * AI tab — CustomNPC `GuiNpcAI`'s two-column layout (labels + right-aligned [cycle]/[Y/N] controls). Only the two
 * behaviors we actually implemented are functional — [toggle]s for wander and follow-owner (they write the config and
 * fire [GlbNpcConfig]-backed goals). The remaining AI switches (response, doors, swim, shelter, movement, …) are
 * placeholders for the Step-5 goal set we have not built yet.
 */
class GlbAiTab(ctx: GlbEditorContext) : GlbEditorTab(ctx) {
    override val title = "AI"

    override fun build() {
        // Left column: real toggles first, then placeholder cycles/toggles.
        var y = 6
        label(5, y + 5, "Wander")
        toggle(86, y, 60, ctx.wander) { ctx.wander = it }
        y += 22
        label(5, y + 5, "Follow owner")
        toggle(86, y, 60, ctx.followOwner) { ctx.followOwner = it }
        y += 22
        label(5, y + 5, "Sit")
        toggle(86, y, 60, ctx.sit) { ctx.sit = it }
        y += 22
        label(5, y + 5, "Look player")
        toggle(86, y, 60, ctx.lookPlayer) { ctx.lookPlayer = it }
        y += 22
        label(5, y + 5, "Response")
        stubCycle(86, y, 60, "Retaliate", "Panic", "Retreat", "Nothing")
        y += 22
        label(5, y + 5, "Doors")
        stubCycle(86, y, 60, "Break", "Open", "Disabled")
        y += 22
        label(5, y + 5, "Shelter")
        stubCycle(86, y, 60, "Darkness", "Sunlight", "Disabled")

        // Right column: all placeholders.
        var r = 6
        label(150, r + 5, "Avoid water")
        stubYesNo(230, r, 60, false)
        r += 22
        label(150, r + 5, "Return")
        stubYesNo(230, r, 60)
        r += 22
        label(150, r + 5, "Leap")
        stubYesNo(230, r, 60, false)
        r += 22
        label(150, r + 5, "Mount control")
        stubYesNo(230, r, 60, false)
        r += 22
        label(150, r + 5, "Movement")
        stubButton(230, r, 60, "Edit")
    }
}
