package tv.safetubeforkids.app.auth

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.ChannelEntity
import tv.safetubeforkids.app.data.cache.VideoEntity
import tv.safetubeforkids.app.data.catalog.CatalogMetadataEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeEntity
import tv.safetubeforkids.app.data.catalog.CatalogNodeType
import tv.safetubeforkids.app.data.catalog.ThumbnailMode
import tv.safetubeforkids.app.playback.PlaybackApproval
import tv.safetubeforkids.app.playback.PlaybackAuthorization
import tv.safetubeforkids.app.server.FileCatalogStore

/**
 * The invariant W10 must not break: **a parent's credential is not permission.**
 *
 * Playback authorization is one question - is this video in the approved cache, and is its source
 * still approved? - answered from two tables by [PlaybackAuthorization] and nothing else. Parent
 * access is a different question entirely, and this suite proves they do not touch by doing every
 * parent-access thing there is to do and checking, before and after, that the answer to the playback
 * question has not moved:
 *
 *  - create a Parent PIN          - sign in (get a session)
 *  - verify a Recovery Code       - reset the PIN with it
 *  - change the PIN               - rotate the Recovery Code
 *  - wipe everything (destructive reset)
 *
 * The last one is the interesting case, and it is honest about what it means: the destructive reset
 * *does* remove the approval data, because the approved sources are part of what the forgotten
 * credential was protecting - that is the whole reason the reset is destructive. What it does not do
 * is *grant* anything: an unapproved video is still refused afterwards, and a video that was refused
 * before is never allowed by any credential operation.
 *
 * This is also where the upgrade path is pinned: an installation with a library and approved sources
 * that predates W10 has no persistent credential, gets one-time setup, and keeps everything else.
 */
@RunWith(RobolectricTestRunner::class)
class ParentAccessSecurityTest {

    private lateinit var db: CacheDatabase
    private lateinit var pins: PinManager

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        pins = PinManager(store = InMemoryParentCredentialStore(), hasher = TestHasher)
        pins.setup(TEST_PIN, TEST_PIN)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun approveAPlaylistWith(videoIds: List<String>) {
        db.channelDao().insert(
            ChannelEntity(sourceType = "yt_playlist", sourceId = "PLapproved", sourceUrl = "u", displayName = "Songs")
        )
        db.videoDao().insertAll(
            videoIds.mapIndexed { index, id ->
                VideoEntity(videoId = id, playlistId = "PLapproved", title = "Video $index", thumbnailUrl = "", durationSeconds = 0, position = index)
            }
        )
    }

    /** The playback question, asked exactly the way the player asks it. */
    private suspend fun mayPlay(videoId: String): Boolean =
        PlaybackAuthorization.authorize(db, videoId) is PlaybackApproval.Approved

    // --- credentials do not authorize -----------------------------------------------------------------

    @Test
    fun creatingAParentPinDoesNotApproveAnything() = runBlocking {
        approveAPlaylistWith(listOf("approved-1"))
        val before = mayPlay("unapproved-1")

        val fresh = PinManager(store = InMemoryParentCredentialStore(), hasher = TestHasher)
        fresh.setup(TEST_PIN, TEST_PIN)

        assertFalse("an unapproved video is still unapproved after setup", mayPlay("unapproved-1"))
        assertEquals("and the answer did not change at all", before, mayPlay("unapproved-1"))
        assertTrue("while the approved one is still approved", mayPlay("approved-1"))
    }

    @Test
    fun signingInDoesNotApproveAnything() = runBlocking {
        approveAPlaylistWith(listOf("approved-1"))
        val sessions = SessionManager()
        val wired = PinManager(
            store = InMemoryParentCredentialStore(),
            onPinValidated = { sessions.createSession() ?: "" },
            hasher = TestHasher,
        )
        wired.setup(TEST_PIN, TEST_PIN)

        val result = wired.validate(TEST_PIN)
        val token = (result as PinResult.Success).token

        assertTrue("the session is real", sessions.validateSession(token))
        assertFalse("but it approves nothing", mayPlay("unapproved-1"))
        assertEquals("the approved cache is untouched", 1, db.channelDao().count())
    }

    @Test
    fun aRecoveryCodeDoesNotApproveAnything() = runBlocking {
        approveAPlaylistWith(listOf("approved-1"))
        val code = pins.pendingRecoveryCode()!!

        assertTrue(pins.verifyRecoveryCode(code) is RecoveryCheckResult.Verified)

        assertFalse(mayPlay("unapproved-1"))
        assertTrue(mayPlay("approved-1"))
    }

    @Test
    fun changingThePinDoesNotTouchTheApprovedSourcesOrTheLibrary() = runBlocking {
        approveAPlaylistWith(listOf("approved-1"))
        ServiceLocator.initForTest(
            db = db,
            pin = pins,
            session = SessionManager(),
            catalog = FileCatalogStore.inFilesDir(RuntimeEnvironment.getApplication().filesDir),
        )
        ServiceLocator.catalogStore.write(
            listOf(
                tv.safetubeforkids.app.data.catalog.CatalogNodeDto(
                    id = "cat-1", parentId = null, nodeType = "CATEGORY", title = "Cartoon",
                    position = 0, enabled = true,
                )
            )
        )
        val catalogBefore = ServiceLocator.catalogStore.read().nodes.size

        assertTrue(pins.changePin(TEST_PIN, "654321", "654321") is PinChangeResult.Changed)

        assertTrue("approved sources survive a PIN change", mayPlay("approved-1"))
        assertFalse("and nothing new is approved", mayPlay("unapproved-1"))
        assertEquals("the library survives a PIN change", catalogBefore, ServiceLocator.catalogStore.read().nodes.size)
    }

    @Test
    fun resettingThePinWithARecoveryCodeDoesNotTouchThemEither() = runBlocking {
        approveAPlaylistWith(listOf("approved-1"))
        val code = pins.pendingRecoveryCode()!!

        assertTrue(pins.resetWithRecovery(code, "654321", "654321") is PinResetResult.Reset)

        assertTrue("approved sources survive a PIN reset", mayPlay("approved-1"))
        assertFalse("and the reset approved nothing", mayPlay("unapproved-1"))
        assertEquals("the source row is untouched", 1, db.channelDao().count())
        assertEquals("and so are its videos", 1, db.videoDao().count())
    }

    @Test
    fun rotatingTheRecoveryCodeDoesNotTouchThem() = runBlocking {
        approveAPlaylistWith(listOf("approved-1"))

        pins.rotateRecoveryCode()

        assertTrue(mayPlay("approved-1"))
        assertFalse(mayPlay("unapproved-1"))
    }

    @Test
    fun noCredentialIsConsultedByPlaybackAuthorizationAtAll() = runBlocking {
        // The structural version of the same claim: authorization reads two tables and cannot see a
        // credential, so there is no code path for one to influence it through.
        val source = java.io.File("src/main/java/tv/safetubeforkids/app/playback/PlaybackAuthorization.kt").readText()

        listOf("pin", "Pin", "session", "Session", "recovery", "Recovery", "token", "Token").forEach { forbidden ->
            assertFalse(
                "PlaybackAuthorization must not know about $forbidden",
                source.contains(forbidden),
            )
        }
    }

    // --- the catalog is not the credential either -----------------------------------------------------

    @Test
    fun aCatalogEntryForAnUnapprovedVideoStillPlaysNothing() = runBlocking {
        // W9's invariant, re-asserted from W10's side: adding something to the library - by hand, by
        // import, or by a file - is configuration, and configuration is not permission.
        approveAPlaylistWith(listOf("approved-1"))
        db.catalogNodeDao().insert(
            CatalogNodeEntity(
                id = "cat-1", parentId = null, nodeType = CatalogNodeType.CATEGORY, title = "Cartoon",
                position = 0, enabled = true, youtubeVideoId = null, youtubePlaylistId = null,
                thumbnailMode = ThumbnailMode.AUTO, thumbnailVideoId = null, thumbnailUrl = null,
                createdAt = 1L, updatedAt = 1L,
            )
        )
        db.catalogNodeDao().insert(
            CatalogNodeEntity(
                id = "vid-unapproved", parentId = "cat-1", nodeType = CatalogNodeType.VIDEO, title = "Not approved",
                position = 0, enabled = true, youtubeVideoId = "unapproved-1", youtubePlaylistId = null,
                thumbnailMode = ThumbnailMode.AUTO, thumbnailVideoId = null, thumbnailUrl = null,
                createdAt = 1L, updatedAt = 1L,
            )
        )

        assertFalse("a library entry grants nothing", mayPlay("unapproved-1"))
        assertTrue("while the approved video is unaffected", mayPlay("approved-1"))
    }

    // --- the upgrade path -----------------------------------------------------------------------------

    @Test
    fun anExistingInstallationKeepsEverythingAndGetsOneTimeSetup() = runBlocking {
        // What W10 does to a TV that has been in use since before it: the library, the approved
        // sources, the watch history and the settings are all there, and the only new thing is that
        // there is no persistent Parent PIN yet - so the next launch runs the one-time setup and
        // nothing else changes.
        approveAPlaylistWith(listOf("approved-1", "approved-2"))
        db.catalogNodeDao().insert(
            CatalogNodeEntity(
                id = "cat-1", parentId = null, nodeType = CatalogNodeType.CATEGORY, title = "Cartoon",
                position = 0, enabled = true, youtubeVideoId = null, youtubePlaylistId = null,
                thumbnailMode = ThumbnailMode.AUTO, thumbnailVideoId = null, thumbnailUrl = null,
                createdAt = 1L, updatedAt = 1L,
            )
        )
        db.catalogMetadataDao().upsert(CatalogMetadataEntity(catalogVersion = 42L))

        val upgrading = PinManager(store = InMemoryParentCredentialStore(), hasher = TestHasher)
        assertFalse("an upgrade has no parent credential yet", upgrading.isConfigured())

        val setup = upgrading.setup("135790", "135790")

        assertTrue("so it gets the one-time setup", setup is PinSetupResult.Created)
        assertTrue(upgrading.isConfigured())
        assertEquals("and the library is still there", 42L, db.catalogMetadataDao().get()!!.catalogVersion)
        assertEquals("and the approved sources", 1, db.channelDao().count())
        assertEquals("and their videos", 2, db.videoDao().count())
        assertTrue("which still play", mayPlay("approved-1"))
        assertFalse("and nothing else does", mayPlay("unapproved-1"))
    }

    // --- the destructive reset's effect on authorization ----------------------------------------------

    @Test
    fun theDestructiveResetRemovesTheApprovalDataBecauseThatIsWhatItIsFor() = runBlocking {
        // Stated plainly rather than glossed over: the reset removes the approved sources, because a
        // reset that kept them would be a way to read a child's library without the credential. What
        // it must never do is leave the TV able to play something it could not play before.
        approveAPlaylistWith(listOf("approved-1"))
        assertTrue(mayPlay("approved-1"))

        ServiceLocator.initForTest(
            db = db,
            pin = pins,
            session = SessionManager(),
            catalog = FileCatalogStore.inFilesDir(RuntimeEnvironment.getApplication().filesDir),
        )
        tv.safetubeforkids.app.reset.SafeTubeReset.wipe(RuntimeEnvironment.getApplication())

        assertFalse("after a wipe, even the previously approved video plays nothing", mayPlay("approved-1"))
        assertFalse("and an unapproved one still plays nothing", mayPlay("unapproved-1"))
        assertFalse("and the TV has no credential at all", pins.isConfigured())
    }
}
