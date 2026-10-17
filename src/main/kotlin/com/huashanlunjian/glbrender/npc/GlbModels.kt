package com.huashanlunjian.glbrender.npc

import com.google.gson.Gson
import com.mojang.logging.LogUtils
import net.neoforged.fml.loading.FMLPaths
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * The common (both-dists) table of NPC model descriptors, keyed by id in registration order. The server reads it to
 * cycle an NPC's synced modelId on interaction; the client `GlbModelRegistry` reads it to resolve a model path and
 * load its scene. Keeping the table common means the id list the server cycles through is exactly the set the
 * client can render.
 *
 * Descriptors come from JSON files under `config/glbrender/models/` (one file per model, filename order = cycle
 * order), loaded on both dists by [load]. If no config model is usable, it falls back to the code-registered
 * builtins so a fresh install still works. This is a STARTUP load — editing the JSON needs a restart; a `/reload`
 * hot-swap would also have to invalidate the client scene cache, which is deferred.
 */
object GlbModels {
    private val LOGGER = LogUtils.getLogger()
    private val GSON = Gson()

    private val descriptors = LinkedHashMap<String, GlbModelDescriptor>()

    /** The model an NPC with no explicit modelId falls back to: the first registered descriptor. */
    var defaultId: String? = null
        private set

    fun register(descriptor: GlbModelDescriptor) {
        val previous = descriptors.put(descriptor.id, descriptor)
        if (previous != null) LOGGER.warn("glbrender: model id {} registered twice, overriding", descriptor.id)
        if (defaultId == null) defaultId = descriptor.id
    }

    fun descriptor(id: String): GlbModelDescriptor? = descriptors[id]

    /** Registration order — the cycle order used by [GlbNpc.mobInteract]. */
    fun ids(): List<String> = descriptors.keys.toList()

    /** Entry point called from [com.huashanlunjian.glbrender.GlbRenderContent.register] on BOTH dists. */
    fun load() {
        val fromConfig = loadFromConfig()
        if (fromConfig == 0) {
            LOGGER.info("glbrender: no usable model config, registering builtin models")
            registerBuiltins()
        }
        LOGGER.info("glbrender: {} npc model(s) registered: {}", descriptors.size, ids())
    }

    /**
     * Scans the JSON files under `config/glbrender/models/`, parses each into a descriptor and registers it. Returns
     * how many were registered. A file that fails to parse, or whose asset is missing, is logged and skipped (TLM's
     * loader is likewise per-file tolerant) so one bad entry never blocks the rest.
     */
    private fun loadFromConfig(): Int {
        val dir = FMLPaths.CONFIGDIR.get().resolve("glbrender").resolve("models").toFile()
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: return 0
        var count = 0
        for (file in files) {
            try {
                val info = file.inputStream().use { stream ->
                    GSON.fromJson(InputStreamReader(stream, StandardCharsets.UTF_8), GlbModelInfo::class.java)
                }
                if (info == null) {
                    LOGGER.warn("glbrender: empty model config {}", file.name)
                    continue
                }
                val descriptor = info.decorate(file.name)
                if (!descriptor.modelPath.toFile().isFile) {
                    LOGGER.warn(
                        "glbrender: model file for id {} not found ({}), skipping {}",
                        descriptor.id, descriptor.modelPath, file.name,
                    )
                    continue
                }
                register(descriptor)
                count++
            } catch (error: Exception) {
                LOGGER.warn("glbrender: failed to load model config {}", file.name, error)
            }
        }
        return count
    }

    /**
     * Code-registered fallback models (used only when no config JSON is present). Luka keeps the Step 2
     * `-Dglbrender.npc.*` tuning props; cesium is the second model that proves multi-model dispatch.
     */
    private fun registerBuiltins() {
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
