package com.huashanlunjian.glbrender.npc

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component

/**
 * The client-only actions the common entity/network code needs, exposed as injectable hooks so neither ever references
 * `net.minecraft.client`. The client bootstrap sets them; on a dedicated server they stay null and the matching
 * interactions simply do nothing, which is correct there.
 */
object GlbNpcClientHooks {
    /** Opens the dialog screen for [npcName] walking [lines]. Set only under the client dist. */
    var openDialog: ((npcName: Component, lines: List<String>) -> Unit)? = null

    /** Applies a world-data snapshot ([com.huashanlunjian.glbrender.npc.net.SyncWorldDataPacket]) to the client cache. */
    var applyWorldData: ((CompoundTag) -> Unit)? = null
}
