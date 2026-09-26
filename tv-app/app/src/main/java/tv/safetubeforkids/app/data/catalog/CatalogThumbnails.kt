package tv.safetubeforkids.app.data.catalog

/**
 * Which video's picture stands for a container.
 *
 * A shelf or a sub-category is a group, not a video, so something has to represent it in the grid. The
 * parent either lets the app choose ([ThumbnailMode.AUTO]) or names one of the videos inside it
 * ([ThumbnailMode.VIDEO]), and this object is the single place that turns that configuration into a
 * video id the TV can look artwork up for.
 *
 * It is a **pure function of the node list**: no database, no clock, no network, no iteration over a
 * `HashMap`. Everything it decides comes from `parent_id`, `position` and `id`, which is the same
 * ordering rule every read in this project uses - so the same catalog always produces the same
 * thumbnail, on the TV, in the editor and in a test.
 *
 * ### What "eligible" means
 *
 * A video can represent a container only when the child could actually reach it:
 * `enabled`, with a non-blank `youtubeVideoId`. A disabled node is skipped, and a disabled container
 * takes its whole subtree with it - using a hidden video as a picture would put a trace of it back on
 * screen, which is exactly what disabling it was for. A parent's explicit choice is held to the same
 * standard rather than exempted from it, so hiding a video hides it even when a shelf points at it.
 *
 * ### Identity, and the one thing this deliberately is not
 *
 * The result is a **YouTube video id**, because that is what artwork is looked up by. It is not
 * permission: a video being chosen here - or being the reason a shelf has a picture - grants nothing.
 * `PlaybackAuthorization` remains the only thing that decides what may play, and an unapproved video
 * that represents a shelf still cannot play.
 *
 * ### Never throws, never hangs
 *
 * The input is local catalog data that may have been hand-edited, so nothing here assumes the tree is
 * well formed: a missing node, a selection pointing at nothing, a selection pointing at a video
 * outside the subtree, a duplicate id or a cycle all resolve to `null` (or to the `AUTO` answer)
 * rather than to an exception. A shelf with no picture shows the screen's existing placeholder, which
 * is the behaviour every other card without artwork already has.
 */
object CatalogThumbnails {

    /**
     * The video id whose artwork represents the node [nodeId], or null when nothing eligible exists.
     *
     *  - a `VIDEO` node represents itself: its picture is its own YouTube thumbnail;
     *  - a `CATEGORY` or `SUBCATEGORY` answers with its chosen video when that choice is still valid,
     *    and otherwise with what `AUTO` would have picked - a fallback, never a failure;
     *  - an unknown node id answers with null.
     */
    fun representativeFor(nodes: List<CatalogNodeEntity>, nodeId: String): String? =
        representativeFor(nodes.associateBy { it.id }, nodeId)

    /**
     * Every container's representative in one pass, keyed by node id.
     *
     * Containers are the only nodes that need one - a video's picture is itself - so a caller can
     * look up a card's artwork with a single map.
     */
    fun representatives(nodes: List<CatalogNodeEntity>): Map<String, String> {
        val byId = nodes.associateBy { it.id }
        val result = LinkedHashMap<String, String>()
        nodes.forEach { node ->
            if (node.nodeType == CatalogNodeType.CATEGORY || node.nodeType == CatalogNodeType.SUBCATEGORY) {
                representativeFor(byId, node.id)?.let { result[node.id] = it }
            }
        }
        return result
    }

    private fun representativeFor(byId: Map<String, CatalogNodeEntity>, nodeId: String): String? {
        val node = byId[nodeId] ?: return null
        return when (node.nodeType) {
            CatalogNodeType.VIDEO -> usableVideoId(node)

            CatalogNodeType.CATEGORY, CatalogNodeType.SUBCATEGORY ->
                explicitlyChosenVideo(node, byId) ?: autoVideo(node, byId)
        }
    }

    /**
     * The parent's choice, when it is still a choice that can be honoured.
     *
     * The stored value is the **catalog node id** of a descendant video, not a YouTube id: a node id
     * identifies exactly one node, whereas the same video may legitimately sit in the subtree twice
     * (the same video curated onto two shelves, or imported again), and "which of these two did the
     * parent pick" has to have one answer.
     *
     * A selection that no longer holds - the video was deleted, moved out, hidden, or emptied of its
     * identifier - is not an error at render time. It is ignored, and `AUTO` answers instead. Hiding is
     * included on purpose: a hidden video may not stand for a shelf that is still on screen, or hiding
     * it would put a trace of it straight back. The choice itself is never rewritten, so unhiding the
     * video brings the picture back exactly as the parent left it.
     */
    private fun explicitlyChosenVideo(
        container: CatalogNodeEntity,
        byId: Map<String, CatalogNodeEntity>,
    ): String? {
        if (container.thumbnailMode != ThumbnailMode.VIDEO) return null
        val chosenId = container.thumbnailVideoId?.takeIf { it.isNotBlank() } ?: return null
        val chosen = byId[chosenId] ?: return null
        if (chosen.nodeType != CatalogNodeType.VIDEO) return null
        if (!isVisibleFrom(chosen, container.id, byId)) return null
        return usableVideoId(chosen)
    }

    /**
     * Whether [node] sits below [ancestorId] *and* is visible from it: the node is enabled, and so is
     * every node on the way up to the ancestor.
     *
     * The ancestor itself is not consulted - a container that is hidden is not drawn at all, so what it
     * chooses for itself is nobody's business until it is shown again.
     */
    private fun isVisibleFrom(
        node: CatalogNodeEntity,
        ancestorId: String,
        byId: Map<String, CatalogNodeEntity>,
    ): Boolean {
        if (!node.enabled) return false

        var current = node.parentId
        var guard = 0
        while (current != null && guard++ < 64) {
            if (current == ancestorId) return true
            val parent = byId[current] ?: return false
            if (!parent.enabled) return false
            current = parent.parentId
        }
        return false
    }

    /**
     * The first eligible video inside the container, in catalog order.
     *
     * The walk is a pre-order depth-first traversal: a parent's children in `position ASC, id ASC`,
     * each video taken where it stands and each container descended into before the next sibling is
     * looked at. That is what makes the documented examples come out as they do - a direct video
     * before a sub-category wins, and with only sub-categories the first one's first video wins
     * (`X → B` before `Y → C`).
     *
     * Order comes from the two stored numbers and nothing else: not insertion order, not a response
     * order, not a timestamp, not a hash, not a random pick.
     */
    private fun autoVideo(container: CatalogNodeEntity, byId: Map<String, CatalogNodeEntity>): String? {
        val visited = mutableSetOf<String>()

        fun walk(parentId: String): String? {
            for (child in childrenOf(byId, parentId)) {
                if (!visited.add(child.id)) continue      // a hand-edited cycle cannot loop forever
                if (!child.enabled) continue              // hidden, and so is everything inside it

                when (child.nodeType) {
                    CatalogNodeType.VIDEO -> usableVideoId(child)?.let { return it }
                    CatalogNodeType.CATEGORY, CatalogNodeType.SUBCATEGORY ->
                        walk(child.id)?.let { return it }
                }
            }
            return null
        }

        return walk(container.id)
    }

    /** A node's own picture, when it has one: enabled, and naming a video. */
    private fun usableVideoId(node: CatalogNodeEntity): String? =
        node.youtubeVideoId?.takeIf { it.isNotBlank() && node.enabled }

    /** One parent's children, in the order the tree is always read in. */
    private fun childrenOf(byId: Map<String, CatalogNodeEntity>, parentId: String): List<CatalogNodeEntity> =
        byId.values
            .filter { it.parentId == parentId }
            .sortedWith(compareBy({ it.position }, { it.id }))
}
