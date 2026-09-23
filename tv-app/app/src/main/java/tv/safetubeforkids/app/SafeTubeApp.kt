package tv.safetubeforkids.app

import android.app.Application
import tv.safetubeforkids.app.data.ChannelMeta
import tv.safetubeforkids.app.data.ContentSourceRepository
import tv.safetubeforkids.app.util.AppLogger
import tv.safetubeforkids.app.kiosk.HomeWatcherService
import tv.safetubeforkids.app.util.NewPipeDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe

class SafeTubeApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        /**
         * How often the TV asks its SafeTube server for a newer catalog.
         *
         * Fifteen minutes is a compromise: a parent's edit reaches the TV without anyone touching
         * it, and the TV still only makes four tiny loopback requests an hour. The request is a few
         * kilobytes over the loopback interface, the sync serializes itself, and nothing about it is
         * on the path that renders the screen.
         */
        const val CATALOG_SYNC_INTERVAL_MS = 15L * 60 * 1000

        /**
         * A short grace period before the first attempt, so the dashboard server (started by
         * MainActivity and ServerService) is already listening and the first sync is not a
         * connection-refused that has to wait a quarter of an hour to be retried.
         */
        const val CATALOG_SYNC_START_DELAY_MS = 3_000L
    }

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        if (CrashHandler.hasCrashLog(this)) {
            AppLogger.log("Previous crash detected — crash log available via /api/crash-log")
        }
        NewPipe.init(NewPipeDownloader.instance)
        ServiceLocator.init(this)
        // Only connect relay if user has enabled remote access
        if (ServiceLocator.isRelayEnabled()) {
            ServiceLocator.relayConnector.connect()
        }
        // Start home watcher if kiosk is enabled (delayed to avoid ANR on slow devices)
        appScope.launch {
            try {
                kotlinx.coroutines.delay(5_000) // Wait for app to fully initialize
                val config = ServiceLocator.database.kioskDao().getConfig()
                if (config?.kioskEnabled == true) {
                    HomeWatcherService.start(this@SafeTubeApp)
                    AppLogger.log("Home watcher service started")
                }
            } catch (e: Exception) {
                AppLogger.error("Failed to start home watcher: ${e.message}")
            }
        }
        // Auto-refresh all sources in background
        appScope.launch {
            try {
                val db = ServiceLocator.database
                val channels = db.channelDao().getAll()
                if (channels.isNotEmpty()) {
                    AppLogger.log("Auto-refreshing ${channels.size} sources on startup")
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
                    AppLogger.success("Startup auto-refresh complete")
                }
            } catch (e: Exception) {
                AppLogger.error("Startup auto-refresh failed: ${e.message}")
            }
        }
        // Catalog synchronization, in the background and never awaited.
        //
        // Local-first means the opposite of the obvious order: the screen is drawn from Room
        // immediately, and this runs alongside it. A sync that succeeds replaces the catalog in one
        // transaction and the UI follows through Room; a sync that fails - no server, a malformed
        // answer, an older version - changes nothing, and the TV keeps working from what it has.
        // CatalogSyncService serializes overlapping runs itself, so the startup attempt and the
        // periodic one cannot race.
        appScope.launch {
            try {
                kotlinx.coroutines.delay(CATALOG_SYNC_START_DELAY_MS)
                while (true) {
                    val result = ServiceLocator.catalogSyncService.syncCatalog()
                    AppLogger.log("Catalog sync on startup: ${result::class.java.simpleName}")
                    kotlinx.coroutines.delay(CATALOG_SYNC_INTERVAL_MS)
                }
            } catch (e: Exception) {
                // Nothing here is worth interrupting the child's television for.
                AppLogger.warn("Catalog sync loop stopped: ${e.message}")
            }
        }
    }
}
