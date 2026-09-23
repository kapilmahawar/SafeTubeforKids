package tv.safetubeforkids.app

import android.content.Context
import android.content.SharedPreferences
import tv.safetubeforkids.app.auth.PinManager
import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.auth.SharedPrefsPinLockoutPersistence
import tv.safetubeforkids.app.auth.SharedPrefsSessionPersistence
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.catalog.CatalogRepository
import tv.safetubeforkids.app.data.catalog.CatalogSyncService
import tv.safetubeforkids.app.data.catalog.HttpCatalogApi
import tv.safetubeforkids.app.data.events.PlayEventRecorder
import tv.safetubeforkids.app.kiosk.KioskManager
import tv.safetubeforkids.app.util.CatalogSyncDebug
import tv.safetubeforkids.app.relay.RelayConfig
import tv.safetubeforkids.app.relay.RelayConnector
import tv.safetubeforkids.app.server.CatalogStore
import tv.safetubeforkids.app.server.FileCatalogStore
import tv.safetubeforkids.app.server.InMemoryCatalogStore
import tv.safetubeforkids.app.server.SAFE_TUBE_SERVER_PORT
import tv.safetubeforkids.app.timelimits.RoomTimeLimitStore
import tv.safetubeforkids.app.timelimits.RoomWatchTimeProvider
import tv.safetubeforkids.app.timelimits.TimeLimitManager

object ServiceLocator {
    lateinit var pinManager: PinManager
    lateinit var sessionManager: SessionManager
    lateinit var database: CacheDatabase
    lateinit var relayConfig: RelayConfig
    lateinit var relayConnector: RelayConnector
    lateinit var timeLimitManager: TimeLimitManager
    lateinit var updateChecker: UpdateChecker
    lateinit var kioskManager: KioskManager

    /**
     * The server's own authoritative catalog - the parent's configuration, not the TV's runtime
     * copy. Backed by a file so it outlives the process (see [FileCatalogStore]).
     */
    lateinit var catalogStore: CatalogStore

    private var initialized = false
    private lateinit var relayPrefs: SharedPreferences

    /** The TV's own session against its server, created once and reused. */
    private var selfSyncToken: String? = null

    /**
     * Local catalog access for the future TV UI and the future sync layer. Lazy, so it always wraps
     * whichever database `init` or `initForTest` installed.
     */
    val catalogRepository: CatalogRepository by lazy { CatalogRepository(database) }

    /**
     * The TV's catalog sync against its own SafeTube server.
     *
     * It reads over HTTP rather than reaching into [catalogStore] directly, so the sync path is the
     * same one a future remote parent server would use, and the JSON boundary is exercised in
     * production rather than only in tests. The token is a single session this process creates for
     * itself and reuses; it is never logged.
     *
     * Nothing calls this yet: Phase 4 owns when the TV syncs. Invoking [CatalogSyncService.syncCatalog]
     * is the whole API, and it is deliberately not awaited from any UI path.
     */
    val catalogSyncService: CatalogSyncService by lazy {
        CatalogSyncService(
            api = HttpCatalogApi(
                baseUrl = "http://127.0.0.1:$SAFE_TUBE_SERVER_PORT",
                tokenProvider = {
                    selfSyncToken ?: sessionManager.createSession()?.also { selfSyncToken = it }
                },
            ),
            repository = catalogRepository,
        )
    }

    private const val KEY_RELAY_ENABLED = "relay_enabled"

    fun init(context: Context) {
        if (initialized) return
        database = CacheDatabase.getInstance(context)
        catalogStore = FileCatalogStore.inFilesDir(context.filesDir)
        CatalogSyncDebug.init(context)
        val persistence = SharedPrefsSessionPersistence(
            context.getSharedPreferences("parentapproved_sessions", Context.MODE_PRIVATE)
        )
        sessionManager = SessionManager(persistence = persistence)

        // Relay config
        relayPrefs = context.getSharedPreferences("parentapproved_relay", Context.MODE_PRIVATE)
        relayConfig = RelayConfig(
            prefs = relayPrefs,
            relayUrl = BuildConfig.RELAY_URL,
        )

        // RelayConnector
        relayConnector = RelayConnector(config = relayConfig, appVersion = BuildConfig.VERSION_NAME)

        val pinLockoutPersistence = SharedPrefsPinLockoutPersistence(
            context.getSharedPreferences("parentapproved_pin_lockout", Context.MODE_PRIVATE)
        )
        pinManager = PinManager(
            onPinValidated = { sessionManager.createSession() ?: "" },
            lockoutPersistence = pinLockoutPersistence,
        )
        PlayEventRecorder.init(database)

        val timeLimitStore = RoomTimeLimitStore(database.timeLimitDao())
        val watchTimeProvider = RoomWatchTimeProvider(
            playEventDao = database.playEventDao(),
            currentVideoElapsedProvider = { PlayEventRecorder.getElapsedMs() },
        )
        timeLimitManager = TimeLimitManager(
            store = timeLimitStore,
            watchTimeProvider = watchTimeProvider,
        )

        updateChecker = UpdateChecker()
        updateChecker.startPeriodicCheck()

        kioskManager = KioskManager(context)

        initialized = true
    }

    fun isRelayEnabled(): Boolean {
        return if (::relayPrefs.isInitialized) {
            relayPrefs.getBoolean(KEY_RELAY_ENABLED, false)
        } else false
    }

    fun setRelayEnabled(enabled: Boolean) {
        relayPrefs.edit().putBoolean(KEY_RELAY_ENABLED, enabled).apply()
        if (enabled) {
            relayConnector.connect()
        } else {
            relayConnector.disconnect()
        }
    }

    fun initForTest(
        db: CacheDatabase,
        pin: PinManager,
        session: SessionManager,
        timeLimit: TimeLimitManager? = null,
        kiosk: KioskManager? = null,
        catalog: CatalogStore? = null,
    ) {
        database = db
        pinManager = pin
        sessionManager = session
        catalogStore = catalog ?: InMemoryCatalogStore()
        PlayEventRecorder.init(db)
        if (timeLimit != null) {
            timeLimitManager = timeLimit
        } else {
            // No-op default: no limits configured, always allowed
            val noOpStore = object : tv.safetubeforkids.app.timelimits.TimeLimitStore {
                override suspend fun getConfig() = null
                override suspend fun saveConfig(config: tv.safetubeforkids.app.timelimits.TimeLimitConfig) {}
                override suspend fun updateManualLock(locked: Boolean) {}
                override suspend fun updateBonus(minutes: Int, date: String) {}
            }
            val noOpWatch = object : tv.safetubeforkids.app.timelimits.WatchTimeProvider {
                override suspend fun getTodayWatchSeconds() = 0
            }
            timeLimitManager = TimeLimitManager(store = noOpStore, watchTimeProvider = noOpWatch)
        }
        if (kiosk != null) {
            kioskManager = kiosk
        }
        initialized = true
    }

    fun isInitialized(): Boolean = initialized
    fun isKioskManagerInitialized(): Boolean = ::kioskManager.isInitialized
}
