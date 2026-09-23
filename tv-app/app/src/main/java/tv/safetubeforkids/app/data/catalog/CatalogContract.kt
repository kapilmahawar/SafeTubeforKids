package tv.safetubeforkids.app.data.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire contract between the parent-facing SafeTube server and the TV.
 *
 * This is deliberately not the Room shape: the TV's tables are an implementation detail, while this
 * is a versioned document a parent's browser, a future remote server and the TV all have to agree
 * on. Nothing here carries approval or permission information - the catalog says what the parent
 * *configured*, and playback authorization is decided separately by `PlaybackAuthorization`.
 *
 * ### Shape choices, and why
 *
 * - Items are **nested inside their category**. That makes "an item that references a category that
 *   does not exist" unrepresentable rather than merely checked, so it can never reach the TV.
 * - `type` is a plain string, not an enum. An unknown value has to be reported as
 *   "unsupported type 'CHANNEL'" (a 400 with a reason a parent can act on), whereas an enum would
 *   turn it into an unreadable-payload error and lose the reason.
 * - `categories` and `items` have **no default**. A category with no items is legitimate (the
 *   `Stories` shelf is configured empty), so `items` defaults to an empty list; but a *missing*
 *   `categories` key must not be read as "the parent wants nothing", which is why it is required.
 *   `{}` is therefore a malformed payload, not an empty catalog.
 * - `catalogVersion` is on the snapshot the server serves, and is **absent** from
 *   [CatalogPutRequest]: the server owns version assignment, so a client cannot propose one.
 */
const val CATALOG_SCHEMA_VERSION = 1

/** What a catalog entry points at. Wire values are exactly these strings. */
const val CATALOG_TYPE_PLAYLIST = "PLAYLIST"
const val CATALOG_TYPE_VIDEO = "VIDEO"

/** One content item: a whole YouTube playlist, or one individual video. */
@Serializable
data class CatalogItemDto(
    val id: String,
    val type: String,
    val displayName: String,
    val sortOrder: Int,
    val youtubePlaylistId: String? = null,
    val youtubeVideoId: String? = null,
    val enabled: Boolean = true,
)

/** One parent-defined shelf, with its entries in the parent's order. */
@Serializable
data class CatalogCategoryDto(
    val id: String,
    val displayName: String,
    val sortOrder: Int,
    val enabled: Boolean = true,
    val items: List<CatalogItemDto> = emptyList(),
)

/** The complete catalog as served by `GET /catalog` and stored by the server. */
@Serializable
data class CatalogSnapshot(
    val schemaVersion: Int,
    val catalogVersion: Long,
    val categories: List<CatalogCategoryDto>,
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
    val categories: List<CatalogCategoryDto>,
)

/** A catalog with nothing configured yet: a legitimate state, at version 0. */
fun emptyCatalogSnapshot(): CatalogSnapshot =
    CatalogSnapshot(schemaVersion = CATALOG_SCHEMA_VERSION, catalogVersion = 0L, categories = emptyList())

/**
 * One serializer for both ends of the wire.
 *
 * The server and the sync client must not each bring their own `Json` configuration: a field the
 * server omits because it looks like a default is a field the client then fails to parse.
 * `explicitNulls` matters here - `"youtubeVideoId": null` has to be on the wire so a playlist item
 * is visibly not a video item.
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
}
