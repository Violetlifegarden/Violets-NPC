package com.huashanlunjian.glbrender.npc

import com.mojang.logging.LogUtils
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Path

/**
 * The common (both-dists) table of NPC model descriptors, keyed by id in registration order. The server reads it to
 * cycle an NPC's synced modelId on interaction; the client `GlbModelRegistry` reads it to resolve a model path and
 * load its scene. Keeping the table common means the id list the server cycles through is exactly the set the
 * client can render — 3c will populate this same table from JSON on both dists (a TLM-style client/server pack pair).
 */
object GlbModels {
    private val LOGGER = LogUtils.getLogger()

    private val descriptors = LinkedHashMap<String, GlbModelDescriptor>()

    /** The model an NPC with no explicit modelId falls back to: the first registered descriptor. */
    var defaultId: String? = null
        private set

    fun register(descriptor: GlbModelDescriptor) {
        descriptors[descriptor.id] = descriptor
        if (defaultId == null) defaultId = descriptor.id
    }

    fun descriptor(id: String): GlbModelDescriptor? = descriptors[id]

    /** Registration order — the cycle order used by [GlbNpc.mobInteract]. */
    fun ids(): List<String> = descriptors.keys.toList()

    /**
     * Registers the models that ship with the mod. Called on both dists from [com.huashanlunjian.glbrender.GlbRenderContent.register].
     * A model whose asset is missing is simply not registered (NPCs then fall back to whatever is present), matching
     * the pre-registry behavior. Luka keeps the Step 2 `-Dglbrender.npc.*` tuning props; cesium is the second model
     * that proves multi-model dispatch (reachable in-game by right-clicking an NPC).
     */
    fun registerBuiltins() {
        resolveLukaPath()?.let { path ->
            register(
                GlbModelDescriptor(
                    id = "luka",
                    modelPath = path,
                    scale = System.getProperty("glbrender.npc.scale", "1.0").toFloat(),
                    yawOffset = System.getProperty("glbrender.npc.yawOffset", "0").toFloat(),
                ),
            )
        }
        resolveModelFile("cesium.glb")?.let { path ->
            register(GlbModelDescriptor(id = "cesium", modelPath = path))
        }
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
        return resolveModelFile("luka.glb")
    }

    private fun resolveModelFile(name: String): Path? {
        val gameDir = FMLPaths.GAMEDIR.get()
        return gameDir.resolve("models").resolve(name).takeIf { it.toFile().isFile }
            ?: gameDir.resolve(name).takeIf { it.toFile().isFile }
    }
}
