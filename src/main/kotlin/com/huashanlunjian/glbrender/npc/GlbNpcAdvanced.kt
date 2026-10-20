package com.huashanlunjian.glbrender.npc

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

/** One spoken line: the text plus an optional sound id played with it (CustomNPC `Line`). */
data class GlbNpcLine(
    var text: String = "",
    var sound: String = "",
) {
    fun isEmpty(): Boolean = text.isBlank() && sound.isBlank()
}

/** One overhead mark: one of the [GlbNpcMark.TYPE_*] glyphs drawn in [color] (CustomNPC `MarkData.Mark`). */
data class GlbNpcMark(
    var type: Int = TYPE_NONE,
    var color: Int = DEFAULT_COLOR,
) {
    companion object {
        const val TYPE_NONE = 0
        const val TYPE_QUESTION = 1
        const val TYPE_EXCLAMATION = 2
        const val TYPE_POINTER = 3
        const val TYPE_SKULL = 4
        const val TYPE_CROSS = 5
        const val TYPE_STAR = 6

        /** CustomNPC's default mark yellow (`16772433`). */
        const val DEFAULT_COLOR = 0xFFED51

        val TYPE_OPTIONS = arrayOf("None", "Question", "Exclamation", "Pointer", "Skull", "Cross", "Star")

        /** The glyph drawn for each type; index 0 (None) is never drawn. */
        val GLYPHS = arrayOf("", "?", "!", "\u25BC", "\u2620", "\u2716", "\u2605")
    }
}

/**
 * The Advanced tab's self-contained block: spoken lines, voice sounds and overhead marks, mirroring the CustomNPC
 * `DataAdvanced` fields that need no world-side controller (factions, roles, scenes and scripted dialogs are out of
 * scope). Like [GlbNpcAi] it is a plain mutable holder — the editor mutates a copy and commits it in one
 * [com.huashanlunjian.glbrender.npc.net.SetAdvancedPacket], and the server stores the canonical copy in
 * [GlbNpcConfig.advanced] as a single synced `COMPOUND_TAG`.
 *
 * Lines are six fixed categories of eight slots; an empty slot is simply skipped when picking. [orderedLines] is the
 * CNPC global toggle: false = random line each time, true = walk the filled slots in order.
 *
 * [faction], [dialogIds] and [linkedName] reference the world-level tables in
 * [com.huashanlunjian.glbrender.npc.world.GlbNpcWorldData] by id/name, so the entity carries no copies of the names
 * that a Global-tab rename would have to rewrite.
 */
class GlbNpcAdvanced(
    /** `[category][slot]`, always [LINE_COUNT] × [LINES_PER_CATEGORY]. */
    val lines: MutableList<MutableList<GlbNpcLine>> = defaultLines(),
    /** Pick lines in slot order instead of at random (CNPC `orderedLines`). */
    var orderedLines: Boolean = false,
    /** Ambient sound id (empty = the vanilla mob default, i.e. silence). */
    var idleSound: String = "",
    /** Sound played on a successful attack. */
    var angrySound: String = "",
    /** Hurt sound id (empty = vanilla generic hurt). */
    var hurtSound: String = "",
    /** Death sound id (empty = vanilla generic death). */
    var deathSound: String = "",
    /** Footstep sound id (empty = the block's own step sound). */
    var stepSound: String = "",
    /** Play every NPC sound at pitch 1.0 instead of vanilla's randomized voice pitch. */
    var disablePitch: Boolean = false,
    /** Fixed [MAX_MARKS] slots; [GlbNpcMark.TYPE_NONE] slots are not drawn. */
    val marks: MutableList<GlbNpcMark> = MutableList(MAX_MARKS) { GlbNpcMark() },
    /** World faction id (`GlbNpcWorldData.factions`) or [FACTION_NONE]. */
    var faction: Int = FACTION_NONE,
    /** Acquire and melee members of other factions on sight (CNPC "Attack other factions"). */
    var attackOtherFactions: Boolean = false,
    /** Join a same-faction member's fight when the attacker is identified (CNPC "Defending faction"). */
    var defendFaction: Boolean = false,
    /** Up to [MAX_DIALOG_SLOTS] world dialog ids offered when a player talks; 0 = empty slot. */
    val dialogIds: MutableList<Int> = defaultDialogIds(),
    /** Name from the world linked-NPC list (empty = none). CNPC's link is a scripting hook; ours is data-only. */
    var linkedName: String = "",
) {
    fun copyAdvanced(): GlbNpcAdvanced = GlbNpcAdvanced(
        lines = MutableList(LINE_COUNT) { category ->
            MutableList(LINES_PER_CATEGORY) { slot -> lines[category][slot].copy() }
        },
        orderedLines = orderedLines,
        idleSound = idleSound,
        angrySound = angrySound,
        hurtSound = hurtSound,
        deathSound = deathSound,
        stepSound = stepSound,
        disablePitch = disablePitch,
        marks = marks.map { it.copy() }.toMutableList(),
        faction = faction,
        attackOtherFactions = attackOtherFactions,
        defendFaction = defendFaction,
        dialogIds = dialogIds.toMutableList(),
        linkedName = linkedName,
    )

    fun line(category: Int, slot: Int): GlbNpcLine = lines[category][slot]

    /** The dialog ids actually bound (non-zero, in slot order). */
    fun boundDialogIds(): List<Int> = dialogIds.filter { it > 0 }

    fun hasLines(category: Int): Boolean = lines[category].any { !it.isEmpty() }

    /** The non-empty lines of [category] in slot order. */
    fun filledLines(category: Int): List<GlbNpcLine> = lines[category].filter { !it.isEmpty() }

    fun toTag(): CompoundTag = CompoundTag().also { tag ->
        val lineList = ListTag()
        for (category in 0 until LINE_COUNT) {
            for (slot in 0 until LINES_PER_CATEGORY) {
                val line = lines[category][slot]
                if (line.isEmpty()) continue
                lineList.add(CompoundTag().also { entry ->
                    entry.putInt(KEY_CATEGORY, category)
                    entry.putInt(KEY_SLOT, slot)
                    entry.putString(KEY_TEXT, line.text)
                    entry.putString(KEY_SOUND, line.sound)
                })
            }
        }
        tag.put(KEY_LINES, lineList)
        tag.putBoolean(KEY_ORDERED, orderedLines)
        tag.putString(KEY_IDLE_SOUND, idleSound)
        tag.putString(KEY_ANGRY_SOUND, angrySound)
        tag.putString(KEY_HURT_SOUND, hurtSound)
        tag.putString(KEY_DEATH_SOUND, deathSound)
        tag.putString(KEY_STEP_SOUND, stepSound)
        tag.putBoolean(KEY_DISABLE_PITCH, disablePitch)
        val markList = ListTag()
        for (mark in marks) {
            markList.add(CompoundTag().also { entry ->
                entry.putInt(KEY_MARK_TYPE, mark.type)
                entry.putInt(KEY_MARK_COLOR, mark.color)
            })
        }
        tag.put(KEY_MARKS, markList)
        tag.putInt(KEY_FACTION, faction)
        tag.putBoolean(KEY_ATTACK_OTHER_FACTIONS, attackOtherFactions)
        tag.putBoolean(KEY_DEFEND_FACTION, defendFaction)
        tag.putIntArray(KEY_DIALOG_IDS, dialogIds.toIntArray())
        tag.putString(KEY_LINKED_NAME, linkedName)
    }

    /** Server-side clamp applied to every packet-carried copy (and every NBT load): lengths, ids, ranges. */
    fun coerced(): GlbNpcAdvanced {
        val copy = copyAdvanced()
        for (category in 0 until LINE_COUNT) {
            for (slot in 0 until LINES_PER_CATEGORY) {
                val line = copy.lines[category][slot]
                line.text = line.text.take(MAX_TEXT)
                line.sound = cleanResource(line.sound)
            }
        }
        copy.idleSound = cleanResource(copy.idleSound)
        copy.angrySound = cleanResource(copy.angrySound)
        copy.hurtSound = cleanResource(copy.hurtSound)
        copy.deathSound = cleanResource(copy.deathSound)
        copy.stepSound = cleanResource(copy.stepSound)
        for (mark in copy.marks) {
            mark.type = mark.type.coerceIn(GlbNpcMark.TYPE_NONE, GlbNpcMark.TYPE_STAR)
            mark.color = mark.color and 0xFFFFFF
        }
        copy.faction = copy.faction.coerceAtLeast(FACTION_NONE)
        for (index in copy.dialogIds.indices) {
            copy.dialogIds[index] = copy.dialogIds[index].coerceAtLeast(0)
        }
        copy.linkedName = copy.linkedName.trim().take(MAX_LINKED_NAME)
        return copy
    }

    companion object {
        const val LINE_INTERACT = 0
        const val LINE_WORLD = 1
        const val LINE_ATTACK = 2
        const val LINE_KILLED = 3
        const val LINE_KILL = 4
        const val LINE_NPC_INTERACT = 5

        const val LINE_COUNT = 6
        const val LINES_PER_CATEGORY = 8
        const val MAX_MARKS = 9

        /** CNPC's dialog-option grid width. */
        const val MAX_DIALOG_SLOTS = 12
        const val MAX_LINKED_NAME = 64

        /** "No faction" id; real faction ids are allocated by the world data and are always positive. */
        const val FACTION_NONE = 0

        const val MAX_TEXT = 128
        const val MAX_SOUND = 128

        val LINE_CATEGORY_NAMES = arrayOf("Interact", "World", "Attack", "Killed", "Kill", "NPC Interact")

        private fun defaultLines(): MutableList<MutableList<GlbNpcLine>> =
            MutableList(LINE_COUNT) { MutableList(LINES_PER_CATEGORY) { GlbNpcLine() } }

        private fun defaultDialogIds(): MutableList<Int> = MutableList(MAX_DIALOG_SLOTS) { 0 }

        /** Trims and truncates a sound id (`minecraft:entity.player.hurt` style); empty stays empty. */
        private fun cleanResource(raw: String): String {
            val id = raw.trim()
            if (id.isEmpty()) return ""
            return if (id.contains(':')) id.take(MAX_SOUND) else "minecraft:$id".take(MAX_SOUND)
        }

        /**
         * Parses an advanced compound, merging over defaults so an older/partial tag keeps the default sound and line
         * layout instead of collapsing to an all-empty block.
         */
        fun fromTag(tag: CompoundTag): GlbNpcAdvanced {
            val advanced = GlbNpcAdvanced()
            val lineList = tag.getList(KEY_LINES, Tag.TAG_COMPOUND.toInt())
            for (index in 0 until lineList.size) {
                val entry = lineList.getCompound(index)
                val category = entry.getInt(KEY_CATEGORY)
                val slot = entry.getInt(KEY_SLOT)
                if (category !in 0 until LINE_COUNT || slot !in 0 until LINES_PER_CATEGORY) continue
                advanced.lines[category][slot] = GlbNpcLine(
                    text = entry.getString(KEY_TEXT),
                    sound = entry.getString(KEY_SOUND),
                )
            }
            advanced.orderedLines = tag.getBoolean(KEY_ORDERED)
            advanced.idleSound = tag.getString(KEY_IDLE_SOUND)
            advanced.angrySound = tag.getString(KEY_ANGRY_SOUND)
            advanced.hurtSound = tag.getString(KEY_HURT_SOUND)
            advanced.deathSound = tag.getString(KEY_DEATH_SOUND)
            advanced.stepSound = tag.getString(KEY_STEP_SOUND)
            advanced.disablePitch = tag.getBoolean(KEY_DISABLE_PITCH)
            val markList = tag.getList(KEY_MARKS, Tag.TAG_COMPOUND.toInt())
            for (index in 0 until minOf(markList.size, MAX_MARKS)) {
                val entry = markList.getCompound(index)
                advanced.marks[index] = GlbNpcMark(
                    type = entry.getInt(KEY_MARK_TYPE),
                    color = entry.getInt(KEY_MARK_COLOR),
                )
            }
            advanced.faction = tag.getInt(KEY_FACTION)
            advanced.attackOtherFactions = tag.getBoolean(KEY_ATTACK_OTHER_FACTIONS)
            advanced.defendFaction = tag.getBoolean(KEY_DEFEND_FACTION)
            val dialogIds = tag.getIntArray(KEY_DIALOG_IDS)
            for (index in 0 until minOf(dialogIds.size, MAX_DIALOG_SLOTS)) {
                advanced.dialogIds[index] = dialogIds[index]
            }
            advanced.linkedName = tag.getString(KEY_LINKED_NAME)
            return advanced
        }

        private const val KEY_LINES = "Lines"
        private const val KEY_CATEGORY = "Category"
        private const val KEY_SLOT = "Slot"
        private const val KEY_TEXT = "Text"
        private const val KEY_SOUND = "Sound"
        private const val KEY_ORDERED = "OrderedLines"
        private const val KEY_IDLE_SOUND = "IdleSound"
        private const val KEY_ANGRY_SOUND = "AngrySound"
        private const val KEY_HURT_SOUND = "HurtSound"
        private const val KEY_DEATH_SOUND = "DeathSound"
        private const val KEY_STEP_SOUND = "StepSound"
        private const val KEY_DISABLE_PITCH = "DisablePitch"
        private const val KEY_MARKS = "Marks"
        private const val KEY_MARK_TYPE = "Type"
        private const val KEY_MARK_COLOR = "Color"
        private const val KEY_FACTION = "Faction"
        private const val KEY_ATTACK_OTHER_FACTIONS = "AttackOtherFactions"
        private const val KEY_DEFEND_FACTION = "DefendFaction"
        private const val KEY_DIALOG_IDS = "DialogIds"
        private const val KEY_LINKED_NAME = "LinkedName"
    }
}
