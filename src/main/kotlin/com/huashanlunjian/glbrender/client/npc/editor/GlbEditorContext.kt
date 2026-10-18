package com.huashanlunjian.glbrender.client.npc.editor

import net.minecraft.client.gui.Font

/**
 * The narrow surface a [GlbEditorTab] needs from its host screen: the client [Font], the editing target (entity id),
 * the real-feature bindings (model cycling + the per-instance config the Display/AI tabs wire up), and value formatting.
 * Everything else a tab builds is a placeholder, so it never needs the server or the entity beyond [npcId].
 *
 * [GlbNpcEditorScreen] implements this; keeping tabs behind this interface (instead of the concrete screen) stops the
 * tab classes from reaching into screen internals and lets non-feature tabs compile with no screen dependency at all.
 */
interface GlbEditorContext {
    val npcId: Int?

    fun font(): Font

    /** The registered GLB model ids, in cycle order. */
    val modelIds: List<String>

    /** The model id the edited NPC currently uses (raw synced value; may be empty = default). */
    fun currentModelId(): String

    /** Send a model change to the server. */
    fun applyModel(modelId: String)

    // Real transform/behavior values. Reading returns the seeded current value; writing stores it (the tab then calls
    // applyConfig() to commit the full packet). NaN "inherit" is resolved to a concrete number when the tab is built.
    var scale: Float
    var yawOffset: Float
    var groundOffsetY: Float
    var wander: Boolean
    var followOwner: Boolean
    var maxHealth: Float

    /** Whether the NPC renders at full brightness (glow). */
    var glowing: Boolean

    /** The NPC's display name (empty = none). Backed by vanilla `Entity.customName`, not the config struct. */
    var displayName: String

    /** Commit the current transform/behavior values to the server (one SetConfigPacket). */
    fun applyConfig()

    fun fmt(value: Float, decimals: Int): String
}
