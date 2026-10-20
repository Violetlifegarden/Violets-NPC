package com.huashanlunjian.glbrender.npc.net

import com.huashanlunjian.glbrender.npc.GlbNpcClientHooks
import com.huashanlunjian.glbrender.npc.world.GlbNpcWorldData
import com.huashanlunjian.glbrender.npc.world.GlbNpcWorldEvents
import com.mojang.logging.LogUtils
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * World-level data sync. Unlike the per-NPC edit packets (which ride the entity's own `SynchedEntityData`), the
 * faction/dialog/linked-name/transport tables belong to no entity, so they travel explicitly:
 *
 *  - [SyncWorldDataPacket] is the full S2C snapshot, sent at login and after every edit, and is also what feeds the
 *    client-side cache the editors and the dialog chooser read;
 *  - [SetWorldSectionPacket] replaces one section wholesale. The whole-section shape (rather than add/remove verbs)
 *    keeps ids allocated consistently and makes the server's clamp the single source of truth. Global edits are
 *    op-gated (permission level 2), matching CNPC's world managers.
 */
data class SyncWorldDataPacket(val tag: CompoundTag) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SyncWorldDataPacket> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("glbrender", "sync_world_data"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SyncWorldDataPacket> = StreamCodec.of(
            { buf, packet -> buf.writeNbt(packet.tag) },
            { buf -> SyncWorldDataPacket(buf.readNbt() ?: CompoundTag()) },
        )

        fun handleClient(packet: SyncWorldDataPacket, context: IPayloadContext) {
            context.enqueueWork { GlbNpcClientHooks.applyWorldData?.invoke(packet.tag) }
        }
    }
}

/** Replaces world section [section] (`GlbNpcWorldData.SECTION_*`) with [tag]'s `Entries` list. */
data class SetWorldSectionPacket(val section: Int, val tag: CompoundTag) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SetWorldSectionPacket> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("glbrender", "set_world_section"))

        /** Game-master level: the same gate vanilla uses for command blocks. */
        const val EDIT_PERMISSION_LEVEL = 2

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetWorldSectionPacket> = StreamCodec.of(
            { buf, packet ->
                buf.writeVarInt(packet.section)
                buf.writeNbt(packet.tag)
            },
            { buf -> SetWorldSectionPacket(buf.readVarInt(), buf.readNbt() ?: CompoundTag()) },
        )

        fun handle(packet: SetWorldSectionPacket, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                if (!player.hasPermissions(EDIT_PERMISSION_LEVEL)) {
                    LOGGER.warn("glbrender: rejected world section {} from {} (not op)", packet.section, player.name)
                    return@enqueueWork
                }
                if (packet.section !in 0 until GlbNpcWorldData.SECTION_COUNT) return@enqueueWork
                val data = GlbNpcWorldData.get(player.server)
                data.replaceSection(packet.section, packet.tag)
                // Broadcast the clamped result so every client (including the editor's) converges on the same ids.
                GlbNpcWorldEvents.broadcast(data)
            }
        }
    }
}

private val LOGGER = LogUtils.getLogger()
