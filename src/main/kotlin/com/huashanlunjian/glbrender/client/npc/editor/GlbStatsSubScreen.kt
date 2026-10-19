package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.npc.GlbNpcStats
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * Detached editor for the four Stats groups whose field sets do not fit the 420px tab body: Respawn, Melee, Ranged and
 * Resistance (CustomNPC likewise edits these on their own sub-screens). It edits the host editor's live [GlbNpcStats]
 * copy and calls [onCommit] after every change (the host sends one SetStatsPacket); [returnTo] rebuilds the editor
 * over the still-open menu, the same pattern as the model preview.
 */
class GlbStatsSubScreen(
    private val section: Int,
    private val stats: GlbNpcStats,
    private val onCommit: () -> Unit,
    private val returnTo: () -> Screen?,
) : Screen(Component.literal(sectionTitle(section))) {

    private var left = 0
    private var top = 0
    private val labels = ArrayList<Label>()

    private data class Label(val x: Int, val y: Int, val text: String, val color: Int = COLOR_LABEL)

    override fun init() {
        super.init()
        left = (width - PANEL_W) / 2
        top = (height - PANEL_H) / 2
        labels.clear()
        when (section) {
            SECTION_RESPAWN -> buildRespawn()
            SECTION_MELEE -> buildMelee()
            SECTION_RANGED -> buildRanged()
            SECTION_RESISTANCE -> buildResistance()
        }
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(left + PANEL_W - 66, top + PANEL_H - 26, 56, 20)
                .build(),
        )
    }

    // --- sections ---

    private fun buildRespawn() {
        var y = 34
        label(20, y + 5, "Spawn cycle")
        cycle(150, y, 140, GlbNpcStats.SPAWN_CYCLE_OPTIONS, stats.spawnCycle) { stats.spawnCycle = it }
        y += 24
        label(20, y + 5, "Respawn (s)")
        stepper(150, y, 100, stats.respawnTime, 5, 0, GlbNpcStats.MAX_RESPAWN_TIME) { stats.respawnTime = it }
        y += 24
        label(20, y + 5, "Hide body")
        toggle(150, y, 60, stats.hideKilledBody) { stats.hideKilledBody = it }
        label(20, y + 34, "Yes/Day/Night respawn; No/Naturally die permanently.", COLOR_DIM)
    }

    private fun buildMelee() {
        var y = 26
        label(20, y + 5, "Strength")
        stepper(130, y, 90, stats.meleeStrength, 1, 0, GlbNpcStats.MAX_MELEE) { stats.meleeStrength = it }
        label(235, y + 5, "Speed (t)")
        stepper(345, y, 60, stats.meleeSpeed, 1, 1, GlbNpcStats.MAX_MELEE_SPEED) { stats.meleeSpeed = it }
        y += 24

        label(20, y + 5, "Range")
        stepper(130, y, 90, stats.meleeRange, 1, 1, GlbNpcStats.MAX_MELEE_RANGE) { stats.meleeRange = it }
        label(235, y + 5, "Knockback")
        stepper(345, y, 60, stats.meleeKnockback, 1, 0, GlbNpcStats.MAX_MELEE_KNOCKBACK) {
            stats.meleeKnockback = it
        }
        y += 24

        label(20, y + 5, "Effect")
        cycle(130, y, 90, GlbNpcStats.MELEE_EFFECT_NAMES, stats.meleeEffect) { stats.meleeEffect = it }
        label(235, y + 5, "Time (s)")
        stepper(345, y, 60, stats.meleeEffectTime, 1, 1, GlbNpcStats.MAX_EFFECT_TIME) { stats.meleeEffectTime = it }
        y += 24

        label(20, y + 5, "Amplifier")
        stepper(130, y, 90, stats.meleeEffectAmplifier, 1, 0, GlbNpcStats.MAX_EFFECT_AMPLIFIER) {
            stats.meleeEffectAmplifier = it
        }
        label(235, y + 5, "20 ticks = 1 second", COLOR_DIM)
    }

    private fun buildRanged() {
        var y = 26
        label(20, y + 5, "Accuracy")
        stepper(125, y, 85, stats.rangedAccuracy, 1, 0, 100) { stats.rangedAccuracy = it }
        label(225, y + 5, "Shots")
        stepper(330, y, 70, stats.rangedShotCount, 1, 1, GlbNpcStats.MAX_RANGED_SHOTS) { stats.rangedShotCount = it }
        y += 24

        label(20, y + 5, "Range")
        stepper(125, y, 85, stats.rangedRange, 1, 1, GlbNpcStats.MAX_RANGED_RANGE) { stats.rangedRange = it }
        label(225, y + 5, "Melee dist")
        stepper(330, y, 70, stats.rangedMeleeDistance, 1, 0, GlbNpcStats.MAX_RANGED_RANGE) {
            stats.rangedMeleeDistance = it
        }
        y += 24

        label(20, y + 5, "Min delay")
        stepper(125, y, 85, stats.rangedMinDelay, 1, 1, GlbNpcStats.MAX_RANGED_DELAY) { stats.rangedMinDelay = it }
        label(225, y + 5, "Max delay")
        stepper(330, y, 70, stats.rangedMaxDelay, 1, 1, GlbNpcStats.MAX_RANGED_DELAY) { stats.rangedMaxDelay = it }
        y += 24

        label(20, y + 5, "Burst")
        stepper(125, y, 85, stats.rangedBurstCount, 1, 1, GlbNpcStats.MAX_RANGED_SHOTS) { stats.rangedBurstCount = it }
        label(225, y + 5, "Burst delay")
        stepper(330, y, 70, stats.rangedBurstDelay, 1, 1, GlbNpcStats.MAX_RANGED_DELAY) { stats.rangedBurstDelay = it }
        y += 24

        label(20, y + 5, "Damage")
        stepper(125, y, 85, stats.rangedDamage, 1, 0, GlbNpcStats.MAX_MELEE) { stats.rangedDamage = it }
        label(225, y + 5, "Speed")
        stepper(330, y, 70, stats.rangedSpeed, 1, 1, GlbNpcStats.MAX_RANGED_SPEED) { stats.rangedSpeed = it }
        y += 24

        label(20, y + 5, "Indirect")
        cycle(125, y, 85, GlbNpcStats.RANGED_INDIRECT_OPTIONS, stats.rangedIndirect) { stats.rangedIndirect = it }
        label(225, y + 5, "Aim")
        toggle(330, y, 60, stats.rangedAimWhileShooting) { stats.rangedAimWhileShooting = it }
    }

    private fun buildResistance() {
        var y = 34
        label(20, y + 5, "Knockback")
        floatStepper(150, y, 100, stats.resistKnockback) { stats.resistKnockback = it }
        y += 24
        label(20, y + 5, "Arrow")
        floatStepper(150, y, 100, stats.resistArrow) { stats.resistArrow = it }
        y += 24
        label(20, y + 5, "Melee")
        floatStepper(150, y, 100, stats.resistMelee) { stats.resistMelee = it }
        y += 24
        label(20, y + 5, "Explosion")
        floatStepper(150, y, 100, stats.resistExplosion) { stats.resistExplosion = it }
        label(20, y + 38, "2.0 = immune, 1.0 = normal, 0.0 = double damage", COLOR_DIM)
    }

    // --- controls (each commits the shared stats block) ---

    private fun label(x: Int, y: Int, text: String, color: Int = COLOR_LABEL) {
        labels.add(Label(x, y, text, color))
    }

    private fun stepper(x: Int, y: Int, w: Int, initial: Int, step: Int, min: Int, max: Int, assign: (Int) -> Unit) {
        addRenderableWidget(
            GlbStepper(left + x, top + y, w, initial.toFloat(), step.toFloat(), min.toFloat(), max.toFloat(), { fmt(it, 0) }) { value ->
                assign(value.toInt())
                onCommit()
            },
        )
    }

    private fun floatStepper(x: Int, y: Int, w: Int, initial: Float, assign: (Float) -> Unit) {
        addRenderableWidget(
            GlbStepper(left + x, top + y, w, initial, RESIST_STEP, 0f, 2f, { fmt(it, 2) }) { value ->
                assign(value)
                onCommit()
            },
        )
    }

    private fun toggle(x: Int, y: Int, w: Int, initial: Boolean, assign: (Boolean) -> Unit) {
        addRenderableWidget(
            GlbToggleYesNo(left + x, top + y, w, initial) { value ->
                assign(value)
                onCommit()
            },
        )
    }

    private fun cycle(x: Int, y: Int, w: Int, options: Array<String>, initial: Int, assign: (Int) -> Unit) {
        addRenderableWidget(
            GlbCycleButton(left + x, top + y, w, options, initial.coerceIn(0, options.lastIndex)) { index ->
                assign(index)
                onCommit()
            },
        )
    }

    private fun fmt(value: Float, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

    // --- screen plumbing ---

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(g, mouseX, mouseY, partialTick)
        val right = left + PANEL_W
        val bottom = top + PANEL_H
        g.fill(left, top, right, bottom, PANEL_BG)
        g.fill(left, top, right, top + 1, PANEL_BORDER)
        g.fill(left, bottom - 1, right, bottom, PANEL_BORDER)
        g.fill(left, top, left + 1, bottom, PANEL_BORDER)
        g.fill(right - 1, top, right, bottom, PANEL_BORDER)
        g.drawCenteredString(font, title, left + PANEL_W / 2, top + 8, 0xFFFFFF)
        labels.forEach { g.drawString(font, it.text, left + it.x, top + it.y, it.color, false) }
        renderables.forEach { it.render(g, mouseX, mouseY, partialTick) }
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        minecraft?.setScreen(returnTo())
    }

    companion object {
        const val SECTION_RESPAWN = 0
        const val SECTION_MELEE = 1
        const val SECTION_RANGED = 2
        const val SECTION_RESISTANCE = 3

        private const val PANEL_W = 420
        private const val PANEL_H = 200
        private const val RESIST_STEP = 0.1f
        private val PANEL_BG = 0xF01E1E1E.toInt()
        private val PANEL_BORDER = 0xFF5A5A5A.toInt()
        private const val COLOR_LABEL = 0xE0E0E0
        private const val COLOR_DIM = 0x909090
        private val TITLES = arrayOf("Respawn", "Melee", "Ranged", "Resistance")

        private fun sectionTitle(section: Int): String = TITLES[section.coerceIn(0, TITLES.lastIndex)]
    }
}
