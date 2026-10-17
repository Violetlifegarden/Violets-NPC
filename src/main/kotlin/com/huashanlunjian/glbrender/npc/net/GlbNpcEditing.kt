package com.huashanlunjian.glbrender.npc.net

import com.huashanlunjian.glbrender.npc.GlbNpc
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import java.util.UUID

/**
 * Server-side "which NPC is this player editing" session, in CustomNPC's `editingNpc` shape. The C2S edit packets
 * carry an entity id, but authorization never trusts it blindly: a packet is only applied if the sender actually has
 * an editing session for that exact entity, opened by legitimately right-clicking it (Step 5-4), and the entity is
 * still within reach. This keeps the packets context-free and makes id-spoofing useless — a player can only edit an
 * NPC they legitimately opened the editor on.
 *
 * Accessed only from the server thread (menu open/close and [net.neoforged.neoforge.network.handling.IPayloadContext]
 * `enqueueWork`), so a plain map needs no synchronization.
 */
object GlbNpcEditing {
    private const val REACH = 6.0

    private val sessions = HashMap<UUID, Int>()

    /** Begins an editing session; called server-side when the editor menu opens for [npc]. */
    fun open(player: ServerPlayer, npc: GlbNpc) {
        sessions[player.uuid] = npc.id
    }

    /** Ends the session; called when the editor menu closes. Safe to call with no active session. */
    fun close(player: Player) {
        sessions.remove(player.uuid)
    }

    /** True if [player] has an open session for exactly [npc], it is alive, and it is still within edit reach. */
    fun canEdit(player: ServerPlayer, npc: GlbNpc): Boolean {
        val editingId = sessions[player.uuid] ?: return false
        if (editingId != npc.id) return false
        if (npc.isRemoved) return false
        return player.canInteractWithEntity(npc, REACH)
    }
}
