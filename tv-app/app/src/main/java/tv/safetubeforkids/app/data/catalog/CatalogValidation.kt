package tv.safetubeforkids.app.data.catalog

/**
 * The one rule SQLite cannot express for us.
 *
 * A container entry must not carry a video id, and a video entry must carry one and no playlist id.
 * Room has no `@Check` support, and a `CHECK` constraint written only into the migration would make
 * migrated databases disagree with freshly created ones, so the rule lives here instead and is
 * enforced at both ends:
 *
 *  - [ContentItemEntity] refuses to be constructed while malformed, so no malformed row can reach
 *    the database through any code path, including a direct DAO call;
 *  - [contentIdentityProblem] returns a reason instead of throwing, so the sync layer can screen an
 *    untrusted server payload *before* it turns it into entities - building entities first would turn
 *    one bad item into an exception halfway through a catalog replacement.
 *
 * A `PLAYLIST` item's `youtubePlaylistId` is **optional**, because such an item is a *container*: it
 * names a playlist when it imports one, and names nothing when the parent built it by hand. Requiring
 * an id here is what used to force a hand-built container to be shown to the child as its first video
 * (W6 removed that), and the tree's own rules already allow a `SUBCATEGORY` node with no import
 * source. What is still refused is a container carrying a *video* id: that is a contradiction the tree
 * cannot express either.
 *
 * Referential integrity (an item's category existing) is deliberately NOT duplicated here - the
 * foreign key owns that.
 */
object CatalogValidation {

    /** Human-readable reason the identity is malformed, or `null` when it is valid. */
    fun contentIdentityProblem(
        type: ContentItemType,
        youtubePlaylistId: String?,
        youtubeVideoId: String?,
    ): String? = when (type) {
        ContentItemType.PLAYLIST -> when {
            youtubeVideoId != null ->
                "a PLAYLIST item must not carry a youtubeVideoId (got '$youtubeVideoId')"
            else -> null
        }

        ContentItemType.VIDEO -> when {
            youtubeVideoId.isNullOrBlank() ->
                "a VIDEO item requires a non-blank youtubeVideoId"
            youtubePlaylistId != null ->
                "a VIDEO item must not carry a youtubePlaylistId (got '$youtubePlaylistId')"
            else -> null
        }
    }

    fun isValidContentIdentity(
        type: ContentItemType,
        youtubePlaylistId: String?,
        youtubeVideoId: String?,
    ): Boolean = contentIdentityProblem(type, youtubePlaylistId, youtubeVideoId) == null

    /** @throws IllegalArgumentException when the identity is malformed. */
    fun requireValidContentIdentity(
        type: ContentItemType,
        youtubePlaylistId: String?,
        youtubeVideoId: String?,
    ) {
        val problem = contentIdentityProblem(type, youtubePlaylistId, youtubeVideoId)
        require(problem == null) { "Malformed catalog content item: $problem" }
    }
}
