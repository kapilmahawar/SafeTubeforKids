package tv.safetubeforkids.app.data.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The schema version an installation upgraded from W1b may still have on disk. */
const val CATALOG_LEGACY_SCHEMA_VERSION = 1

/**
 * Reads the version-1 catalog document a server that has not published since W2 may still have in
 * `catalog.json`, and turns it into the version-2 node tree.
 *
 * ### Why this exists, and what it is not
 *
 * It is **not** a second wire format. A version-1 `PUT /catalog` is refused with a 400: a client that
 * still speaks v1 must be told to update, not have its two-level document guessed into a tree it
 * never described. Reading the *stored* document is a different question - that document is this
 * server's own committed state - and refusing to read it would throw the parent's whole catalog away
 * on upgrade. The key invariant is that a previously committed catalog never disappears, so the
 * stored shape is upgraded once, deliberately, keeping its version number so no TV is asked to move
 * backwards.
 *
 * It reads the old document **field by field, with no legacy types**: the shape of a format nothing
 * may write any more lives in this one function, where it cannot be mistaken for part of the model.
 *
 * ### The rule, which is the same one `MIGRATION_7_8` uses
 *
 * - a `category` becomes a ROOT `CATEGORY` node;
 * - a `PLAYLIST` item becomes a `SUBCATEGORY` container carrying the playlist as its import source
 *   (a playlist is how videos arrive, never a child-facing tile);
 * - a `VIDEO` item becomes a `VIDEO` node.
 *
 * Sibling positions are renumbered to the canonical `0..n-1` in the order the document already
 * configured (`sortOrder ASC, id ASC`), which is what the tree requires and what a version-1 document
 * could not guarantee. Nothing is invented: unlike the TV's own translation, this one cannot
 * materialize the episodes a playlist imported, because the approved cache those come from lives on
 * the TV, not on the server.
 *
 * Timestamps stay 0: a version-1 document carried none, and inventing "now" on a read would make the
 * same stored document decode differently every time it is read.
 *
 * @return the upgraded snapshot, or `null` when [text] is not a complete, usable version-1 document -
 *   the same answer as any other unreadable stored document, so a hand-edited file is treated as
 *   corrupt rather than silently repaired.
 */
object CatalogLegacyDocument {

    fun upgrade(text: String): CatalogSnapshot? {
        val root = runCatching {
            CatalogJson.json.parseToJsonElement(text) as? JsonObject
        }.getOrNull() ?: return null

        if (root.int("schemaVersion", -1) != CATALOG_LEGACY_SCHEMA_VERSION) return null
        val categories = root["categories"] as? JsonArray ?: return null
        val catalogVersion = root.long("catalogVersion", 0L)

        val nodes = mutableListOf<CatalogNodeDto>()

        // Categories in the order the document configured, then renumbered 0..n-1.
        val ordered = categories.mapNotNull { it as? JsonObject }
            .sortedWith(compareBy({ it.int("sortOrder", 0) }, { it.string("id").orEmpty() }))

        if (ordered.size != categories.size) return null

        ordered.forEachIndexed { categoryPosition, category ->
            val categoryId = category.string("id")?.takeIf { it.isNotBlank() } ?: return null
            val categoryName = category.string("displayName")?.takeIf { it.isNotBlank() } ?: return null

            nodes += CatalogNodeDto(
                id = categoryId,
                parentId = null,
                nodeType = CATALOG_NODE_TYPE_CATEGORY,
                title = categoryName,
                position = categoryPosition,
                enabled = category.boolean("enabled", true),
            )

            val rawItems = category["items"] as? JsonArray
            val items = rawItems?.mapNotNull { it as? JsonObject } ?: emptyList()
            if (items.size != (rawItems?.size ?: 0)) return null

            val orderedItems = items.sortedWith(compareBy({ it.int("sortOrder", 0) }, { it.string("id").orEmpty() }))

            orderedItems.forEachIndexed { itemPosition, item ->
                val itemId = item.string("id")?.takeIf { it.isNotBlank() } ?: return null
                val itemName = item.string("displayName")?.takeIf { it.isNotBlank() } ?: return null
                val playlistId = item.string("youtubePlaylistId")
                val videoId = item.string("youtubeVideoId")

                val node = when (item.string("type")) {
                    LEGACY_TYPE_PLAYLIST -> {
                        // A playlist entry is a container, not a tile: it holds what it brought in.
                        if (playlistId.isNullOrBlank() || !videoId.isNullOrBlank()) return null
                        CatalogNodeDto(
                            id = itemId,
                            parentId = categoryId,
                            nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
                            title = itemName,
                            position = itemPosition,
                            enabled = item.boolean("enabled", true),
                            youtubePlaylistId = playlistId,
                        )
                    }

                    LEGACY_TYPE_VIDEO -> {
                        if (videoId.isNullOrBlank() || !playlistId.isNullOrBlank()) return null
                        CatalogNodeDto(
                            id = itemId,
                            parentId = categoryId,
                            nodeType = CATALOG_NODE_TYPE_VIDEO,
                            title = itemName,
                            position = itemPosition,
                            enabled = item.boolean("enabled", true),
                            youtubeVideoId = videoId,
                        )
                    }

                    else -> return null
                }

                nodes += node
            }
        }

        return CatalogSnapshot(
            schemaVersion = CATALOG_SCHEMA_VERSION,
            catalogVersion = catalogVersion,
            nodes = nodes,
        )
    }

    private const val LEGACY_TYPE_PLAYLIST = "PLAYLIST"
    private const val LEGACY_TYPE_VIDEO = "VIDEO"

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String, fallback: Int): Int =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: fallback

    private fun JsonObject.long(key: String, fallback: Long): Long =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: fallback

    private fun JsonObject.boolean(key: String, fallback: Boolean): Boolean =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: fallback
}
