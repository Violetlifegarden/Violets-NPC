package com.huashanlunjian.glbrender.npc.world

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag

/**
 * One world-wide faction definition (CustomNPC Global → Factions). The per-NPC Advanced block stores only
 * [GlbNpcAdvanced.faction] as an [id], so renaming or recoloring a faction updates every member without touching an
 * entity. Entries are edited through the Global tab's manager and persisted by [GlbNpcWorldData].
 */
data class GlbFaction(
    var id: Int = 0,
    var name: String = "",
    var color: Int = DEFAULT_COLOR,
) {
    fun copyFaction(): GlbFaction = GlbFaction(id, name, color)

    fun toTag(): CompoundTag = CompoundTag().also { tag ->
        tag.putInt(KEY_ID, id)
        tag.putString(KEY_NAME, name)
        tag.putInt(KEY_COLOR, color)
    }

    companion object {
        const val MAX_NAME = 64

        /** CNPC's default faction color (gold). */
        const val DEFAULT_COLOR = 0xFFAA00

        fun fromTag(tag: CompoundTag): GlbFaction = GlbFaction(
            id = tag.getInt(KEY_ID),
            name = tag.getString(KEY_NAME),
            color = tag.getInt(KEY_COLOR),
        )

        private const val KEY_ID = "Id"
        private const val KEY_NAME = "Name"
        private const val KEY_COLOR = "Color"
    }
}

/**
 * One world-wide dialog (CustomNPC Global → Dialogs), deliberately reduced to what our talk screen can show: a title
 * plus the lines walked on click. The per-NPC Advanced block points at up to twelve of these by [id]; CNPC's option
 * trees, quests, faction requirements and commands are out of scope.
 */
data class GlbDialogDef(
    var id: Int = 0,
    var title: String = "",
    var lines: MutableList<String> = mutableListOf(),
) {
    fun copyDialog(): GlbDialogDef = GlbDialogDef(id, title, lines.toMutableList())

    /** The lines to show, falling back to the title so a line-less dialog still says something. */
    fun displayLines(): List<String> = lines.ifEmpty { listOf(title) }

    fun toTag(): CompoundTag = CompoundTag().also { tag ->
        tag.putInt(KEY_ID, id)
        tag.putString(KEY_TITLE, title)
        tag.put(KEY_LINES, ListTag().also { list ->
            lines.forEach { list.add(StringTag.valueOf(it)) }
        })
    }

    companion object {
        const val MAX_TITLE = 64
        const val MAX_LINES = 16
        const val MAX_LINE_LENGTH = 256

        fun fromTag(tag: CompoundTag): GlbDialogDef {
            val lineList = tag.getList(KEY_LINES, Tag.TAG_STRING.toInt())
            val lines = MutableList(lineList.size) { index -> lineList.getString(index) }
            return GlbDialogDef(tag.getInt(KEY_ID), tag.getString(KEY_TITLE), lines)
        }

        private const val KEY_ID = "Id"
        private const val KEY_TITLE = "Title"
        private const val KEY_LINES = "Lines"
    }
}

/** A named world coordinate (CustomNPC Global → Transport), editable from the Global tab. Data-only for now. */
data class GlbTransportLocation(
    var id: Int = 0,
    var name: String = "",
    var dimension: String = "minecraft:overworld",
    var x: Double = 0.0,
    var y: Double = 0.0,
    var z: Double = 0.0,
) {
    fun copyLocation(): GlbTransportLocation = GlbTransportLocation(id, name, dimension, x, y, z)

    fun toTag(): CompoundTag = CompoundTag().also { tag ->
        tag.putInt(KEY_ID, id)
        tag.putString(KEY_NAME, name)
        tag.putString(KEY_DIMENSION, dimension)
        tag.putDouble(KEY_X, x)
        tag.putDouble(KEY_Y, y)
        tag.putDouble(KEY_Z, z)
    }

    companion object {
        const val MAX_NAME = 64
        const val DEFAULT_DIMENSION = "minecraft:overworld"

        fun fromTag(tag: CompoundTag): GlbTransportLocation = GlbTransportLocation(
            id = tag.getInt(KEY_ID),
            name = tag.getString(KEY_NAME),
            dimension = tag.getString(KEY_DIMENSION),
            x = tag.getDouble(KEY_X),
            y = tag.getDouble(KEY_Y),
            z = tag.getDouble(KEY_Z),
        )

        private const val KEY_ID = "Id"
        private const val KEY_NAME = "Name"
        private const val KEY_DIMENSION = "Dimension"
        private const val KEY_X = "X"
        private const val KEY_Y = "Y"
        private const val KEY_Z = "Z"
    }
}
