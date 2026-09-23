package tv.safetubeforkids.app.data.catalog

import tv.safetubeforkids.app.util.ContentSourceParser
import tv.safetubeforkids.app.util.ParseResult
import tv.safetubeforkids.app.util.SourceType

/**
 * Everything a catalog payload must satisfy before anyone acts on it.
 *
 * The same validator runs on both sides of the wire on purpose:
 *
 * - the **server** runs it before storing a `PUT`, so a bad configuration never becomes the
 *   published catalog;
 * - the **TV** runs it again after downloading, because the server's answer is untrusted input and
 *   a single bad item must not be allowed to half-replace the local catalog.
 *
 * Validating is separate from writing: [CatalogSyncService] validates the *whole* payload first and
 * only then maps and replaces, so "invalid" and "partially applied" cannot happen together.
 *
 * ### Ordering
 * `sortOrder` accepts any `Int`, including negative and duplicated values. Phase 2 decided that
 * duplicate sort orders are legal and made the local order deterministic with `sortOrder ASC, id
 * ASC`; rejecting duplicates here would change that decision by the back door. A negative value is
 * simply a way for a parent to push a shelf to the top.
 *
 * ### Referential integrity
 * Items are nested inside their category, so an item naming a category that does not exist cannot
 * be expressed at all. The one referential hazard the nesting does *not* prevent is two categories
 * claiming the same item id, which would silently overwrite a row locally (`content_items.id` is the
 * primary key), so that is checked across the whole payload.
 *
 * ### YouTube identifiers
 * Structural validation is delegated to [ContentSourceParser], the parser that already guards the
 * dashboard's "add source" flow: the id is turned into the canonical URL and must come back out
 * unchanged, which rejects a blank id, a stray `&`, a channel handle used as a video, and an
 * auto-generated `RD…`/`UU…` list. No length or character assumptions are added beyond that, so no
 * valid id can be refused for being unusual.
 */
object CatalogPayloadValidator {

    /** One reason a payload was refused, addressed to the part of the document that caused it. */
    data class Problem(val location: String, val reason: String) {
        override fun toString(): String = "$location: $reason"
    }

    sealed class Outcome {
        /** The whole payload is usable. */
        data class Valid(val categories: List<CatalogCategoryDto>) : Outcome()

        /** Nothing may be written. */
        data class Invalid(val problems: List<Problem>) : Outcome()
    }

    /**
     * An intentionally empty catalog is valid: a parent may configure zero shelves.
     * A *malformed* payload is not - that distinction lives in [CatalogJson], because a missing
     * `categories` key fails to parse instead of decoding to an empty list.
     */
    fun validate(categories: List<CatalogCategoryDto>): Outcome {
        val problems = mutableListOf<Problem>()

        val categoryIds = mutableSetOf<String>()
        val itemIds = mutableSetOf<String>()

        categories.forEachIndexed { categoryIndex, category ->
            val where = "categories[$categoryIndex]"

            if (category.id.isBlank()) {
                problems += Problem("$where.id", "category id must not be blank")
            } else if (!categoryIds.add(category.id)) {
                problems += Problem("$where.id", "duplicate category id '${category.id}'")
            }
            if (category.displayName.isBlank()) {
                problems += Problem("$where.displayName", "category display name must not be blank")
            }

            category.items.forEachIndexed { itemIndex, item ->
                val itemWhere = "$where.items[$itemIndex]"
                validateItem(item, itemWhere, problems, itemIds)
            }
        }

        return if (problems.isEmpty()) Outcome.Valid(categories) else Outcome.Invalid(problems)
    }

    private fun validateItem(
        item: CatalogItemDto,
        where: String,
        problems: MutableList<Problem>,
        itemIds: MutableSet<String>,
    ) {
        if (item.id.isBlank()) {
            problems += Problem("$where.id", "item id must not be blank")
        } else if (!itemIds.add(item.id)) {
            problems += Problem("$where.id", "duplicate item id '${item.id}'")
        }
        if (item.displayName.isBlank()) {
            problems += Problem("$where.displayName", "item display name must not be blank")
        }

        when (item.type) {
            CATALOG_TYPE_PLAYLIST -> {
                if (item.youtubePlaylistId.isNullOrBlank()) {
                    problems += Problem(
                        "$where.youtubePlaylistId",
                        "a PLAYLIST item requires a youtubePlaylistId",
                    )
                } else {
                    youtubeIdProblem(item.youtubePlaylistId, SourceType.YT_PLAYLIST)?.let {
                        problems += Problem("$where.youtubePlaylistId", it)
                    }
                }
                if (item.youtubeVideoId != null) {
                    problems += Problem(
                        "$where.youtubeVideoId",
                        "a PLAYLIST item must not carry a youtubeVideoId",
                    )
                }
            }

            CATALOG_TYPE_VIDEO -> {
                if (item.youtubeVideoId.isNullOrBlank()) {
                    problems += Problem(
                        "$where.youtubeVideoId",
                        "a VIDEO item requires a youtubeVideoId",
                    )
                } else {
                    youtubeIdProblem(item.youtubeVideoId, SourceType.YT_VIDEO)?.let {
                        problems += Problem("$where.youtubeVideoId", it)
                    }
                }
                if (item.youtubePlaylistId != null) {
                    problems += Problem(
                        "$where.youtubePlaylistId",
                        "a VIDEO item must not carry a youtubePlaylistId",
                    )
                }
            }

            else -> problems += Problem(
                "$where.type",
                "unsupported item type '${item.type}' (expected $CATALOG_TYPE_PLAYLIST or $CATALOG_TYPE_VIDEO)",
            )
        }
    }

    /**
     * Null when [id] is a usable identifier of [expected] type.
     *
     * The canonical URL is rebuilt and re-parsed, and the id must survive the round trip unchanged;
     * anything the existing parser would refuse on the dashboard is refused here too, with the same
     * wording, and an id that only *looks* like one (an extra `&`, a handle, an `RD…` mix) cannot
     * slip through by being embedded in a URL.
     */
    private fun youtubeIdProblem(id: String, expected: SourceType): String? {
        val url = when (expected) {
            SourceType.YT_PLAYLIST -> "https://www.youtube.com/playlist?list=$id"
            else -> "https://www.youtube.com/watch?v=$id"
        }
        return when (val parsed = ContentSourceParser.parse(url)) {
            is ParseResult.Rejected -> parsed.message
            is ParseResult.Success ->
                if (parsed.source.type == expected && parsed.source.id == id) {
                    null
                } else {
                    "not a usable ${expected.name.lowercase().removePrefix("yt_")} id"
                }
        }
    }

    /** Renders problems for a log line or an HTTP error body without leaking anything secret. */
    fun describe(problems: List<Problem>, limit: Int = 5): String =
        problems.take(limit).joinToString("; ") { it.toString() } +
            if (problems.size > limit) " (+${problems.size - limit} more)" else ""
}
