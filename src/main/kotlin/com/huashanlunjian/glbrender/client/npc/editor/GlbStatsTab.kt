package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.npc.GlbNpcStats

/**
 * Stats tab — CustomNPC `GuiNpcStats`' number/cycle/edit-button grid, now fully wired to [GlbNpcStats]. The toggles,
 * aggro/creature/attack selectors and both regen rates live on the tab itself; Respawn, Melee, Ranged and Resistance
 * open detached sub-screens ([GlbStatsSubScreen]) because their field sets do not fit CNPC's 420px tab body. Health
 * stays on [GlbEditorContext.maxHealth] (the synced MAX_HEALTH override) rather than in the stats block.
 */
class GlbStatsTab(ctx: GlbEditorContext) : GlbEditorTab(ctx) {
    override val title = "Stats"

    override fun build() {
        var y = 6
        label(5, y + 5, "Health")
        stepper(86, y, 70, ctx.maxHealth, HEALTH_STEP, HEALTH_MIN, HEALTH_MAX, 0) { ctx.maxHealth = it }
        label(215, y + 5, "Aggro")
        statsStepper(300, y, 80, ctx.stats.aggroRange, 1, 0, AGGRO_MAX) { ctx.stats.aggroRange = it }
        y += 22

        label(5, y + 5, "Creature type")
        statsCycle(86, y, 115, GlbNpcStats.CREATURE_TYPE_OPTIONS, ctx.stats.creatureType) {
            ctx.stats.creatureType = it
        }
        label(215, y + 5, "Attack type")
        statsCycle(300, y, 80, GlbNpcStats.ATTACK_TYPE_OPTIONS, ctx.stats.attackType) { ctx.stats.attackType = it }
        y += 22

        label(5, y + 5, "Respawn")
        button(86, y, 70, "Edit") { ctx.openStatsSub(GlbStatsSubScreen.SECTION_RESPAWN) }
        label(215, y + 5, "Melee")
        button(300, y, 70, "Edit") { ctx.openStatsSub(GlbStatsSubScreen.SECTION_MELEE) }
        y += 22

        label(5, y + 5, "Ranged")
        button(86, y, 70, "Edit") { ctx.openStatsSub(GlbStatsSubScreen.SECTION_RANGED) }
        label(215, y + 5, "Resistance")
        button(300, y, 70, "Edit") { ctx.openStatsSub(GlbStatsSubScreen.SECTION_RESISTANCE) }
        y += 22

        label(5, y + 5, "Fire immune")
        statsToggle(86, y, 60, ctx.stats.immuneToFire) { ctx.stats.immuneToFire = it }
        label(215, y + 5, "Can drown")
        statsToggle(300, y, 60, ctx.stats.canDrown) { ctx.stats.canDrown = it }
        y += 22

        label(5, y + 5, "Burn in sun")
        statsToggle(86, y, 60, ctx.stats.burnInSun) { ctx.stats.burnInSun = it }
        label(215, y + 5, "No fall dmg")
        statsToggle(300, y, 60, ctx.stats.noFallDamage) { ctx.stats.noFallDamage = it }
        y += 22

        label(5, y + 5, "Regen")
        statsStepper(86, y, 60, ctx.stats.healthRegen, 1, 0, GlbNpcStats.MAX_REGEN) { ctx.stats.healthRegen = it }
        label(215, y + 5, "Combat regen")
        statsStepper(300, y, 80, ctx.stats.combatRegen, 1, 0, GlbNpcStats.MAX_REGEN) { ctx.stats.combatRegen = it }
        y += 22

        label(5, y + 5, "Potion immune")
        statsToggle(86, y, 60, ctx.stats.potionImmune) { ctx.stats.potionImmune = it }
        label(215, y + 5, "Cobweb")
        statsToggle(300, y, 60, ctx.stats.ignoreCobweb) { ctx.stats.ignoreCobweb = it }
    }

    private companion object {
        const val AGGRO_MAX = 128
    }
}
