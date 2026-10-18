package com.huashanlunjian.glbrender.client.npc.editor

/**
 * Global tab — a non-functional replica of CustomNPC `GuiNPCGlobalMainMenu`: the single-column list of global-editor
 * launcher buttons (bank accounts, factions, dialogs, quests, transport, player data, recipes, natural spawn, linked
 * chunks). These open CNPC's world-wide management sub-screens, none of which apply to our mod; placeholders only.
 */
class GlbGlobalTab(ctx: GlbEditorContext) : GlbEditorTab(ctx) {
    override val title = "Global"

    override fun build() {
        val items = listOf(
            "Bank Accounts", "Factions", "Dialogs", "Quests", "Transport Locations",
            "Player Data", "Recipes", "Natural Spawn", "Linked Chunks",
        )
        var y = 8
        items.forEach {
            stubButton(85, y, 250, it)
            y += 21
        }
    }
}
