package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.GlbRenderContent
import net.minecraft.world.item.CreativeModeTabs
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.common.NeoForge
import java.util.function.Consumer

/**
 * Client-only registrations for the NPC: its [GlbNpcRenderer], its spawn egg in the creative Spawning tab, and the
 * once-per-frame pool rotation the renderer needs. References client-only classes, so [register] is called only under
 * the client dist guard in the mod constructor.
 */
object GlbNpcClient {
    fun register(bus: IEventBus) {
        bus.addListener(
            EventPriority.NORMAL,
            EntityRenderersEvent.RegisterRenderers::class.java,
            Consumer { event ->
                event.registerEntityRenderer(GlbRenderContent.GLB_NPC.get(), ::GlbNpcRenderer)
            },
        )
        bus.addListener(
            EventPriority.NORMAL,
            BuildCreativeModeTabContentsEvent::class.java,
            Consumer { event ->
                if (event.tabKey == CreativeModeTabs.SPAWN_EGGS) {
                    event.accept(GlbRenderContent.GLB_NPC_SPAWN_EGG.get())
                }
            },
        )
        // Rewind the renderer's ring-buffer pool once per frame, before entities draw. AFTER_SKY is the earliest
        // stage that fires exactly once per level-render frame and precedes entity rendering.
        NeoForge.EVENT_BUS.addListener(
            EventPriority.NORMAL,
            RenderLevelStageEvent::class.java,
            Consumer { event ->
                if (event.stage === RenderLevelStageEvent.Stage.AFTER_SKY) {
                    GlbNpcModels.rotateForFrame()
                }
            },
        )
    }
}
