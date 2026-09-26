package tv.safetubeforkids.app.ui.screens

import android.app.Application
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.data.ChannelMeta
import tv.safetubeforkids.app.data.ContentSourceRepository
import tv.safetubeforkids.app.util.AppLogger

data class WhitelistedApp(
    val packageName: String,
    val displayName: String,
    val icon: Drawable?,
)

/** Kiosk-mode state, kept separate so catalog updates never churn it. */
data class KioskUiState(
    val enabled: Boolean = false,
    val whitelistedApps: List<WhitelistedApp> = emptyList(),
)

/**
 * Supplies the home screen with the parent's catalog.
 *
 * **The catalog comes from Room and only from Room.** This view model opens three local flows - the
 * catalog, the artwork index and the resumable-video join - and projects them into what the screen
 * draws. It performs no HTTP: when the background sync replaces the catalog, Room invalidates, the
 * flows re-emit and the UI updates on its own, with no imperative reload anywhere.
 *
 * ### What this view model deliberately does not do
 * It does not validate payloads, compare catalog versions, parse server JSON or decide what may
 * play. Those belong to Phase 3's sync components and to `PlaybackAuthorization`.
 *
 * It does still refresh the *approved* sources in the background, as the app always has. That is a
 * different concern from the catalog: the approved cache is what makes a video playable, so if it
 * were never refreshed a synced catalog would render but nothing would open.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ServiceLocator.database
    private val catalogRepository = ServiceLocator.catalogRepository

    private val _kioskState = MutableStateFlow(KioskUiState())
    val kioskState: StateFlow<KioskUiState> = _kioskState.asStateFlow()

    private var started = false
    private var lastChannelSignature = ""

    /**
     * `WhileSubscribed` so the database work stops while the player or a container is on screen and
     * resumes on the way back; the last value is retained, so returning repaints the same shelves
     * immediately instead of flashing an empty state.
     *
     * The three flows are the whole tree, the artwork the approved cache holds, and the half-watched
     * videos. There is no fourth "items" view: the tree *is* the catalog, and reading it directly is
     * what lets a sub-category be a container rather than being flattened into its first video. It is
     * still Room and only Room - the catalog arrives with the sync the TV already did, so the shelves
     * render with the server switched off.
     */
    val catalogState: StateFlow<CatalogUiState> = combine(
        catalogRepository.observeTree(),
        catalogRepository.observeVideoThumbnails(),
        catalogRepository.observeResumableVideos(
            CatalogUiProjection.RESUME_MIN_POSITION_MS,
            CatalogUiProjection.RESUME_MAX_PERCENT,
        ),
    ) { tree, thumbnails, resumable ->
        CatalogUiProjection.build(tree, thumbnails, resumable)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CatalogUiState(),
    )

    fun start() {
        if (started) return // Prevent duplicate loops on re-composition
        started = true
        refreshApprovedSources()
        loadKiosk()
        pollForChanges()
    }

    /** The Refresh button: re-resolve approved sources and ask for a newer catalog. */
    fun refresh() {
        refreshApprovedSources()
        loadKiosk()
        requestCatalogSync()
    }

    /**
     * Keeps the approved cache and the approved sources' names/counts current.
     *
     * The results are not rendered any more - the catalog supplies what the child sees - but the
     * cache they populate is what `PlaybackAuthorization` reads, so this must keep running.
     */
    private fun refreshApprovedSources() {
        viewModelScope.launch {
            try {
                val channels = db.channelDao().getAll()
                if (channels.isEmpty()) return@launch
                val metas = channels.map { entity ->
                    ChannelMeta(
                        id = entity.id,
                        sourceType = entity.sourceType,
                        sourceId = entity.sourceId,
                        sourceUrl = entity.sourceUrl,
                        displayName = entity.displayName,
                    )
                }
                ContentSourceRepository.resolveAllChannels(metas, db)
                lastChannelSignature = channelSignature(channels)
            } catch (e: Exception) {
                AppLogger.warn("Approved source refresh failed: ${e.message}")
            }
        }
    }

    /**
     * Asks for a newer catalog in the background. Deliberately fire-and-forget: the screen is
     * already showing the local catalog, and a sync is an improvement, never a prerequisite.
     */
    private fun requestCatalogSync() {
        viewModelScope.launch {
            runCatching { ServiceLocator.catalogSyncService.syncCatalog() }
                .onFailure { AppLogger.warn("Catalog sync request failed: ${it.message}") }
        }
    }

    /**
     * Watches for the two things a parent can change outside the catalog: the kiosk configuration
     * (which the home screen renders directly) and the set of approved sources (which the approved
     * cache has to follow). Catalog changes need no polling - Room reports them.
     */
    private fun pollForChanges() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000)

                val channels = try {
                    db.channelDao().getAll()
                } catch (e: Exception) {
                    emptyList()
                }
                if (channels.isNotEmpty() && channelSignature(channels) != lastChannelSignature) {
                    refreshApprovedSources()
                }

                val config = db.kioskDao().getConfig()
                val shouldBeEnabled = config?.kioskEnabled == true
                val whitelisted = db.whitelistDao().getWhitelisted().map { it.packageName }.toSet()
                val current = _kioskState.value
                if (shouldBeEnabled != current.enabled
                    || whitelisted != current.whitelistedApps.map { it.packageName }.toSet()
                ) {
                    loadKiosk()
                }
            }
        }
    }

    private fun channelSignature(channels: List<tv.safetubeforkids.app.data.cache.ChannelEntity>): String =
        channels.joinToString(",") { "${it.id}:${it.videoCount}" }

    private fun loadKiosk() {
        viewModelScope.launch {
            val config = db.kioskDao().getConfig()
            if (config?.kioskEnabled != true) {
                _kioskState.value = KioskUiState()
                return@launch
            }
            val pm = getApplication<Application>().packageManager
            val apps = db.whitelistDao().getWhitelisted().map { entity ->
                val icon = try {
                    pm.getApplicationIcon(entity.packageName)
                } catch (e: Exception) {
                    null
                }
                WhitelistedApp(
                    packageName = entity.packageName,
                    displayName = entity.displayName,
                    icon = icon,
                )
            }
            _kioskState.value = KioskUiState(enabled = true, whitelistedApps = apps)
        }
    }

    /**
     * Turns a *video* card press into a player destination.
     *
     * A container card never reaches here: pressing one opens the container (see `CatalogNavigation`),
     * because starting the first video inside a group is exactly what W6 removed. A card that names no
     * video resolves to nothing at all rather than to a guessed identifier, so a malformed catalog
     * cannot turn a press into a request for something nobody approved.
     *
     * Nothing here grants permission: the chosen video id still has to pass
     * `PlaybackAuthorization` inside the player before any media is prepared.
     */
    fun onCardSelected(card: CatalogCardUi, onTarget: (CatalogPlaybackTarget) -> Unit) {
        viewModelScope.launch {
            val videoId = card.videoId?.takeIf { it.isNotBlank() } ?: return@launch
            onTarget(
                CatalogPlaybackTarget(
                    videoId = videoId,
                    playlistId = card.playlistId?.takeIf { it.isNotBlank() } ?: videoId,
                )
            )
        }
    }
}
