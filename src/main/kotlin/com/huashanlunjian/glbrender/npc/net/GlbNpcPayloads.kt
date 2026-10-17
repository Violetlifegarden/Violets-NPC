package com.huashanlunjian.glbrender.npc.net

import com.huashanlunjian.glbrender.npc.GlbModels
import com.huashanlunjian.glbrender.npc.GlbNpc
import com.mojang.logging.LogUtils
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.function.Consumer

/**
 * Client->server edit packets for the NPC editor. They deliberately carry only an entity id plus the new values; the
 * server re-resolves the entity, checks the [GlbNpcEditing] session authorization, revalidates the values, and applies
 * them to [GlbNpc.config]. Because config fields are backed by `SynchedEntityData`, applying them server-side
 * auto-replicates to every tracking client (including the editor's own), so there is no S2C packet — the client just
 * watches the synced values bounce back. This is TouhouLittleMaid's model (send C2S, let SynchedEntityData echo) with
 * CustomNPC's session-based authorization.
 *
 * Common (both dists): the payloads and codecs are plain `net.minecraft` types, and handlers only act on the server
 * flow (`playToServer`), so a dedicated server can register and receive them too.
 */

/** Sets which registered GLB model an NPC renders as. Empty [modelId] means "use the registry default". */
data class SetModelPacket(val entityId: Int, val modelId: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SetModelPacket> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("glbrender", "set_model"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetModelPacket> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, { it.entityId },
            ByteBufCodecs.STRING_UTF8, { it.modelId },
            { id, model -> SetModelPacket(id, model) },
        )

        fun handle(packet: SetModelPacket, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val npc = player.level().getEntity(packet.entityId) as? GlbNpc ?: return@enqueueWork
                if (!GlbNpcEditing.canEdit(player, npc)) {
                    LOGGER.warn("glbrender: rejected set_model for {} from {} (no editing session)", packet.entityId, player.name)
                    return@enqueueWork
                }
                // Never accept an unknown model id: it would leave the client renderer with no scene to draw.
                if (packet.modelId.isNotEmpty() && GlbModels.descriptor(packet.modelId) == null) {
                    LOGGER.warn("glbrender: rejected set_model unknown id {}", packet.modelId)
                    return@enqueueWork
                }
                npc.config.modelId = packet.modelId
            }
        }
    }
}

/**
 * Sets an NPC's per-instance transform overrides and behavior flags in one shot (the editor's Transform/Behavior tabs
 * commit together). Transform values use the same [Float.NaN] = "inherit the model descriptor" convention as
 * [com.huashanlunjian.glbrender.npc.GlbNpcConfig]; NaN is a legal float on the wire, so it round-trips unchanged.
 */
data class SetConfigPacket(
    val entityId: Int,
    val scale: Float,
    val yawOffset: Float,
    val groundOffsetY: Float,
    val wander: Boolean,
    val followOwner: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SetConfigPacket> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("glbrender", "set_config"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetConfigPacket> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, { it.entityId },
            ByteBufCodecs.FLOAT, { it.scale },
            ByteBufCodecs.FLOAT, { it.yawOffset },
            ByteBufCodecs.FLOAT, { it.groundOffsetY },
            ByteBufCodecs.BOOL, { it.wander },
            ByteBufCodecs.BOOL, { it.followOwner },
            { id, scale, yaw, ground, wander, follow -> SetConfigPacket(id, scale, yaw, ground, wander, follow) },
        )

        fun handle(packet: SetConfigPacket, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val npc = player.level().getEntity(packet.entityId) as? GlbNpc ?: return@enqueueWork
                if (!GlbNpcEditing.canEdit(player, npc)) {
                    LOGGER.warn("glbrender: rejected set_config for {} from {} (no editing session)", packet.entityId, player.name)
                    return@enqueueWork
                }
                val config = npc.config
                // Clamp scale to the same sane range the descriptor loader uses, so a bad client can't blow up the model.
                config.scale = if (packet.scale.isNaN()) Float.NaN else packet.scale.coerceIn(SCALE_MIN, SCALE_MAX)
                config.yawOffset = packet.yawOffset
                config.groundOffsetY = packet.groundOffsetY
                config.wander = packet.wander
                config.followOwner = packet.followOwner
            }
        }

        private const val SCALE_MIN = 0.01f
        private const val SCALE_MAX = 16.0f
    }
}

/** Registers the payloads on the mod bus. Called from [com.huashanlunjian.glbrender.GlbRenderContent.register] (both dists). */
object GlbRenderNetwork {
    fun register(bus: IEventBus) {
        bus.addListener(
            EventPriority.NORMAL,
            RegisterPayloadHandlersEvent::class.java,
            Consumer { event ->
                val registrar = event.registrar("1")
                registrar.playToServer(SetModelPacket.TYPE, SetModelPacket.STREAM_CODEC) { packet, context ->
                    SetModelPacket.handle(packet, context)
                }
                registrar.playToServer(SetConfigPacket.TYPE, SetConfigPacket.STREAM_CODEC) { packet, context ->
                    SetConfigPacket.handle(packet, context)
                }
            },
        )
    }
}

private val LOGGER = LogUtils.getLogger()
