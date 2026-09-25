package tv.safetubeforkids.app.data.catalog

/**
 * Turns a validated version-2 payload into the nodes the TV stores.
 *
 * Only ever called on a payload [CatalogPayloadValidator] has accepted: the entity constructor
 * itself refuses a malformed node, so mapping first and validating later would turn one bad node
 * into an exception in the middle of a replacement. The `error(...)` calls below are therefore
 * unreachable from a validated document - they exist so that a future caller who skips validation
 * gets a loud failure instead of a node with a guessed type.
 *
 * This is where the two names stay apart. `title` is copied verbatim from the payload - the parent's
 * chosen name for the child - and is never derived from, or compared against, the YouTube identifier
 * beside it.
 *
 * Timestamps: the server's values are carried through when it sent them, and the synchronization
 * time is used when it did not. What is *stored* is decided by the tree comparison in
 * [CatalogNodeRepository]: an unchanged node is not rewritten at all (so it keeps its own
 * `created_at`), which is what keeps resume history attached to the same item across syncs.
 */
object CatalogMapper {

    fun toNodes(nodes: List<CatalogNodeDto>, syncedAt: Long): List<CatalogNodeEntity> =
        nodes.map { dto ->
            val type = CatalogNodeWire.nodeTypeOf(dto.nodeType)
                ?: error("validated catalog contained unsupported node type '${dto.nodeType}'")
            val thumbnailMode = CatalogNodeWire.thumbnailModeOf(dto.thumbnailMode)
                ?: error("validated catalog contained unsupported thumbnail mode '${dto.thumbnailMode}'")

            CatalogNodeEntity(
                id = dto.id,
                parentId = dto.parentId,
                nodeType = type,
                title = dto.title,
                position = dto.position,
                enabled = dto.enabled,
                youtubeVideoId = dto.youtubeVideoId,
                youtubePlaylistId = dto.youtubePlaylistId,
                thumbnailMode = thumbnailMode,
                thumbnailVideoId = dto.thumbnailVideoId,
                thumbnailUrl = dto.thumbnailUrl,
                createdAt = dto.createdAt.takeIf { it > 0L } ?: syncedAt,
                updatedAt = dto.updatedAt.takeIf { it > 0L } ?: syncedAt,
            )
        }
}
