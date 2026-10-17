package com.huashanlunjian.glbrender.client.npc

import java.nio.file.Path

/**
 * Pure data describing one GLB NPC model: where it lives and how it is placed in the world. Holds no render
 * state — [GlbModelRegistry] owns the loaded scene/renderer side. Step 3 keys NPC rendering off a descriptor
 * id instead of the hardwired Luka props, so adding a model is a `register` call now (3a) and a JSON entry
 * later (3c).
 */
data class GlbModelDescriptor(
    val id: String,
    val modelPath: Path,
    val scale: Float = 1.0f,
    val yawOffset: Float = 0.0f,
    /** Model-local vertical offset applied after [scale]; 0 leaves the model exactly where it loads. */
    val groundOffsetY: Float = 0.0f,
)
