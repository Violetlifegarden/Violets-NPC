package top.fifthlight.blazerod.model.loader

import java.nio.ByteBuffer
import java.nio.file.Path

interface ModelFileLoader {
    enum class Ability {
        MODEL,
        EMBED_ANIMATION,
        EXTERNAL_ANIMATION,
        EMBED_THUMBNAIL,
        METADATA,
    }

    fun initialize() = Unit
    val available: Boolean
        get() = true

    val extensions: Map<String, Set<Ability>>
        get() = mapOf()
    val markerFiles: Map<String, Set<Ability>>
        get() = mapOf()
    val abilities: Set<Ability>
        get() = extensions.values.flatten().toSet()

    val probeLength: Int?
    fun probe(buffer: ByteBuffer): Boolean

    fun load(
        path: Path,
        basePath: Path = path.parent,
        param: LoadParam = LoadParam(),
    ): LoadResult = load(
        path = path,
        context = LoadContext.File(basePath),
        param = param,
    )

    fun load(
        path: Path,
        context: LoadContext,
        param: LoadParam = LoadParam(),
    ): LoadResult

    fun getThumbnail(path: Path, context: LoadContext): ThumbnailResult = ThumbnailResult.Unsupported

    fun getMarkerFileHashes(marker: Path, directory: Path): Set<Path> =
        setOf(marker)

    fun getMetadata(path: Path, context: LoadContext): MetadataResult = MetadataResult.Unsupported

}
