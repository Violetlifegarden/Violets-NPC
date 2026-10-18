package com.huashanlunjian.glbrender.client.npc.editor

/**
 * Display tab — CustomNPC `GuiNpcDisplay` shape (9-row column, +23px pitch). We do not render skins/capes/overlays, so
 * those rows are replicated as empty placeholder controls; only the rows backed by real features are wired:
 * [modelCycle] (our GLB model id) and the scale/yaw/ground [stepper]s (our per-instance transform overrides). The
 * CNPC "size" field becomes our scale stepper, and yaw/ground — which CNPC has no equivalent for — take two of the
 * rows we would otherwise leave as texture/cape placeholders.
 */
class GlbDisplayTab(ctx: GlbEditorContext) : GlbEditorTab(ctx) {
    override val title = "Display"

    override fun build() {
        var y = 4
        label(5, y + 5, "Name")
        textField(50, y, 206, ctx.displayName) { ctx.displayName = it }
        stubCycle(305, y, 110, "Show", "Hide", "Show attacking")
        y += 23

        label(5, y + 5, "Title")
        stubField(50, y, 186)
        y += 23

        // Real: model picker + scale.
        label(5, y + 5, "Model")
        modelCycle(50, y, 150)
        label(210, y + 5, "Size")
        stepper(240, y, 90, ctx.scale, SCALE_STEP, SCALE_MIN, SCALE_MAX, 2) { ctx.scale = it }
        y += 23

        // Real: yaw / ground-offset overrides (no CNPC counterpart).
        label(5, y + 5, "Yaw")
        stepper(50, y, 90, ctx.yawOffset, YAW_STEP, YAW_MIN, YAW_MAX, 1) { ctx.yawOffset = it }
        y += 23

        label(5, y + 5, "Ground")
        stepper(50, y, 90, ctx.groundOffsetY, GROUND_STEP, GROUND_MIN, GROUND_MAX, 2) { ctx.groundOffsetY = it }
        y += 23

        label(5, y + 5, "Texture")
        stubField(80, y, 200)
        stubButton(283, y, 38, "...")
        y += 23

        label(5, y + 5, "Cape")
        stubField(80, y, 200)
        stubButton(283, y, 80, "Select")
        y += 23

        label(5, y + 5, "Overlay")
        stubField(80, y, 150)
        label(245, y + 5, "Glowing")
        stubYesNo(300, y, 40, false)
    }
}
