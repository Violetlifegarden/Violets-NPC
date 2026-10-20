package com.huashanlunjian.glbrender.client.npc.editor

import com.huashanlunjian.glbrender.npc.GlbNpcAdvanced
import net.minecraft.client.gui.screens.Screen

/**
 * CustomNPC `SubGuiNpcSound`: the NPC's voice ids. Idle drives the vanilla ambient-sound cadence, angry is played on a
 * successful attack, and hurt/death/step replace the vanilla sounds. An empty id keeps the vanilla behavior, so a
 * fresh NPC sounds exactly like it did before this tab existed. Only registered sound ids resolve (the entity looks
 * them up in the built-in registry); arbitrary custom sound files are not supported.
 */
class GlbSoundsSubScreen(
    advanced: GlbNpcAdvanced,
    onCommit: () -> Unit,
    returnTo: () -> Screen?,
) : GlbAdvancedSubScreen("Sounds", advanced, onCommit, returnTo) {

    override fun build() {
        var y = 30
        soundRow(y, "Idle", advanced.idleSound) { advanced.idleSound = it }
        y += 24
        soundRow(y, "Angry", advanced.angrySound) { advanced.angrySound = it }
        y += 24
        soundRow(y, "Hurt", advanced.hurtSound) { advanced.hurtSound = it }
        y += 24
        soundRow(y, "Death", advanced.deathSound) { advanced.deathSound = it }
        y += 24
        soundRow(y, "Step", advanced.stepSound) { advanced.stepSound = it }
        y += 30
        label(20, y + 5, "Disable pitch")
        toggle(150, y, 60, advanced.disablePitch) { advanced.disablePitch = it }
        label(220, y + 5, "Play every sound at pitch 1.0", COLOR_DIM)
        label(20, 190, "Blank = vanilla. Ids must exist in the sound registry.", COLOR_DIM)
    }

    private fun soundRow(y: Int, name: String, value: String, assign: (String) -> Unit) {
        label(20, y + 5, name)
        text(150, y, 240, value, GlbNpcAdvanced.MAX_SOUND, assign)
    }
}
