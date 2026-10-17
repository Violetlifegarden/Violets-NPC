package com.huashanlunjian.glbrender

import com.huashanlunjian.glbrender.npc.GlbModels
import com.huashanlunjian.glbrender.npc.GlbNpc
import com.huashanlunjian.glbrender.npc.net.GlbRenderNetwork
import net.minecraft.core.registries.Registries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.Item
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.DeferredSpawnEggItem
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * Server-safe registrations: the [GlbNpc] entity type, its attribute supplier, and its spawn egg. These must run on
 * BOTH dists (a dedicated server needs the entity + item + attributes), so [register] is called unconditionally from
 * the mod constructor. The client-only renderer + creative-tab wiring lives in
 * [com.huashanlunjian.glbrender.client.npc.GlbNpcClient].
 */
object GlbRenderContent {
    private val ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, GlbRenderMod.MOD_ID)
    private val ITEMS = DeferredRegister.create(Registries.ITEM, GlbRenderMod.MOD_ID)

    val GLB_NPC: DeferredHolder<EntityType<*>, EntityType<GlbNpc>> =
        ENTITY_TYPES.register(
            "glb_npc",
            Supplier {
                EntityType.Builder.of({ type, level -> GlbNpc(type, level) }, MobCategory.CREATURE)
                    .sized(0.6f, 1.8f)
                    .build("glb_npc")
            },
        )

    val GLB_NPC_SPAWN_EGG: DeferredHolder<Item, DeferredSpawnEggItem> =
        ITEMS.register(
            "glb_npc_spawn_egg",
            Supplier { DeferredSpawnEggItem(GLB_NPC, 0x2B7F8F, 0xF0A0C0, Item.Properties()) },
        )

    fun register(bus: IEventBus) {
        // The model-descriptor table is common data: the server cycles an NPC's modelId through it, the client
        // resolves paths from it. Load it (config JSON, else builtins) on both dists before either side queries it.
        GlbModels.load()

        ENTITY_TYPES.register(bus)
        ITEMS.register(bus)
        // Editor C2S payloads are common (a dedicated server receives them too), so register on the mod bus unconditionally.
        GlbRenderNetwork.register(bus)
        bus.addListener(
            EventPriority.NORMAL,
            EntityAttributeCreationEvent::class.java,
            Consumer { event -> event.put(GLB_NPC.get(), GlbNpc.attributes().build()) },
        )
    }
}
