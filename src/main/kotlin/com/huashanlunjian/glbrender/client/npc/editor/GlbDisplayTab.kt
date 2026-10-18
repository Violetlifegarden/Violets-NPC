package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.npc.GlbNpcConfig
import java.util.Locale

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

        label(5, y + 5, "Tint")
        // Real: per-instance vertex tint, entered as 6 (RRGGBB) or 8 (AARRGGBB) hex digits; commits on Enter.
        textField(50, y, 100, tintToHex(ctx.tint)) { ctx.tint = hexToTint(it) }
        label(158, y + 5, "hex, empty = none")
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
        // Real: full-bright glow toggle (our custom drawScene renderer bypasses vanilla outline, so glow = max light).
        toggle(300, y, 40, ctx.glowing) { ctx.glowing = it }
    }

    private companion object {
        /** Display a tint as 6 hex digits (RGB); the opaque-white default shows as blank = "no tint". */
        fun tintToHex(tint: Int): String =
            if (tint == GlbNpcConfig.NO_TINT) "" else String.format(Locale.ROOT, "%06X", tint and 0xFFFFFF)

        /** Parse a 6 (RRGGBB) or 8 (AARRGGBB) hex color, tolerating a leading '#'; blank/invalid clears to no tint. */
        fun hexToTint(text: String): Int {
            val hex = text.trim().removePrefix("#")
            if (hex.isEmpty()) return GlbNpcConfig.NO_TINT
            val value = hex.toLongOrNull(16) ?: return GlbNpcConfig.NO_TINT
            return when (hex.length) {
                6 -> (0xFF000000L or value).toInt()
                8 -> value.toInt()
                else -> GlbNpcConfig.NO_TINT
            }
        }
    }
}
