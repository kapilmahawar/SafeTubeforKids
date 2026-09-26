package tv.safetubeforkids.app.server

import android.content.Context
import tv.safetubeforkids.app.BuildConfig
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.data.ChannelMeta
import tv.safetubeforkids.app.data.ContentSourceRepository
import tv.safetubeforkids.app.data.catalog.CatalogSyncResult
import tv.safetubeforkids.app.util.AppLogger
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

/** The one port the dashboard server binds and the sync client talks to. */
const val SAFE_TUBE_SERVER_PORT = 8080

class SafeTubeServer(private val context: Context, private val port: Int = SAFE_TUBE_SERVER_PORT) {
    private var server: EmbeddedServer<*, *>? = null

    var isRunning: Boolean = false
        private set

    fun start() {
        if (isRunning) return
        server = embeddedServer(Netty, port = port) {
            configureServer(this, context)
        }.start(wait = false)
        isRunning = true
        AppLogger.log("Ktor server started on port $port")
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        isRunning = false
        AppLogger.log("Ktor server stopped")
    }

    companion object {
        fun configureServer(application: Application, appContext: Context? = null) {
            application.apply {
                install(ContentNegotiation) {
                    json(Json {
                        prettyPrint = true
                        isLenient = true
                        ignoreUnknownKeys = true
                        // Emit fields whose value equals the default too. Without this, a
                        // paused player reports no "playing" field at all (and a zero
                        // position reports no "positionSec"), which makes the API ambiguous
                        // for the dashboard, the relay and automated device tests.
                        encodeDefaults = true
                    })
                }

                install(CORS) {
                    // Restrict CORS to known origins: local dashboard and official relay.
                    // (anyHost() is too permissive for production)
                    allowHost("localhost:8080")
                    allowHost("relay.parentapproved.tv", schemes = listOf("https"))

                    allowMethod(HttpMethod.Get)
                    allowMethod(HttpMethod.Post)
                    allowMethod(HttpMethod.Put)
                    allowMethod(HttpMethod.Delete)
                    allowHeader(HttpHeaders.Authorization)
                    allowHeader(HttpHeaders.ContentType)
                }

                install(StatusPages) {
                    exception<Throwable> { call, cause ->
                        val errorMessage = if (BuildConfig.IS_DEBUG) {
                            cause.message ?: "Unknown error"
                        } else {
                            "An unexpected error occurred"
                        }
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to errorMessage)
                        )
                    }
                }

                routing {
                    authRoutes(ServiceLocator.pinManager, ServiceLocator.sessionManager)
                    playlistRoutes(ServiceLocator.sessionManager, ServiceLocator.database)
                    catalogRoutes(ServiceLocator.sessionManager, ServiceLocator.catalogStore)
                    // Resolving a playlist for the editor's import is read-only: it reads nothing but
                    // YouTube and reports whether the source is already approved.
                    catalogImportRoutes(ServiceLocator.sessionManager, ServiceLocator.database)
                    sourceTransferRoutes(ServiceLocator.sessionManager, ServiceLocator.database)
                    // The two endpoints the redesigned dashboard needs beyond the document itself:
                    // the TV's own pictures and counts, and "send it to my TV now". Both are
                    // read-mostly and neither can make anything playable that the approved sources
                    // did not already allow.
                    catalogLibraryRoutes(
                        sessionManager = ServiceLocator.sessionManager,
                        catalog = ServiceLocator.catalogRepository,
                        database = ServiceLocator.database,
                        refresh = { refreshCatalogAndSources() },
                    )
                    playbackRoutes(ServiceLocator.sessionManager)
                    statsRoutes(ServiceLocator.sessionManager, ServiceLocator.database)
                    timeLimitRoutes(ServiceLocator.sessionManager, ServiceLocator.timeLimitManager)
                    if (ServiceLocator.isKioskManagerInitialized()) {
                        appsRoutes(ServiceLocator.sessionManager, ServiceLocator.kioskManager, ServiceLocator.database)
                    }
                    statusRoutes(ServiceLocator.sessionManager)
                    if (appContext != null) {
                        crashLogRoutes(ServiceLocator.sessionManager, appContext)
                    }
                    dashboardRoutes()
                }
            }
        }

        /**
         * What `POST /catalog/refresh` performs: bring the TV up to date with the catalog the parent
         * has just published, doing exactly the work the TV's own Refresh button does.
         *
         * The order matters and is the same order the TV uses at startup. A source the parent allowed
         * a moment ago has no cached videos yet, and the TV materialises and authorises videos from
         * that cache - so the sources that have nothing cached are resolved *first*, and only then is
         * the catalog itself fetched. Sources that already have videos are left to the TV's own
         * periodic poll, which is what keeps this request short instead of re-resolving up to twenty
         * YouTube sources while the parent waits.
         *
         * Nothing here grants permission: resolving a source caches what an approved source already
         * allows, and the sync client only reads the catalog the parent already wrote.
         */
        private suspend fun refreshCatalogAndSources(): RefreshOutcome {
            if (!ServiceLocator.isInitialized()) return RefreshOutcome.NotConnected

            return try {
                val database = ServiceLocator.database
                val channels = database.channelDao().getAll()

                val unresolved = channels.filter { channel ->
                    database.videoDao().getByPlaylist(channel.sourceId).isEmpty()
                }
                if (unresolved.isNotEmpty()) {
                    ContentSourceRepository.resolveAllChannels(
                        unresolved.map { channel ->
                            ChannelMeta(
                                id = channel.id,
                                sourceType = channel.sourceType,
                                sourceId = channel.sourceId,
                                sourceUrl = channel.sourceUrl,
                                displayName = channel.displayName,
                            )
                        },
                        database,
                    )
                }

                when (val result = ServiceLocator.catalogSyncService.syncCatalog()) {
                    is CatalogSyncResult.Updated ->
                        RefreshOutcome.Refreshed(result.catalogVersion, unresolved.size)

                    is CatalogSyncResult.AlreadyCurrent ->
                        RefreshOutcome.Refreshed(result.catalogVersion, unresolved.size)

                    is CatalogSyncResult.ServerUnavailable -> RefreshOutcome.NotConnected

                    is CatalogSyncResult.Unauthorized ->
                        RefreshOutcome.Failed("The TV could not sign in to its own catalog service.")

                    is CatalogSyncResult.InvalidResponse ->
                        RefreshOutcome.Failed("The TV could not read its own catalog: ${result.reason}")

                    is CatalogSyncResult.UnsupportedSchema ->
                        RefreshOutcome.Failed("This TV speaks catalog version ${result.supportedSchemaVersion}, and it was offered ${result.serverSchemaVersion}.")

                    is CatalogSyncResult.InvalidCatalog ->
                        RefreshOutcome.Failed("The TV refused the library: ${result.problems.firstOrNull()?.reason ?: "unknown reason"}")

                    is CatalogSyncResult.VersionRegression ->
                        RefreshOutcome.Failed("The TV already has a newer library than the one it was sent.")

                    is CatalogSyncResult.LocalWriteFailed ->
                        RefreshOutcome.Failed("The TV could not store the library: ${result.reason}")
                }
            } catch (e: Exception) {
                AppLogger.warn("Refreshing the TV from the dashboard failed: ${e.message}")
                RefreshOutcome.Failed(e.message ?: "The TV could not be refreshed")
            }
        }
    }
}
