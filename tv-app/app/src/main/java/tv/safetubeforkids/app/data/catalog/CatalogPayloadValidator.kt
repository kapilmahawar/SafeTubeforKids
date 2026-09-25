package tv.safetubeforkids.app.data.catalog

import tv.safetubeforkids.app.util.ContentSourceParser

/**
 * Everything a version-2 catalog payload must satisfy before anyone acts on it.
 *
 * The same validator runs on both sides of the wire on purpose:
 *
 * - the **server** runs it before storing a `PUT`, so a bad tree never becomes the published
 *   catalog;
 * - the **TV** runs it again after downloading, because the server's answer is untrusted input and
 *   one bad node must not be allowed to half-replace the local catalog.
 *
 * Validating is separate from writing: [CatalogSyncService] validates the *whole* document first and
 * only then replaces, so "invalid" and "partially applied" cannot happen together.
 *
 * ### One model, not two
 *
 * The per-node rules are not restated here - they are [CatalogNodeValidation.problemWith], the same
 * function [CatalogNodeEntity] refuses to be constructed without. A node the server accepts is
 * therefore a node the TV's own entity type accepts, and the two ends cannot drift. What this object
 * adds is everything that is a property of the *tree* rather than of one node: unique identifiers,
 * parents that exist, no cycles, which node type may sit where, and sibling numbering.
 *
 * ### The rules the tree adds
 *
 * - **unique ids** across the whole document - a duplicate would make one node overwrite another,
 *   and would make "the node with this id" ambiguous;
 * - **`parentId` names a node in the same document**, or is null for a shelf at ROOT;
 * - **no cycles** (including a node parented to itself) - the tree is a tree, and a cycle would make
 *   rendering it an infinite walk;
 * - **node type fits its parent**: only a `CATEGORY` sits at ROOT, a `CATEGORY` holds containers and
 *   videos, a `SUBCATEGORY` holds videos, and a `VIDEO` holds nothing;
 * - **sibling positions are exactly `0..n-1`**. This also settles ordering: with unique positions and
 *   unique ids, `position ASC, id ASC` is a total order and no two children can tie, so the order a
 *   parent configured is the order every reader renders - there is nothing left for the server to
 *   accept and the TV to interpret differently.
 *
 * ### Nothing is repaired
 *
 * A malformed tree is refused whole. No node is dropped, no position is filled in, no missing parent
 * is invented: those would all be changes the parent did not ask for, and the caller could no longer
 * tell "stored as sent" from "stored as understood".
 */
object CatalogPayloadValidator {

    /** One reason a payload was refused, addressed to the part of the document that caused it. */
    data class Problem(val location: String, val reason: String) {
        override fun toString(): String = "$location: $reason"
    }

    sealed class Outcome {
        /** The whole payload is usable. */
        data class Valid(val nodes: List<CatalogNodeDto>) : Outcome()

        /** Nothing may be written. */
        data class Invalid(val problems: List<Problem>) : Outcome()
    }

    /**
     * An intentionally empty catalog is valid: a parent may delete every shelf, and the server
     * records that as a new version like any other change. A *malformed* payload is not - that
     * distinction lives in [CatalogJson], because a missing `nodes` key fails to parse instead of
     * decoding to an empty list.
     */
    fun validate(nodes: List<CatalogNodeDto>): Outcome {
        val problems = mutableListOf<Problem>()

        // The typed view, for the tree rules. A node whose type this build does not know has no place
        // in the tree, so it is reported and left out of them rather than guessed at.
        val typed = mutableListOf<Triple<Int, CatalogNodeDto, CatalogNodeType>>()
        val byId = mutableMapOf<String, CatalogNodeDto>()
        val typeById = mutableMapOf<String, CatalogNodeType>()

        nodes.forEachIndexed { index, node ->
            val where = "nodes[$index]"

            nodeTypeProblem(node, where)?.let { problems += it }
            validationProblem(node, where)?.let { problems += it }
            youtubeProblems(node, where, problems)
            thumbnailProblems(node, where, problems)
            timestampProblems(node, where, problems)

            if (node.id.isNotBlank()) {
                if (byId.put(node.id, node) != null) {
                    problems += Problem("$where.id", "duplicate node id '${node.id}'")
                }
            }

            val type = CatalogNodeWire.nodeTypeOf(node.nodeType)
            if (type != null) {
                typed += Triple(index, node, type)
                if (node.id.isNotBlank()) typeById[node.id] = type
            }
        }

        // Parent existence, the type each parent may hold, and cycles.
        typed.forEach { (index, node, type) ->
            val where = "nodes[$index]"
            val parentId = node.parentId

            if (parentId == null) {
                if (type != CatalogNodeType.CATEGORY) {
                    problems += Problem(
                        "$where.parentId",
                        "only a CATEGORY node may sit at ROOT; a ${node.nodeType} node needs a parent",
                    )
                }
                return@forEach
            }

            val parentType = typeById[parentId]
            if (parentType == null) {
                val reason = if (byId.containsKey(parentId)) {
                    "parent '$parentId' is not a node type this build understands"
                } else {
                    "parent '$parentId' does not exist in this catalog"
                }
                problems += Problem("$where.parentId", reason)
                return@forEach
            }

            if (!CatalogNodeValidation.childTypeAllowedIn(parentType).contains(type)) {
                problems += Problem(
                    "$where.nodeType",
                    "a $parentType node cannot hold a ${node.nodeType} node",
                )
            }

            cycleProblem(node, index, byId)?.let { problems += it }
        }

        siblingProblems(nodes, problems)

        return if (problems.isEmpty()) Outcome.Valid(nodes) else Outcome.Invalid(problems)
    }

    /** An unknown `nodeType` is named, because that is the only actionable thing to say about it. */
    private fun nodeTypeProblem(node: CatalogNodeDto, where: String): Problem? =
        if (CatalogNodeWire.nodeTypeOf(node.nodeType) == null) {
            Problem(
                "$where.nodeType",
                "unsupported node type '${node.nodeType}' (expected $CATALOG_NODE_TYPE_CATEGORY, " +
                    "$CATALOG_NODE_TYPE_SUBCATEGORY or $CATALOG_NODE_TYPE_VIDEO)",
            )
        } else {
            null
        }

    /**
     * The rules of the node model itself, asked of values rather than of an entity - a node this
     * refuses is one [CatalogNodeEntity] would refuse to be constructed from.
     */
    private fun validationProblem(node: CatalogNodeDto, where: String): Problem? {
        val type = CatalogNodeWire.nodeTypeOf(node.nodeType) ?: return null
        val reason = CatalogNodeValidation.problemWith(
            id = node.id,
            parentId = node.parentId,
            nodeType = type,
            title = node.title,
            position = node.position,
            youtubeVideoId = node.youtubeVideoId,
            youtubePlaylistId = node.youtubePlaylistId,
        ) ?: return null
        return Problem(where, reason)
    }

    /**
     * Structural validation of the YouTube identifiers is delegated to [ContentSourceParser], the
     * parser that already guards the dashboard's "add source" flow and, through
     * [ContentSourceParser.videoIdProblem], the playlist resolver too: an id is turned into the
     * canonical URL and must come back out unchanged. One rule, three callers - so an id the resolver
     * accepts cannot be one the catalog then refuses, and the other way round.
     */
    private fun youtubeProblems(node: CatalogNodeDto, where: String, problems: MutableList<Problem>) {
        node.youtubeVideoId?.let { id ->
            when {
                id.isBlank() -> problems += Problem("$where.youtubeVideoId", "a youtubeVideoId must not be blank")
                else -> ContentSourceParser.videoIdProblem(id)?.let {
                    problems += Problem("$where.youtubeVideoId", it)
                }
            }
        }
        node.youtubePlaylistId?.let { id ->
            when {
                id.isBlank() -> problems += Problem(
                    "$where.youtubePlaylistId",
                    "a youtubePlaylistId must not be blank; leave it out for a container with no import",
                )

                else -> ContentSourceParser.playlistIdProblem(id)?.let {
                    problems += Problem("$where.youtubePlaylistId", it)
                }
            }
        }
    }

    /**
     * Thumbnails: `AUTO` needs nothing, `VIDEO` needs the video it takes its picture from, and
     * `CUSTOM` is refused because it is reserved rather than implemented - storing it would let a
     * parent configure something no screen can render. A URL is refused for the same reason.
     */
    private fun thumbnailProblems(node: CatalogNodeDto, where: String, problems: MutableList<Problem>) {
        val mode = CatalogNodeWire.thumbnailModeOf(node.thumbnailMode)
        if (mode == null) {
            problems += Problem(
                "$where.thumbnailMode",
                "unsupported thumbnail mode '${node.thumbnailMode}' (expected " +
                    "$CATALOG_THUMBNAIL_MODE_AUTO or $CATALOG_THUMBNAIL_MODE_VIDEO)",
            )
            return
        }

        when (mode) {
            ThumbnailMode.AUTO -> if (!node.thumbnailVideoId.isNullOrBlank()) {
                problems += Problem(
                    "$where.thumbnailVideoId",
                    "an AUTO thumbnail takes the first video's picture; it must not name one",
                )
            }

            ThumbnailMode.VIDEO -> if (node.thumbnailVideoId.isNullOrBlank()) {
                problems += Problem(
                    "$where.thumbnailVideoId",
                    "a VIDEO thumbnail requires the youtubeVideoId to take its picture from",
                )
            }

            ThumbnailMode.CUSTOM -> problems += Problem(
                "$where.thumbnailMode",
                "custom thumbnails are not implemented yet",
            )
        }

        if (node.thumbnailUrl != null) {
            problems += Problem("$where.thumbnailUrl", "thumbnail URLs are not supported yet")
        }
    }

    private fun timestampProblems(node: CatalogNodeDto, where: String, problems: MutableList<Problem>) {
        if (node.createdAt < 0L) problems += Problem("$where.createdAt", "createdAt must not be negative")
        if (node.updatedAt < 0L) problems += Problem("$where.updatedAt", "updatedAt must not be negative")
    }

    /**
     * A node that is its own ancestor. Walking up terminates either at ROOT or at a repeat, so the
     * walk is bounded by the number of nodes - which is also why a cycle is reported on the node the
     * walk started from rather than on an arbitrary member of it.
     */
    private fun cycleProblem(
        node: CatalogNodeDto,
        index: Int,
        byId: Map<String, CatalogNodeDto>,
    ): Problem? {
        val seen = mutableSetOf(node.id)
        var current = node.parentId?.let { byId[it] }
        while (current != null) {
            if (!seen.add(current.id)) {
                return Problem(
                    "nodes[$index].parentId",
                    "'${node.id}' is inside a cycle of parents (${seen.joinToString(" -> ")})",
                )
            }
            current = current.parentId?.let { byId[it] }
        }
        return null
    }

    /**
     * For every parent, its children are numbered `0..n-1`.
     *
     * A gap is refused as well as a duplicate: `position` is the only ordering rule the tree has, and
     * a sparse list means the same configured order could be written two different ways. Duplicates
     * are refused because two children sharing a position would leave the rendered order to whatever
     * the reader happened to do with the tie.
     */
    private fun siblingProblems(nodes: List<CatalogNodeDto>, problems: MutableList<Problem>) {
        nodes.filter { it.id.isNotBlank() }
            .groupBy { it.parentId }
            .toSortedMap(compareBy { it ?: "" })
            .forEach { (parentId, children) ->
                val positions = children.map { it.position }.sorted()
                val expected = children.indices.toList()
                if (positions != expected) {
                    val where = parentId?.let { "children of '$it'" } ?: "the shelves at ROOT"
                    problems += Problem(
                        where,
                        "positions must be 0..${children.size - 1}, found ${positions.joinToString(",")}",
                    )
                }
            }
    }


    /** Renders problems for a log line or an HTTP error body without leaking anything secret. */
    fun describe(problems: List<Problem>, limit: Int = 5): String =
        problems.take(limit).joinToString("; ") { it.toString() } +
            if (problems.size > limit) " (+${problems.size - limit} more)" else ""
}
