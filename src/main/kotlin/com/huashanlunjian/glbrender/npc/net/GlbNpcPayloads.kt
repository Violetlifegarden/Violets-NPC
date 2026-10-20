package com.huashanlunjian.glbrender.npc.net

import com.huashanlunjian.glbrender.npc.GlbModels
import com.huashanlunjian.glbrender.npc.GlbNpc
import com.huashanlunjian.glbrender.npc.GlbNpcAdvanced
import com.huashanlunjian.glbrender.npc.GlbNpcAi
import com.huashanlunjian.glbrender.npc.GlbNpcConfig
import com.huashanlunjian.glbrender.npc.GlbNpcStats
import com.mojang.logging.LogUtils
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
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
    val sit: Boolean,
    val lookPlayer: Boolean,
    val profession: Int,
    val onAttack: Int,
    val name: String,
    val maxHealth: Float,
    val glowing: Boolean,
    val tint: Int,
    val dialogs: String,
    val clip: String,
    val materialMode: Int,
    val dropChances: String,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SetConfigPacket> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("glbrender", "set_config"))

        // Hand-rolled (not composite) so the field list can grow past StreamCodec.composite's arity without nesting.
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetConfigPacket> = StreamCodec.of(
            { buf, packet ->
                buf.writeVarInt(packet.entityId)
                buf.writeFloat(packet.scale)
                buf.writeFloat(packet.yawOffset)
                buf.writeFloat(packet.groundOffsetY)
                buf.writeBoolean(packet.wander)
                buf.writeBoolean(packet.followOwner)
                buf.writeBoolean(packet.sit)
                buf.writeBoolean(packet.lookPlayer)
                buf.writeVarInt(packet.profession)
                buf.writeVarInt(packet.onAttack)
                buf.writeUtf(packet.name, MAX_NAME)
                buf.writeFloat(packet.maxHealth)
                buf.writeBoolean(packet.glowing)
                buf.writeInt(packet.tint)
                buf.writeUtf(packet.dialogs, MAX_DIALOG)
                buf.writeUtf(packet.clip, MAX_CLIP)
                buf.writeVarInt(packet.materialMode)
                buf.writeUtf(packet.dropChances, MAX_DROP_CHANCES)
            },
            { buf ->
                SetConfigPacket(
                    buf.readVarInt(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readUtf(MAX_NAME),
                    buf.readFloat(),
                    buf.readBoolean(),
                    buf.readInt(),
                    buf.readUtf(MAX_DIALOG),
                    buf.readUtf(MAX_CLIP),
                    buf.readVarInt(),
                    buf.readUtf(MAX_DROP_CHANCES),
                )
            },
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
                config.sit = packet.sit
                config.lookPlayer = packet.lookPlayer
                // Clamp to the known profession set so a bad client can't select an unwired profession index.
                config.profession = packet.profession.coerceIn(
                    GlbNpcConfig.PROFESSION_NONE,
                    GlbNpcConfig.PROFESSION_GUARD,
                )
                // Clamp to the known response set so a bad client can't select an unwired behavior index.
                config.onAttack = packet.onAttack.coerceIn(
                    GlbNpcConfig.RESPONSE_RETALIATE,
                    GlbNpcConfig.RESPONSE_NOTHING,
                )
                // Name lives on vanilla Entity.customName (already synched + persisted), not in config. Empty clears it.
                val name = packet.name.trim()
                npc.setCustomName(if (name.isEmpty()) null else Component.literal(name))
                npc.setCustomNameVisible(true)
                // Health: store the synced mirror, then push it onto the real (server-only) attribute.
                config.maxHealth = if (packet.maxHealth.isNaN()) Float.NaN
                else packet.maxHealth.coerceIn(MIN_HEALTH, MAX_HEALTH)
                npc.applyMaxHealth()
                config.glowing = packet.glowing
                config.tint = packet.tint
                config.dialogs = packet.dialogs
                config.clip = packet.clip.trim()
                // Clamp to the known shading modes so a bad client can't select an unwired index.
                config.materialMode = packet.materialMode.coerceIn(
                    GlbNpcConfig.MATERIAL_PBR,
                    GlbNpcConfig.MATERIAL_UNLIT,
                )
                // Setter canonicalizes: pads to one entry per loot slot and clamps each percentage to 0..100.
                config.dropChances = packet.dropChances
            }
        }

        private const val SCALE_MIN = 0.01f
        private const val SCALE_MAX = 16.0f
        private const val MAX_NAME = 64
        private const val MAX_DIALOG = 1024
        private const val MAX_CLIP = 128
        private const val MAX_DROP_CHANCES = 128
        private const val MIN_HEALTH = 1.0f
        private const val MAX_HEALTH = 512.0f
    }
}

/**
 * Replaces an NPC's whole Stats block ([GlbNpcStats]). The block travels as one compound rather than ~40 packet fields
 * (the same shape it is synced and saved as), and the server clamps every range via [GlbNpcStats.coerced] before
 * applying, so a bad client cannot write out-of-range values.
 */
data class SetStatsPacket(val entityId: Int, val stats: GlbNpcStats) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SetStatsPacket> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("glbrender", "set_stats"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetStatsPacket> = StreamCodec.of(
            { buf, packet ->
                buf.writeVarInt(packet.entityId)
                buf.writeNbt(packet.stats.toTag())
            },
            { buf ->
                SetStatsPacket(buf.readVarInt(), GlbNpcStats.fromTag(buf.readNbt() ?: CompoundTag()))
            },
        )

        fun handle(packet: SetStatsPacket, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val npc = player.level().getEntity(packet.entityId) as? GlbNpc ?: return@enqueueWork
                if (!GlbNpcEditing.canEdit(player, npc)) {
                    LOGGER.warn("glbrender: rejected set_stats for {} from {} (no editing session)", packet.entityId, player.name)
                    return@enqueueWork
                }
                npc.config.stats = packet.stats.coerced()
                npc.applyStats()
            }
        }
    }
}

/**
 * Replaces an NPC's whole AI block ([GlbNpcAi]). Like [SetStatsPacket] it travels as one compound and is clamped via
 * [GlbNpcAi.coerced] before being stored and pushed onto navigation/attributes by [GlbNpc.applyAi].
 */
data class SetAiPacket(val entityId: Int, val ai: GlbNpcAi) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SetAiPacket> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("glbrender", "set_ai"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetAiPacket> = StreamCodec.of(
            { buf, packet ->
                buf.writeVarInt(packet.entityId)
                buf.writeNbt(packet.ai.toTag())
            },
            { buf ->
                SetAiPacket(buf.readVarInt(), GlbNpcAi.fromTag(buf.readNbt() ?: CompoundTag()))
            },
        )

        fun handle(packet: SetAiPacket, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val npc = player.level().getEntity(packet.entityId) as? GlbNpc ?: return@enqueueWork
                if (!GlbNpcEditing.canEdit(player, npc)) {
                    LOGGER.warn("glbrender: rejected set_ai for {} from {} (no editing session)", packet.entityId, player.name)
                    return@enqueueWork
                }
                npc.config.ai = packet.ai.coerced()
                npc.applyAi()
            }
        }
    }
}

/**
 * Replaces an NPC's whole Advanced block ([GlbNpcAdvanced]: lines, voice sounds, marks). Travels as one compound and is
 * clamped via [GlbNpcAdvanced.coerced] before being stored; the block is read live by gameplay and the renderer, so no
 * separate apply step exists (unlike Stats/AI, which push onto attributes/navigation).
 */
data class SetAdvancedPacket(val entityId: Int, val advanced: GlbNpcAdvanced) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SetAdvancedPacket> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("glbrender", "set_advanced"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetAdvancedPacket> = StreamCodec.of(
            { buf, packet ->
                buf.writeVarInt(packet.entityId)
                buf.writeNbt(packet.advanced.toTag())
            },
            { buf ->
                SetAdvancedPacket(buf.readVarInt(), GlbNpcAdvanced.fromTag(buf.readNbt() ?: CompoundTag()))
            },
        )

        fun handle(packet: SetAdvancedPacket, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val npc = player.level().getEntity(packet.entityId) as? GlbNpc ?: return@enqueueWork
                if (!GlbNpcEditing.canEdit(player, npc)) {
                    LOGGER.warn("glbrender: rejected set_advanced for {} from {} (no editing session)", packet.entityId, player.name)
                    return@enqueueWork
                }
                npc.config.advanced = packet.advanced.coerced()
            }
        }
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
                registrar.playToServer(SetStatsPacket.TYPE, SetStatsPacket.STREAM_CODEC) { packet, context ->
                    SetStatsPacket.handle(packet, context)
                }
                registrar.playToServer(SetAiPacket.TYPE, SetAiPacket.STREAM_CODEC) { packet, context ->
                    SetAiPacket.handle(packet, context)
                }
                registrar.playToServer(SetAdvancedPacket.TYPE, SetAdvancedPacket.STREAM_CODEC) { packet, context ->
                    SetAdvancedPacket.handle(packet, context)
                }
            },
        )
    }
}

private val LOGGER = LogUtils.getLogger()
