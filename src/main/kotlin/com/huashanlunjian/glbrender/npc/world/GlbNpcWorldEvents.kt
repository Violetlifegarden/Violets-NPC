package com.huashanlunjian.glbrender.npc.world

import com.huashanlunjian.glbrender.npc.net.SyncWorldDataPacket
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Sends the world-data snapshot: once to a player on login (so the client cache exists before any editor or dialog
 * chooser opens) and to everyone after an edit (the editor's own client applies the server's clamped result).
 */
object GlbNpcWorldEvents {
    fun syncTo(player: ServerPlayer) {
        val data = GlbNpcWorldData.get(player.server)
        PacketDistributor.sendToPlayer(player, SyncWorldDataPacket(data.snapshot()))
    }

    fun broadcast(data: GlbNpcWorldData) {
        PacketDistributor.sendToAllPlayers(SyncWorldDataPacket(data.snapshot()))
    }
}
