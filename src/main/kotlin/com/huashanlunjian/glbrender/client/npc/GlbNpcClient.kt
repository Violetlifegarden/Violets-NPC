package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.GlbRenderContent
import com.mojang.logging.LogUtils
import net.minecraft.world.item.CreativeModeTabs
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.common.NeoForge
import java.nio.file.Path
import java.util.function.Consumer

/**
 * Client-only registrations for the NPC: its [GlbNpcRenderer], its spawn egg in the creative Spawning tab, the
 * builtin model descriptors, and the once-per-frame pool rotation the shared renderer needs. References
 * client-only classes, so [register] is called only under the client dist guard in the mod constructor.
 */
object GlbNpcClient {
    private val LOGGER = LogUtils.getLogger()

    fun register(bus: IEventBus) {
        registerBuiltinModels()

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
        // Rewind the shared renderer's ring-buffer pool once per frame, before entities draw. AFTER_SKY is the
        // earliest stage that fires exactly once per level-render frame and precedes entity rendering.
        NeoForge.EVENT_BUS.addListener(
            EventPriority.NORMAL,
            RenderLevelStageEvent::class.java,
            Consumer { event ->
                if (event.stage === RenderLevelStageEvent.Stage.AFTER_SKY) {
                    GlbModelRegistry.rotateForFrame()
                }
            },
        )
    }

    /**
     * Registers the models that ship with the mod (currently just Luka). Scale/yaw come from the same tuning props
     * Step 2 used, so a `-Dglbrender.npc.*` override still applies. If the asset is missing the descriptor is simply
     * not registered and NPCs stay invisible, matching the old behavior.
     */
    private fun registerBuiltinModels() {
        val lukaPath = resolveLukaPath() ?: return
        GlbModelRegistry.register(
            GlbModelDescriptor(
                id = "luka",
                modelPath = lukaPath,
                scale = System.getProperty("glbrender.npc.scale", "1.0").toFloat(),
                yawOffset = System.getProperty("glbrender.npc.yawOffset", "0").toFloat(),
            ),
        )
    }

    private fun resolveLukaPath(): Path? {
        val gameDir = FMLPaths.GAMEDIR.get()
        val configured = System.getProperty("glbrender.npcModel")?.takeIf { it.isNotBlank() }
        if (configured != null) {
            val given = Path.of(configured)
            return sequenceOf(given, gameDir.resolve(configured), gameDir.resolve("models").resolve(configured))
                .firstOrNull { it.toFile().isFile }
                ?: run {
                    LOGGER.warn("glbrender: -Dglbrender.npcModel={} not found under {}", configured, gameDir)
                    null
                }
        }
        return gameDir.resolve("models").resolve("luka.glb").takeIf { it.toFile().isFile }
            ?: gameDir.resolve("luka.glb").takeIf { it.toFile().isFile }
    }
}
