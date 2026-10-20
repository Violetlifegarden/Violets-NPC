package com.huashanlunjian.glbrender.client.npc.editor

/**
 * Global tab — the CustomNPC `GuiNPCGlobalMainMenu` launcher list. Factions, Dialogs, Transport Locations and Linked
 * NPCs open our world-data managers (shared by every NPC, operator-gated). Bank Accounts, Quests, Player Data, Recipes
 * and Natural Spawn stay placeholders: they are full world suites (or, in CNPC, marked broken/WIP) that nothing in our
 * NPC behavior consumes yet.
 */
class GlbGlobalTab(ctx: GlbEditorContext) : GlbEditorTab(ctx) {
    override val title = "Global"

    override fun build() {
        val items = listOf(
            "Bank Accounts" to null,
            "Factions" to GlbEditorContext.GLOBAL_FACTIONS,
            "Dialogs" to GlbEditorContext.GLOBAL_DIALOGS,
            "Quests" to null,
            "Transport Locations" to GlbEditorContext.GLOBAL_TRANSPORT,
            "Player Data" to null,
            "Recipes" to null,
            "Natural Spawn" to null,
            "Linked NPCs" to GlbEditorContext.GLOBAL_LINKED,
        )
        var y = 8
        for ((name, section) in items) {
            if (section == null) {
                stubButton(85, y, 250, name)
            } else {
                button(85, y, 250, name) { ctx.openGlobalSub(section) }
            }
            y += 21
        }
    }
}
