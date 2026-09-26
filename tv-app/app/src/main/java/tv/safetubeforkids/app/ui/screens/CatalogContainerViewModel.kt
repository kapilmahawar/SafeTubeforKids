package tv.safetubeforkids.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tv.safetubeforkids.app.ServiceLocator

/**
 * Supplies an open container with its children.
 *
 * **The catalog comes from Room and only from Room.** This view model joins two local flows - the tree
 * and the approved artwork index - and projects them into the cards the container screen draws. It
 * performs no HTTP and needs no server: a container the child opened yesterday still opens today with
 * the network off, because the tree is what the TV synchronised.
 *
 * A container that does not exist (a stale navigation argument, a node deleted by a sync while the
 * screen was open) projects to null, and the screen says so rather than drawing an empty list that
 * looks like a broken shelf.
 */
class CatalogContainerViewModel(application: Application) : AndroidViewModel(application) {

    private val catalogRepository = ServiceLocator.catalogRepository

    private val states = mutableMapOf<String, StateFlow<CatalogContainerUi?>>()

    /** The container's cards for [containerId], re-emitted whenever the tree or the artwork changes. */
    fun stateFor(containerId: String): StateFlow<CatalogContainerUi?> =
        states.getOrPut(containerId) {
            combine(
                catalogRepository.observeTree(),
                catalogRepository.observeVideoThumbnails(),
            ) { tree, thumbnails ->
                CatalogUiProjection.container(containerId, tree, thumbnails)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )
        }
}
