package com.huashanlunjian.glbrender.npc.world

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import java.util.function.BiFunction
import java.util.function.Supplier

/**
 * The mod's one world-level data file (CustomNPC's `GlobalData` analogue): the factions, dialogs, linked-NPC names and
 * transport locations that per-NPC blocks point at by id. Stored in the overworld's [net.minecraft.world.level.storage.DimensionDataStorage]
 * so it is shared by every dimension and survives restarts.
 *
 * Two edit paths share the same shape:
 *  - the Global tab managers replace a whole section ([replaceSection]) — edits are op-gated in the packet handler;
 *  - the per-NPC Advanced block stores only ids/names, so it needs no world writes.
 *
 * Every write is clamped ([coerceFactions] etc.) so a malformed packet or hand-edited save can never grow the file
 * without bound or create duplicate ids.
 */
class GlbNpcWorldData : SavedData() {

    private val factions = ArrayList<GlbFaction>()
    private val dialogs = ArrayList<GlbDialogDef>()
    private val linkedNames = ArrayList<String>()
    private val transport = ArrayList<GlbTransportLocation>()

    fun factions(): List<GlbFaction> = factions
    fun dialogs(): List<GlbDialogDef> = dialogs
    fun linkedNames(): List<String> = linkedNames
    fun transport(): List<GlbTransportLocation> = transport

    fun faction(id: Int): GlbFaction? = factions.firstOrNull { it.id == id }
    fun dialog(id: Int): GlbDialogDef? = dialogs.firstOrNull { it.id == id }

    fun nextFactionId(): Int = nextId(factions.map { it.id })
    fun nextDialogId(): Int = nextId(dialogs.map { it.id })
    fun nextTransportId(): Int = nextId(transport.map { it.id })

    /** One section in the wire/`replaceSection` shape ([KEY_ENTRIES] wrapping a list); unknown sections stay empty. */
    fun sectionTag(section: Int): CompoundTag = CompoundTag().also { tag ->
        when (section) {
            SECTION_FACTIONS -> tag.put(KEY_ENTRIES, factionList())
            SECTION_DIALOGS -> tag.put(KEY_ENTRIES, dialogList())
            SECTION_LINKED -> tag.put(KEY_ENTRIES, linkedList())
            SECTION_TRANSPORT -> tag.put(KEY_ENTRIES, transportList())
        }
    }

    /** Replaces one section from a [sectionTag]-shaped compound. Returns false for an unknown section. */
    fun replaceSection(section: Int, tag: CompoundTag): Boolean {
        when (section) {
            SECTION_FACTIONS -> setFactions(readFactions(tag, KEY_ENTRIES))
            SECTION_DIALOGS -> setDialogs(readDialogs(tag, KEY_ENTRIES))
            SECTION_LINKED -> setLinked(readLinked(tag, KEY_ENTRIES))
            SECTION_TRANSPORT -> setTransport(readTransport(tag, KEY_ENTRIES))
            else -> return false
        }
        setDirty()
        return true
    }

    /** The whole block, as synced to clients and used as the S2C snapshot. */
    fun snapshot(): CompoundTag = write(CompoundTag())

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = write(tag)

    private fun write(tag: CompoundTag): CompoundTag {
        tag.put(KEY_FACTIONS, factionList())
        tag.put(KEY_DIALOGS, dialogList())
        tag.put(KEY_LINKED, linkedList())
        tag.put(KEY_TRANSPORT, transportList())
        return tag
    }

    fun load(tag: CompoundTag) {
        setFactions(readFactions(tag, KEY_FACTIONS))
        setDialogs(readDialogs(tag, KEY_DIALOGS))
        setLinked(readLinked(tag, KEY_LINKED))
        setTransport(readTransport(tag, KEY_TRANSPORT))
    }

    private fun factionList(): ListTag = ListTag().also { list -> factions.forEach { list.add(it.toTag()) } }
    private fun dialogList(): ListTag = ListTag().also { list -> dialogs.forEach { list.add(it.toTag()) } }
    private fun transportList(): ListTag = ListTag().also { list -> transport.forEach { list.add(it.toTag()) } }
    private fun linkedList(): ListTag = ListTag().also { list -> linkedNames.forEach { list.add(StringTag.valueOf(it)) } }

    private fun setFactions(input: List<GlbFaction>) {
        factions.clear()
        factions.addAll(coerceFactions(input))
    }

    private fun setDialogs(input: List<GlbDialogDef>) {
        dialogs.clear()
        dialogs.addAll(coerceDialogs(input))
    }

    private fun setLinked(input: List<String>) {
        linkedNames.clear()
        linkedNames.addAll(coerceLinked(input))
    }

    private fun setTransport(input: List<GlbTransportLocation>) {
        transport.clear()
        transport.addAll(coerceTransport(input))
    }

    private fun readFactions(container: CompoundTag, key: String): List<GlbFaction> {
        val list = container.getList(key, Tag.TAG_COMPOUND.toInt())
        return List(list.size) { index -> GlbFaction.fromTag(list.getCompound(index)) }
    }

    private fun readDialogs(container: CompoundTag, key: String): List<GlbDialogDef> {
        val list = container.getList(key, Tag.TAG_COMPOUND.toInt())
        return List(list.size) { index -> GlbDialogDef.fromTag(list.getCompound(index)) }
    }

    private fun readTransport(container: CompoundTag, key: String): List<GlbTransportLocation> {
        val list = container.getList(key, Tag.TAG_COMPOUND.toInt())
        return List(list.size) { index -> GlbTransportLocation.fromTag(list.getCompound(index)) }
    }

    private fun readLinked(container: CompoundTag, key: String): List<String> {
        val list = container.getList(key, Tag.TAG_STRING.toInt())
        return List(list.size) { index -> list.getString(index) }
    }

    /**
     * Trims/truncates entries, drops nameless ones, enforces the per-section cap, and re-allocates duplicate or
     * missing ids (first occurrence keeps its id) so every NPC's stored faction/dialog id stays unambiguous.
     */
    private fun coerceFactions(input: List<GlbFaction>): List<GlbFaction> {
        val used = HashSet<Int>()
        var next = 1
        val out = ArrayList<GlbFaction>()
        for (source in input) {
            if (out.size >= MAX_FACTIONS) break
            val name = source.name.trim().take(GlbFaction.MAX_NAME)
            if (name.isEmpty()) continue
            var id = source.id
            if (id <= 0 || !used.add(id)) {
                while (!used.add(next)) next++
                id = next
            }
            if (id >= next) next = id + 1
            out.add(GlbFaction(id, name, source.color and 0xFFFFFF))
        }
        return out
    }

    private fun coerceDialogs(input: List<GlbDialogDef>): List<GlbDialogDef> {
        val used = HashSet<Int>()
        var next = 1
        val out = ArrayList<GlbDialogDef>()
        for (source in input) {
            if (out.size >= MAX_DIALOGS) break
            val title = source.title.trim().take(GlbDialogDef.MAX_TITLE)
            if (title.isEmpty()) continue
            var id = source.id
            if (id <= 0 || !used.add(id)) {
                while (!used.add(next)) next++
                id = next
            }
            if (id >= next) next = id + 1
            val lines = source.lines
                .map { it.trim().take(GlbDialogDef.MAX_LINE_LENGTH) }
                .filter { it.isNotEmpty() }
                .take(GlbDialogDef.MAX_LINES)
                .toMutableList()
            out.add(GlbDialogDef(id, title, lines))
        }
        return out
    }

    private fun coerceLinked(input: List<String>): List<String> =
        input.map { it.trim().take(MAX_LINKED_NAME) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_LINKED)

    private fun coerceTransport(input: List<GlbTransportLocation>): List<GlbTransportLocation> {
        val used = HashSet<Int>()
        var next = 1
        val out = ArrayList<GlbTransportLocation>()
        for (source in input) {
            if (out.size >= MAX_TRANSPORT) break
            val name = source.name.trim().take(GlbTransportLocation.MAX_NAME)
            if (name.isEmpty()) continue
            var id = source.id
            if (id <= 0 || !used.add(id)) {
                while (!used.add(next)) next++
                id = next
            }
            if (id >= next) next = id + 1
            val dimension = ResourceLocation.tryParse(source.dimension)?.toString()
                ?: GlbTransportLocation.DEFAULT_DIMENSION
            out.add(
                GlbTransportLocation(
                    id = id,
                    name = name,
                    dimension = dimension,
                    x = clampCoord(source.x),
                    y = clampCoord(source.y),
                    z = clampCoord(source.z),
                ),
            )
        }
        return out
    }

    companion object {
        const val SECTION_FACTIONS = 0
        const val SECTION_DIALOGS = 1
        const val SECTION_LINKED = 2
        const val SECTION_TRANSPORT = 3
        const val SECTION_COUNT = 4

        const val MAX_FACTIONS = 64
        const val MAX_DIALOGS = 64
        const val MAX_LINKED = 64
        const val MAX_TRANSPORT = 128
        const val MAX_LINKED_NAME = 64

        private const val DATA_NAME = "glbrender_world"
        private const val KEY_ENTRIES = "Entries"
        private const val KEY_FACTIONS = "Factions"
        private const val KEY_DIALOGS = "Dialogs"
        private const val KEY_LINKED = "LinkedNames"
        private const val KEY_TRANSPORT = "Transport"

        private const val MAX_COORD = 30_000_000.0

        private val FACTORY: SavedData.Factory<GlbNpcWorldData> = SavedData.Factory(
            Supplier { GlbNpcWorldData() },
            BiFunction<CompoundTag, HolderLookup.Provider, GlbNpcWorldData> { tag, _ -> fromTag(tag) },
            null as DataFixTypes?,
        )

        /** The overworld's shared data instance; created on first access and saved with the world. */
        fun get(server: MinecraftServer): GlbNpcWorldData =
            server.overworld().dataStorage.computeIfAbsent(FACTORY, DATA_NAME)

        fun fromTag(tag: CompoundTag): GlbNpcWorldData = GlbNpcWorldData().also { it.load(tag) }

        private fun nextId(ids: List<Int>): Int = (ids.maxOrNull() ?: 0) + 1

        private fun clampCoord(value: Double): Double =
            if (value.isNaN()) 0.0 else value.coerceIn(-MAX_COORD, MAX_COORD)
    }
}
