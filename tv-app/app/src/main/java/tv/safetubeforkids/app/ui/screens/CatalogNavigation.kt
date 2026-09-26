package tv.safetubeforkids.app.ui.screens

import tv.safetubeforkids.app.data.catalog.CatalogNodeEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeType

/**
 * What a card press does, and where Back comes back to - as a pure function of the catalog.
 *
 * The screens read this instead of deciding for themselves, so "a container opens, a video plays" and
 * "Back returns to the logical parent" are one rule that a test can ask about without a device, a
 * Compose runtime or a navigation controller.
 *
 * ### The hierarchy
 *
 * ```text
 * HOME                     a shelf per CATEGORY; the category is only the shelf's title
 *  └── container           a SUBCATEGORY card opens the sub-category
 *       ├── container      (a sub-category inside a sub-category, if a catalog ever holds one)
 *       └── video          a VIDEO card plays, through PlaybackAuthorization as always
 * ```
 *
 * A `CATEGORY` is deliberately not a destination: it is the heading above its children, and giving it
 * an id to open would turn the child's home screen into a menu of shelves.
 */
object CatalogNavigation {

    /**
     * The container [card] opens, or null when pressing it is not a navigation at all.
     *
     * A video card answers null - it is the player's business, and this object never decides what plays.
     * A container card answers its own node id.
     */
    fun containerOpenedBy(card: CatalogCardUi): String? =
        card.containerId?.takeIf { it.isNotBlank() && card.kind == CatalogCardKind.CONTAINER }

    /**
     * Where Back goes from the container [containerId] is showing, as a container id, or null for the
     * home screen.
     *
     * A container's parent is normally its shelf, and the shelf is the home screen - so Back from a
     * sub-category lands on Home. If a catalog ever nests a container inside another container, Back
     * lands on that inner container instead, which is what "the logical parent" means.
     *
     * Only a container has a screen to go back from: an unknown id, a shelf (which is a heading, not a
     * screen) and a video all answer null, and the home screen is then the only possible destination.
     */
    fun backDestination(tree: List<CatalogNodeEntity>, containerId: String): String? {
        val node = tree.firstOrNull { it.id == containerId } ?: return null
        if (node.nodeType != CatalogNodeType.SUBCATEGORY) return null
        val parent = node.parentId?.let { parentId -> tree.firstOrNull { it.id == parentId } } ?: return null
        return parent.id.takeIf { parent.nodeType == CatalogNodeType.SUBCATEGORY }
    }

    /**
     * The containers between the home screen and [containerId], outermost first, excluding the home
     * screen itself.
     *
     * Empty when the id names nothing, or names something that is not a container.
     */
    fun pathFromHome(tree: List<CatalogNodeEntity>, containerId: String): List<String> {
        val byId = tree.associateBy { it.id }
        val node = byId[containerId] ?: return emptyList()
        if (node.nodeType != CatalogNodeType.SUBCATEGORY) return emptyList()

        val path = ArrayDeque<String>()
        var current: CatalogNodeEntity? = node
        var guard = 0

        while (current != null && guard++ < 64) {
            // A shelf (or ROOT) ends the walk: everything collected so far is the path.
            if (current.nodeType != CatalogNodeType.SUBCATEGORY) return path.toList()
            path.addFirst(current.id)
            current = current.parentId?.let { byId[it] }
        }
        return path.toList()
    }
}
