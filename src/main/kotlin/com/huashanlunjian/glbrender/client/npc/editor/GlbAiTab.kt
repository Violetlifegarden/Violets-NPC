package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.npc.GlbNpcAi

/**
 * AI tab — CustomNPC `GuiNpcAI`'s two-column layout (labels + right-aligned controls). Every switch here is wired:
 * movement/response/profession to the per-instance config, and the door/shelter/water/return/leap/mount block to
 * [GlbNpcAi]. Movement opens its own sub-screen for the numeric range/speed fields, mirroring CNPC's
 * `SubGuiNpcMovement`. The Animation row previews the current model's clips (CNPC's `movement.animation` analogue).
 */
class GlbAiTab(ctx: GlbEditorContext) : GlbEditorTab(ctx) {
    override val title = "AI"

    override fun build() {
        // Left column.
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
        label(5, y + 5, "Profession")
        // Option order matches GlbNpcConfig.PROFESSION_* indices (Guard hunts hostile mobs).
        cycleOption(86, y, 60, PROFESSION_OPTIONS, ctx.profession) { ctx.profession = it }
        y += 22
        label(5, y + 5, "Response")
        // Option order matches GlbNpcConfig.RESPONSE_* indices.
        cycleOption(86, y, 60, RESPONSE_OPTIONS, ctx.onAttack) { ctx.onAttack = it }
        y += 22
        label(5, y + 5, "Doors")
        // Option order matches GlbNpcAi.DOOR_* indices.
        aiCycle(86, y, 60, GlbNpcAi.DOOR_OPTIONS, ctx.ai.doorMode) { ctx.ai.doorMode = it }
        y += 22
        label(5, y + 5, "Shelter")
        // Option order matches GlbNpcAi.SHELTER_* indices.
        aiCycle(86, y, 60, GlbNpcAi.SHELTER_OPTIONS, ctx.ai.shelter) { ctx.ai.shelter = it }
        y += 22
        label(5, y + 5, "Dialog")
        // Per-NPC dialog lines, |-separated; the plain-right-click talk screen walks them in order.
        textField(60, y, 350, ctx.dialogs) { ctx.dialogs = it }

        // Right column.
        var r = 6
        label(150, r + 5, "Avoid water")
        aiToggle(230, r, 60, ctx.ai.avoidsWater) { ctx.ai.avoidsWater = it }
        r += 22
        label(150, r + 5, "Return")
        aiToggle(230, r, 60, ctx.ai.returnToStart) { ctx.ai.returnToStart = it }
        r += 22
        label(150, r + 5, "Leap")
        aiToggle(230, r, 60, ctx.ai.leapAtTarget) { ctx.ai.leapAtTarget = it }
        r += 22
        label(150, r + 5, "Mount control")
        aiToggle(230, r, 60, ctx.ai.mountControl) { ctx.ai.mountControl = it }
        r += 22
        label(150, r + 5, "Movement")
        // CNPC edits walking range/speed and the moving type on a dedicated sub-screen.
        button(230, r, 60, "Edit") { ctx.openMovementSub() }
        r += 22
        // Animation preview over the model's own clip names, so every clip a rig ships can be reviewed in-game.
        label(150, r + 5, "Animation")
        clipCycle(215, r, 190)
    }

    companion object {
        /** Option labels in the same index order as `GlbNpcConfig.PROFESSION_*`. */
        val PROFESSION_OPTIONS = arrayOf("None", "Guard")

        /** Option labels in the same index order as `GlbNpcConfig.RESPONSE_*` (retaliate is a not-yet-wired placeholder). */
        val RESPONSE_OPTIONS = arrayOf("Retaliate", "Panic", "Retreat", "Nothing")
    }
}
