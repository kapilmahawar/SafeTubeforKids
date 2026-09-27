package tv.safetubeforkids.app.reset

import android.content.Context
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.auth.ParentCredentialStore
import tv.safetubeforkids.app.auth.SharedPrefsParentCredentialStore
import tv.safetubeforkids.app.auth.TEST_PIN
import tv.safetubeforkids.app.auth.testPinManager
import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.ChannelEntity
import tv.safetubeforkids.app.data.cache.KioskConfigEntity
import tv.safetubeforkids.app.data.cache.TimeLimitConfigEntity
import tv.safetubeforkids.app.data.cache.VideoEntity
import tv.safetubeforkids.app.data.catalog.CatalogMetadataEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeType
import tv.safetubeforkids.app.data.catalog.ThumbnailMode
import tv.safetubeforkids.app.data.events.PlayEventEntity
import tv.safetubeforkids.app.playback.PlaybackApproval
import tv.safetubeforkids.app.playback.PlaybackAuthorization
import tv.safetubeforkids.app.server.FileCatalogStore

/**
 * What the destructive reset erases, and - just as important - what it leaves alone.
 *
 * The claim in `SafeTubeReset`'s documentation is a list, and a list in a comment is worth nothing, so
 * every line of it is checked here: each SafeTube-owned thing is present before the wipe and gone
 * after it, and three things that belong to somebody else are still exactly where they were.
 *
 * The context is real (Robolectric), the database is real SQLite, and the files are real files in the
 * app's own directory - because "did this delete the right things?" is not a question a fake can
 * answer.
 */
@RunWith(RobolectricTestRunner::class)
class SafeTubeResetTest {

    private lateinit var context: Context
    private lateinit var db: CacheDatabase
    private lateinit var store: ParentCredentialStore
    private lateinit var sessions: SessionManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
        store = SharedPrefsParentCredentialStore.open(context)
        store.clear()
        sessions = SessionManager()

        ServiceLocator.initForTest(
            db = db,
            pin = testPinManager(store = store),
            session = sessions,
            catalog = FileCatalogStore.inFilesDir(context.filesDir),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seedEverything() = runBlocking {
        db.channelDao().insert(ChannelEntity(sourceType = "yt_playlist", sourceId = "PLxyz", sourceUrl = "u", displayName = "Songs"))
        db.videoDao().insertAll(listOf(VideoEntity(videoId = "vid-1", playlistId = "PLxyz", title = "One", thumbnailUrl = "", durationSeconds = 0, position = 0)))
        db.playEventDao().insert(PlayEventEntity(videoId = "vid-1", playlistId = "PLxyz", startedAt = 1L, durationSec = 10, completedPct = 50))
        db.playbackPositionDao().upsert(
            tv.safetubeforkids.app.data.cache.PlaybackPositionEntity(videoId = "vid-1", positionMs = 1000, durationMs = 2000, updatedAt = 1L)
        )
        db.timeLimitDao().insertOrUpdate(TimeLimitConfigEntity())
        db.kioskDao().insertOrUpdate(KioskConfigEntity(kioskEnabled = true))
        db.whitelistDao().insertAll(
            listOf(tv.safetubeforkids.app.data.cache.WhitelistEntity(packageName = "com.example", displayName = "Example", whitelisted = true, addedAt = 1L))
        )
        db.catalogNodeDao().insert(
            CatalogNodeEntity(
                id = "cat-1", parentId = null, nodeType = CatalogNodeType.CATEGORY, title = "Cartoon",
                position = 0, enabled = true, youtubeVideoId = null, youtubePlaylistId = null,
                thumbnailMode = ThumbnailMode.AUTO, thumbnailVideoId = null, thumbnailUrl = null,
                createdAt = 1L, updatedAt = 1L,
            )
        )
        db.catalogMetadataDao().upsert(CatalogMetadataEntity(catalogVersion = 7L))

        // The server's catalog document, written the way the server writes it.
        ServiceLocator.catalogStore.write(emptyList())

        // The crash log, which is SafeTube's own record of itself.
        tv.safetubeforkids.app.CrashHandler.getCrashFile(context).writeText("a crash")
    }

    private fun writePrefs(name: String, key: String, value: String) {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString(key, value).commit()
    }

    private fun readPrefs(name: String, key: String): String? =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).getString(key, null)

    @Test
    fun theResetErasesEverySafeTubeOwnedThing() = runBlocking {
        seedEverything()
        writePrefs("parentapproved_sessions", "sessions", "{\"token\":1}")
        writePrefs("parentapproved_pin_lockout", "pin_failed_attempts", "3")
        writePrefs("parentapproved_relay", "relay_enabled", "true")
        writePrefs("parentapproved_catalog_debug", "force_server_unavailable", "true")
        val crashFile = tv.safetubeforkids.app.CrashHandler.getCrashFile(context)
        assertTrue("the crash log exists before the wipe", crashFile.exists())
        assertNotNull("and a credential exists", store.readPin())

        SafeTubeReset.wipe(context)

        assertNull("approved sources are gone", db.channelDao().getBySourceId("PLxyz"))
        assertEquals("cached videos are gone", 0, db.videoDao().count())
        assertEquals("watch history is gone", 0, db.playEventDao().count())
        assertNull("resume positions are gone", db.playbackPositionDao().get("vid-1"))
        assertNull("time limits are gone", db.timeLimitDao().getConfig())
        assertNull("the kiosk configuration is gone", db.kioskDao().getConfig())
        assertEquals("the app whitelist is gone", 0, db.whitelistDao().getWhitelisted().size)
        assertNull("the curated catalog is gone", db.catalogNodeDao().getById("cat-1"))
        assertEquals("not even an empty node is left", 0, db.catalogNodeDao().count())
        assertNull("the TV's catalog version is gone", db.catalogMetadataDao().get())

        assertFalse("the Parent PIN is gone", store.isConfigured())
        assertNull(store.readPin())
        assertNull("and so is the Recovery Code", store.readRecovery())
        assertEquals("every dashboard session is gone", 0, sessions.getActiveSessionCount())
        assertEquals("the catalog document is gone", 0, ServiceLocator.catalogStore.read().nodes.size)
        assertEquals("and its version starts again", 0L, ServiceLocator.catalogStore.read().catalogVersion)

        assertNull("the session store is empty", readPrefs("parentapproved_sessions", "sessions"))
        assertNull("the attempt counters are cleared", readPrefs("parentapproved_pin_lockout", "pin_failed_attempts"))
        assertNull("remote access is forgotten", readPrefs("parentapproved_relay", "relay_enabled"))
        assertNull("and the debug switch with it", readPrefs("parentapproved_catalog_debug", "force_server_unavailable"))
        assertFalse("the crash log is removed", crashFile.exists())
        assertFalse("no catalog document file remains", File(context.filesDir, FileCatalogStore.FILE_NAME).exists())
        assertFalse("and no version file either", File(context.filesDir, FileCatalogStore.VERSION_FILE_NAME).exists())
    }

    @Test
    fun theResetLeavesEverythingElseExactlyWhereItWas() = runBlocking {
        seedEverything()
        val foreignFile = File(context.filesDir, "someones-notes.txt").apply { writeText("not SafeTube's") }
        writePrefs("someone_elses_prefs", "key", "value")

        SafeTubeReset.wipe(context)

        assertTrue("a file this app did not write is untouched", foreignFile.exists())
        assertEquals("not SafeTube's", foreignFile.readText())
        assertEquals(
            "preferences belonging to somebody else are untouched",
            "value",
            readPrefs("someone_elses_prefs", "key"),
        )
        // And the database is still a working database rather than a dropped one: the wipe empties
        // SafeTube's rows, it does not take the file away from under Room.
        db.channelDao().insert(ChannelEntity(sourceType = "yt_video", sourceId = "vid-9", sourceUrl = "u", displayName = "After"))
        assertEquals(1, db.channelDao().count())
    }

    @Test
    fun afterTheResetTheDeviceIsBackToFirstRun() = runBlocking {
        seedEverything()

        SafeTubeReset.wipe(context)

        assertFalse("a wiped TV has no Parent PIN, so onboarding runs again", ServiceLocator.pinManager.isConfigured())
        val created = ServiceLocator.pinManager.setup(TEST_PIN, TEST_PIN)
        assertTrue("and a new credential can be created on it", created is tv.safetubeforkids.app.auth.PinSetupResult.Created)
        assertTrue("which becomes the device's credential", ServiceLocator.pinManager.isConfigured())
    }

    @Test
    fun theResetReportsWhatItRemoved() = runBlocking {
        seedEverything()

        val report = SafeTubeReset.wipe(context)

        assertTrue("the watchdog can see the counts", report.removed.containsKey("files"))
        assertEquals("one approved source was removed", 1, report.removed["play_events"])
        assertTrue(report.removed["files"]!! >= 2)
    }

    @Test
    fun theConfirmationPhraseIsExactlyTheOneDisplayed() {
        assertEquals("I UNDERSTAND THIS ERASES EVERYTHING", SafeTubeReset.CONFIRMATION_PHRASE)
        assertEquals("five words, as designed", 5, SafeTubeReset.CONFIRMATION_PHRASE.split(" ").size)
    }

    @Test
    fun thePhraseIsMatchedExactlyAndOnlyInFull() {
        assertTrue(SafeTubeReset.phraseMatches("I UNDERSTAND THIS ERASES EVERYTHING"))
        assertTrue("case is forgiven: the reading is what matters", SafeTubeReset.phraseMatches("i understand this erases everything"))
        assertTrue("and surrounding space", SafeTubeReset.phraseMatches("  I understand this erases everything  "))

        assertFalse("a prefix is not the phrase", SafeTubeReset.phraseMatches("I UNDERSTAND THIS ERASES"))
        assertFalse("nor a single word", SafeTubeReset.phraseMatches("I"))
        assertFalse("nor an empty string", SafeTubeReset.phraseMatches(""))
        assertFalse("nor a different sentence", SafeTubeReset.phraseMatches("yes, erase it all please"))
        assertFalse("nor the phrase with something added", SafeTubeReset.phraseMatches("I UNDERSTAND THIS ERASES EVERYTHING!"))
    }

    @Test
    fun wipingAnAlreadyEmptyDeviceIsHarmless() = runBlocking {
        // A parent who resets a TV that has nothing on it must not see a crash; the operation is
        // idempotent because everything it does is "delete if present".
        SafeTubeReset.wipe(context)
        SafeTubeReset.wipe(context)

        assertEquals(0, db.channelDao().count())
        assertFalse(store.isConfigured())
    }

    @Test
    fun noUnauthenticatedRouteCanTriggerTheWipe() {
        // The destructive reset is a TV-screen operation. This is the guard against somebody later
        // adding a convenient endpoint for it: the server's route table must not mention a reset at
        // all, and the only entry point is `SafeTubeReset.wipe`, called from the TV's own screen.
        val routes = File("src/main/java/tv/safetubeforkids/app/server").listFiles()
            ?.filter { it.extension == "kt" }
            ?.joinToString("\n") { it.readText() }
            ?: ""

        assertTrue("the route sources are where this test expects them", routes.isNotEmpty())
        assertFalse("no route may wipe anything", routes.contains("SafeTubeReset"))
        listOf("reset", "wipe", "erase").forEach { word ->
            assertFalse(
                "no route path mentions '$word'",
                Regex("""(get|post|put|delete)\("/[^"]*$word""", RegexOption.IGNORE_CASE).containsMatchIn(routes),
            )
        }
    }
}
