package com.huashanlunjian.glbrender.client.npc.world

import com.huashanlunjian.glbrender.npc.world.GlbDialogDef
import com.huashanlunjian.glbrender.npc.world.GlbFaction
import com.huashanlunjian.glbrender.npc.world.GlbNpcWorldData
import com.huashanlunjian.glbrender.npc.world.GlbTransportLocation
import net.minecraft.nbt.CompoundTag

/**
 * Client-side mirror of [GlbNpcWorldData], refreshed by [com.huashanlunjian.glbrender.npc.net.SyncWorldDataPacket]
 * (login and after every edit). Editors and the talk chooser read it instead of asking the server, so opening a menu
 * or a dialog never waits on a round trip; an empty cache before the first sync simply shows no factions/dialogs.
 */
object GlbNpcWorldCache {
    private var data = GlbNpcWorldData()

    fun apply(tag: CompoundTag) {
        data = GlbNpcWorldData.fromTag(tag)
    }

    fun current(): GlbNpcWorldData = data

    fun factions(): List<GlbFaction> = data.factions()
    fun dialogs(): List<GlbDialogDef> = data.dialogs()
    fun linkedNames(): List<String> = data.linkedNames()
    fun transport(): List<GlbTransportLocation> = data.transport()

    fun faction(id: Int): GlbFaction? = data.faction(id)
    fun dialog(id: Int): GlbDialogDef? = data.dialog(id)
}
