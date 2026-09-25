package tv.safetubeforkids.app.data.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The wire contract between the parent-facing SafeTube server and the TV.
 *
 * This is deliberately not the Room shape: the TV's tables are an implementation detail, while this
 * is a versioned document a parent's browser, a future remote server and the TV all have to agree
 * on. Nothing here carries approval or permission information - the catalog says what the parent
 * *configured*, and playback authorization is decided separately by `PlaybackAuthorization`.
 *
 * ### Version 2: the recursive node tree
 *
 * Version 1 described two levels - a shelf and its entries - which cannot express what the tree in
 * `catalog_nodes` actually holds. Version 2 carries **the node tree itself**, in the shape
 * [CatalogNodeEntity] already has: every node names its `parentId` and its position among its
 * siblings, so the document is the same model the database stores rather than a second one that has
 * to be translated back and forth.
 *
 * ```
 * ROOT
 * |- CATEGORY            parentId: null
 * |  |- SUBCATEGORY      parentId: <category id>
 * |  |  |- VIDEO         parentId: <subcategory id>
 * |  |  \- VIDEO
 * |  |- VIDEO
 * |  \- VIDEO
 * \- CATEGORY
 * ```
 *
 * The node list is **flat, with explicit `parentId` links**, not nested `children` arrays. That is
 * the established model ([CatalogNodeEntity] has `parentId`, and the schema, the DAO, the ordering
 * service and the migration all speak it), and it is the only shape in which the failures the server
 * must refuse are *expressible*: a cycle, a child whose parent does not exist, two nodes sharing an
 * identifier. A nested document cannot represent any of those, so it would make half of the
 * validation unreachable and push the same checks into the parser, where the reason is lost.
 *
 * ### Shape choices, and why
 *
 * - `nodeType` and `thumbnailMode` are plain strings, not enums. An unknown value has to be reported
 *   as "unsupported node type 'CHANNEL'" (a 400 with a reason a parent can act on), whereas an enum
 *   would turn it into an unreadable-payload error and lose the reason.
 * - `nodes` has **no default**. An empty catalog is legitimate (a parent may delete every shelf), so
 *   `"nodes": []` is a valid document; but a *missing* `nodes` key must not be read as "the parent
 *   wants nothing", which is why it is required. `{}` is therefore a malformed payload, not an empty
 *   catalog. The same applies to `id`, `nodeType`, `title` and `position`: a node without them is
 *   not a node, and guessing them would be silently repairing malformed input.
 * - `catalogVersion` is on the snapshot the server serves, and is **absent** from
 *   [CatalogPutRequest]: the server owns version assignment, so a client cannot propose one.
 *   [CatalogPutRequest.expectedCatalogVersion] is the opposite thing - optimistic concurrency - and
 *   never becomes the stored version.
 * - `createdAt`/`updatedAt` are optional (absent reads as 0). They are the server's bookkeeping, so a
 *   client that omits them gets the server's clock rather than an error.
 */
const val CATALOG_SCHEMA_VERSION = 2

/** The kinds of node a v2 document can contain. Wire values are exactly these strings. */
const val CATALOG_NODE_TYPE_CATEGORY = "CATEGORY"
const val CATALOG_NODE_TYPE_SUBCATEGORY = "SUBCATEGORY"
const val CATALOG_NODE_TYPE_VIDEO = "VIDEO"

/** How a container gets its tile artwork. Wire values are exactly these strings. */
const val CATALOG_THUMBNAIL_MODE_AUTO = "AUTO"
const val CATALOG_THUMBNAIL_MODE_VIDEO = "VIDEO"
const val CATALOG_THUMBNAIL_MODE_CUSTOM = "CUSTOM"

/**
 * One node of the tree, on the wire.
 *
 * Field-for-field the shape of [CatalogNodeEntity], including its names, so reading and writing this
 * document is a mapping rather than a translation.
 */
@Serializable
data class CatalogNodeDto(
    val id: String,
    /** Null for a `CATEGORY` node at ROOT; every other node names its container. */
    val parentId: String? = null,
    val nodeType: String,
    val title: String,
    val position: Int,
    val enabled: Boolean = true,
    val youtubeVideoId: String? = null,
    /** The playlist a container imports, or the one an imported video came from. */
    val youtubePlaylistId: String? = null,
    val thumbnailMode: String = CATALOG_THUMBNAIL_MODE_AUTO,
    val thumbnailVideoId: String? = null,
    /** Reserved for the custom-thumbnail mode, which is not implemented; must be null for now. */
    val thumbnailUrl: String? = null,
    /** The server's clock. Absent on the wire reads as 0 and is stamped when the server stores it. */
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** The complete catalog as served by `GET /catalog` and stored by the server. */
@Serializable
data class CatalogSnapshot(
    val schemaVersion: Int,
    val catalogVersion: Long,
    val nodes: List<CatalogNodeDto>,
)

/**
 * The body of `PUT /catalog`.
 *
 * [expectedCatalogVersion] is optimistic concurrency, not version assignment: when present and
 * different from the stored version the server answers 409 instead of overwriting a change another
 * parent session made. The server always assigns the next version itself.
 */
@Serializable
data class CatalogPutRequest(
    val schemaVersion: Int,
    val expectedCatalogVersion: Long? = null,
    val nodes: List<CatalogNodeDto>,
)

/** A catalog with nothing configured yet: a legitimate state, at version 0. */
fun emptyCatalogSnapshot(): CatalogSnapshot =
    CatalogSnapshot(schemaVersion = CATALOG_SCHEMA_VERSION, catalogVersion = 0L, nodes = emptyList())

/**
 * The wire spelling of the tree's enums, in one place.
 *
 * The wire is strings so an unknown value can be *reported*, and these functions are the only place
 * that decides what a string means. `null` means "this build does not know that value"; nothing here
 * ever falls back to a default, because a node whose type was guessed is a node the parent did not
 * configure.
 */
object CatalogNodeWire {

    fun nodeTypeOf(wire: String): CatalogNodeType? = when (wire) {
        CATALOG_NODE_TYPE_CATEGORY -> CatalogNodeType.CATEGORY
        CATALOG_NODE_TYPE_SUBCATEGORY -> CatalogNodeType.SUBCATEGORY
        CATALOG_NODE_TYPE_VIDEO -> CatalogNodeType.VIDEO
        else -> null
    }

    fun thumbnailModeOf(wire: String): ThumbnailMode? = when (wire) {
        CATALOG_THUMBNAIL_MODE_AUTO -> ThumbnailMode.AUTO
        CATALOG_THUMBNAIL_MODE_VIDEO -> ThumbnailMode.VIDEO
        CATALOG_THUMBNAIL_MODE_CUSTOM -> ThumbnailMode.CUSTOM
        else -> null
    }

    fun wireNameOf(type: CatalogNodeType): String = type.name

    fun wireNameOf(mode: ThumbnailMode): String = mode.name

    /** What a v2 document says about a node this build stores. */
    fun toDto(node: CatalogNodeEntity): CatalogNodeDto = CatalogNodeDto(
        id = node.id,
        parentId = node.parentId,
        nodeType = wireNameOf(node.nodeType),
        title = node.title,
        position = node.position,
        enabled = node.enabled,
        youtubeVideoId = node.youtubeVideoId,
        youtubePlaylistId = node.youtubePlaylistId,
        thumbnailMode = wireNameOf(node.thumbnailMode),
        thumbnailVideoId = node.thumbnailVideoId,
        thumbnailUrl = node.thumbnailUrl,
        createdAt = node.createdAt,
        updatedAt = node.updatedAt,
    )
}

/**
 * One serializer for both ends of the wire.
 *
 * The server and the sync client must not each bring their own `Json` configuration: a field the
 * server omits because it looks like a default is a field the client then fails to parse.
 * `explicitNulls` matters here - `"youtubeVideoId": null` has to be on the wire so a container is
 * visibly not a video, and `"parentId": null` so a shelf is visibly at ROOT rather than merely
 * missing its parent.
 */
object CatalogJson {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun encode(snapshot: CatalogSnapshot): String = json.encodeToString(snapshot)

    fun encodePutRequest(request: CatalogPutRequest): String = json.encodeToString(request)

    /** Null when the text is not a readable catalog document at all. */
    fun decodeSnapshot(text: String): CatalogSnapshot? = try {
        json.decodeFromString<CatalogSnapshot>(text)
    } catch (e: Exception) {
        null
    }

    fun decodePutRequest(text: String): CatalogPutRequest? = try {
        json.decodeFromString<CatalogPutRequest>(text)
    } catch (e: Exception) {
        null
    }

    /**
     * The `schemaVersion` a body declares, read on its own, or null when the body does not declare an
     * integer one at all.
     *
     * This exists so a client speaking an older contract can be told *that*, rather than being told
     * its document is unreadable: a version-1 body has no `nodes` key, so decoding it as version 2
     * fails on a missing field and the real reason - "this server speaks 2" - would never be said. The
     * answer is still a refusal either way; the difference is whether a parent (or a future client)
     * can act on it.
     */
    fun declaredSchemaVersion(text: String): Int? = try {
        val root = json.parseToJsonElement(text) as? JsonObject
        (root?.get("schemaVersion") as? JsonPrimitive)?.content?.toIntOrNull()
    } catch (e: Exception) {
        null
    }

    /**
     * Reads the document the **server has on disk**, which may still be the version-1 one written by
     * an earlier build.
     *
     * This is not a second wire format: a version-1 `PUT` is refused with a 400 (see
     * [CatalogRoutes]), because silently reading one as version 2 would invent a tree the parent
     * never configured. A *stored* document is a different thing - it is this server's own committed
     * state, and refusing to read it would throw away the parent's catalog on upgrade, which is
     * exactly what must never happen. So the old shape is upgraded once, deliberately, and the same
     * version number is kept so no TV is asked to move backwards.
     */
    fun decodeStoredDocument(text: String): StoredDocument =
        decodeSnapshot(text)?.let { StoredDocument.Version2(it) }
            ?: CatalogLegacyDocument.upgrade(text)?.let { StoredDocument.UpgradedFromVersion1(it) }
            ?: StoredDocument.Unreadable

    /** What a stored catalog document turned out to be. */
    sealed class StoredDocument {
        data class Version2(val snapshot: CatalogSnapshot) : StoredDocument()
        data class UpgradedFromVersion1(val snapshot: CatalogSnapshot) : StoredDocument()
        data object Unreadable : StoredDocument()
    }
}
