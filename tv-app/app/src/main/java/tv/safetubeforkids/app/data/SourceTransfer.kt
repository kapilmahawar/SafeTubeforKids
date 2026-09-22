package tv.safetubeforkids.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One approved source, in the shape the dashboard exports and imports. */
@Serializable
data class ExportedSource(
    val sourceType: String,
    val sourceId: String,
    val sourceUrl: String,
    val displayName: String = "",
    val videoCount: Int = 0,
)

@Serializable
data class SourceExport(
    val version: Int = 1,
    val exportedAt: Long = 0,
    val sources: List<ExportedSource> = emptyList(),
)

@Serializable
data class ImportFailure(val source: String, val reason: String)

@Serializable
data class ImportSummary(
    val added: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failed: List<ImportFailure> = emptyList(),
)

/**
 * Export/import of the approved library as JSON, so a parent can back it up or move a setup
 * between TVs without re-adding every video by hand.
 *
 * Kept free of database and HTTP concerns so the format and its validation are unit-testable.
 */
object SourceTransfer {

    /** Source types this app knows how to resolve. Anything else is refused on import. */
    val KNOWN_TYPES = setOf("yt_playlist", "yt_video", "yt_channel")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun export(sources: List<ExportedSource>, now: Long): String =
        json.encodeToString(SourceExport(version = 1, exportedAt = now, sources = sources))

    /** Accepts a full export payload or a bare array of sources. Null when unreadable. */
    fun parse(payload: String): List<ExportedSource>? {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.startsWith("[")) {
                json.decodeFromString<List<ExportedSource>>(trimmed)
            } else {
                json.decodeFromString<SourceExport>(trimmed).sources
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Splits a parsed payload into the entries worth importing and the ones to report back. */
    fun validate(sources: List<ExportedSource>): Pair<List<ExportedSource>, List<ImportFailure>> {
        val usable = mutableListOf<ExportedSource>()
        val failures = mutableListOf<ImportFailure>()

        sources.forEach { source ->
            val label = source.sourceId.ifBlank { source.sourceUrl }
            when {
                source.sourceId.isBlank() ->
                    failures += ImportFailure(label, "missing source id")

                source.sourceUrl.isBlank() ->
                    failures += ImportFailure(label, "missing source url")

                source.sourceType !in KNOWN_TYPES ->
                    failures += ImportFailure(label, "unsupported source type '${source.sourceType}'")

                else -> usable += source
            }
        }
        return usable to failures
    }

    /** Preserves order while dropping duplicates that appear inside the payload itself. */
    fun distinctBySourceId(sources: List<ExportedSource>): List<ExportedSource> {
        val seen = mutableSetOf<String>()
        return sources.filter { seen.add(it.sourceId) }
    }
}
