package com.huashanlunjian.glbrender.npc

import java.nio.file.Path

/**
 * Pure data describing one GLB NPC model: where it lives and how it is placed in the world. Holds no render state
 * and no client-only types, so it is safe on BOTH dists — the server needs the id/scale table to cycle an NPC's
 * model, the client needs the path to load the scene. [GlbModels] owns the table; the client
 * `GlbModelRegistry` owns the loaded scene/renderer side.
 */
data class GlbModelDescriptor(
    val id: String,
    val modelPath: Path,
    val scale: Float = 1.0f,
    val yawOffset: Float = 0.0f,
    /** Model-local vertical offset applied after [scale]; 0 leaves the model exactly where it loads. */
    val groundOffsetY: Float = 0.0f,
)
