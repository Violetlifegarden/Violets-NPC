package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.GlbRenderContent
import com.huashanlunjian.glbrender.client.npc.world.GlbNpcWorldCache
import com.huashanlunjian.glbrender.npc.GlbNpcClientHooks
import net.minecraft.client.Minecraft
import net.minecraft.world.item.CreativeModeTabs
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.common.NeoForge
import java.util.function.Consumer

/**
 * Client-only registrations for the NPC: its [GlbNpcRenderer], its spawn egg in the creative Spawning tab, and the
 * once-per-frame maintenance the render path needs. The model-descriptor table itself is common data registered from
 * [GlbRenderContent.register]. References client-only classes, so [register] runs only under the client dist guard.
 */
object GlbNpcClient {
    fun register(bus: IEventBus) {
        // The talk interaction opens its screen from the client side; the common GlbNpc reaches it through this hook
        // so it never imports a client-only class.
        GlbNpcClientHooks.openDialog = { name, lines ->
            Minecraft.getInstance().setScreen(GlbDialogScreen(name, lines))
        }
        GlbNpcClientHooks.applyWorldData = { tag -> GlbNpcWorldCache.apply(tag) }
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
        // Bind the editor menu to its client screen (the panel opened by GlbNpc.mobInteract).
        bus.addListener(
            EventPriority.NORMAL,
            RegisterMenuScreensEvent::class.java,
            Consumer { event ->
                event.register(GlbRenderContent.GLB_NPC_MENU.get()) { menu, inv, title ->
                    GlbNpcEditorScreen(menu, inv, title)
                }
            },
        )
        // Per-frame maintenance around entity drawing. AFTER_SKY is the earliest stage that fires exactly once per
        // level-render frame and precedes entity rendering: rotate the shared renderer's ring-buffer pool (else it
        // grows unbounded -> GL_INVALID_VALUE, the Step 2 crash) and release instances of NPCs that left the level.
        // AFTER_ENTITIES flushes the batched NPC tasks once, while the world projection/modelview are still active.
        NeoForge.EVENT_BUS.addListener(
            EventPriority.NORMAL,
            RenderLevelStageEvent::class.java,
            Consumer { event ->
                if (event.stage === RenderLevelStageEvent.Stage.AFTER_SKY) {
                    GlbModelRegistry.rotateForFrame()
                    GlbNpcModels.pruneRemoved()
                } else if (event.stage === RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
                    GlbNpcModels.executeScheduled()
                }
            },
        )
    }
}
