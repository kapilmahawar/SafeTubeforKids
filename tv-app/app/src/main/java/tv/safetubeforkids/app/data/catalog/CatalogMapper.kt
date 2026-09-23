package tv.safetubeforkids.app.data.catalog

/**
 * Turns a validated wire payload into the Room rows the TV reads.
 *
 * Only ever called on a payload [CatalogPayloadValidator] has accepted: the entity constructor
 * itself refuses a malformed playlist/video identity, so mapping first and validating later would
 * turn one bad item into an exception in the middle of a replacement.
 *
 * This is where the two names stay apart. `displayName` is copied verbatim from the payload - the
 * parent's chosen name for the child - and is never derived from, or compared against, the YouTube
 * identifier beside it.
 *
 * `createdAt`/`updatedAt` are stamped with the synchronization time rather than carried on the wire:
 * they describe when *this* local row was written, and the contract deliberately does not expose
 * local row bookkeeping.
 */
object CatalogMapper {

    data class Mapped(
        val categories: List<CategoryEntity>,
        val items: List<ContentItemEntity>,
    )

    /** Null for a wire value this build does not understand. */
    fun contentItemTypeOf(wireType: String): ContentItemType? = when (wireType) {
        CATALOG_TYPE_PLAYLIST -> ContentItemType.PLAYLIST
        CATALOG_TYPE_VIDEO -> ContentItemType.VIDEO
        else -> null
    }

    fun toEntities(categories: List<CatalogCategoryDto>, syncedAt: Long): Mapped {
        val categoryEntities = mutableListOf<CategoryEntity>()
        val itemEntities = mutableListOf<ContentItemEntity>()

        categories.forEach { category ->
            categoryEntities += CategoryEntity(
                id = category.id,
                displayName = category.displayName,
                sortOrder = category.sortOrder,
                enabled = category.enabled,
                createdAt = syncedAt,
                updatedAt = syncedAt,
            )

            category.items.forEach { item ->
                val type = contentItemTypeOf(item.type)
                    ?: error("validated catalog contained unsupported type '${item.type}'")
                itemEntities += ContentItemEntity(
                    id = item.id,
                    categoryId = category.id,
                    type = type,
                    displayName = item.displayName,
                    sortOrder = item.sortOrder,
                    youtubePlaylistId = item.youtubePlaylistId.takeIf { type == ContentItemType.PLAYLIST },
                    youtubeVideoId = item.youtubeVideoId.takeIf { type == ContentItemType.VIDEO },
                    enabled = item.enabled,
                    createdAt = syncedAt,
                    updatedAt = syncedAt,
                )
            }
        }

        return Mapped(categories = categoryEntities, items = itemEntities)
    }
}
