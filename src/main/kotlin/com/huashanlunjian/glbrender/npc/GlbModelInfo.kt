package com.huashanlunjian.glbrender.npc

import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Path

/**
 * Gson shape of one JSON file under `config/glbrender/models/` (one file per model). Mirrors TLM's `MaidModelInfo`
 * (per-model info POJO + a `decorate()` validate/default/clamp step) but trimmed to what a GLB descriptor needs.
 * Unlike TLM, `model` is a filesystem path (relative to the game dir, or absolute) rather than a pack
 * `ResourceLocation` — our assets are loose `.glb` files, not resource-pack entries.
 */
class GlbModelInfo {
    @SerializedName("id")
    private var id: String? = null

    @SerializedName("model")
    private var model: String? = null

    @SerializedName("scale")
    private var scale: Float = 1.0f

    @SerializedName("yaw_offset")
    private var yawOffset: Float = 0.0f

    @SerializedName("ground_offset_y")
    private var groundOffsetY: Float = 0.0f

    /**
     * Validates the required fields and resolves into a [GlbModelDescriptor], applying defaults and clamping the
     * scale like TLM's `decorate()`. Throws [JsonSyntaxException] only for missing/blank `id` or `model`; a missing
     * asset FILE is not a syntax error, so the caller checks existence and skips (TLM's loader does the same).
     */
    fun decorate(sourceName: String): GlbModelDescriptor {
        val id = id?.takeIf { it.isNotBlank() }
            ?: throw JsonSyntaxException("Expected \"id\" in $sourceName")
        val modelRaw = model?.takeIf { it.isNotBlank() }
            ?: throw JsonSyntaxException("Expected \"model\" in $sourceName (id=$id)")
        return GlbModelDescriptor(
            id = id,
            modelPath = resolvePath(modelRaw),
            scale = scale.coerceIn(SCALE_MIN, SCALE_MAX),
            yawOffset = yawOffset,
            groundOffsetY = groundOffsetY,
        )
    }

    private fun resolvePath(raw: String): Path {
        val given = Path.of(raw)
        if (given.isAbsolute) return given
        return FMLPaths.GAMEDIR.get().resolve(raw)
    }

    companion object {
        private const val SCALE_MIN = 0.01f
        private const val SCALE_MAX = 16.0f
    }
}
