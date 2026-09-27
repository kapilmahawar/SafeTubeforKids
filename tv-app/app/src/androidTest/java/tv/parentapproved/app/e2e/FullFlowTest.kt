package tv.safetubeforkids.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.auth.InMemoryParentCredentialStore
import tv.safetubeforkids.app.auth.PinManager
import tv.safetubeforkids.app.auth.PinResult
import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.ChannelEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullFlowTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: CacheDatabase

    private companion object {
        const val TEST_PIN = "482913"
    }

    @Before
    fun setup() {
        db = CacheDatabase.getInMemoryInstance(context)
        // A PIN authenticates only if something can issue a session for it. A callback-less PinManager
        // now fails closed (PinResult.NotConfigured) rather than reporting a success with an empty
        // token, so the test wires the same session-issuing callback production wires in
        // ServiceLocator.init - a real SessionManager, not a stand-in token.
        val sessionManager = SessionManager()
        ServiceLocator.initForTest(
            db = db,
            pin = PinManager(
                store = InMemoryParentCredentialStore(),
                onPinValidated = { sessionManager.createSession() ?: "" },
            ).also { it.setup(TEST_PIN, TEST_PIN) },
            session = sessionManager,
        )
    }

    @Test
    fun e2e_authThenAddSource_fullCycle() = runBlocking {
        // The contract this flow depends on, asserted here so the flow cannot silently revert to the
        // old expectation: a correct PIN authenticates only when a session can be issued for it, and a
        // PIN on a device that has no credential at all authenticates nothing whatever it is.
        val unconfigured = PinManager(store = InMemoryParentCredentialStore())
        val unconfiguredResult = unconfigured.validate(TEST_PIN)
        assertTrue(
            "a device with no Parent PIN must fail closed, not authenticate",
            unconfiguredResult is PinResult.NotSetUp,
        )

        val noSession = PinManager(store = InMemoryParentCredentialStore()).also { it.setup(TEST_PIN, TEST_PIN) }
        assertTrue(
            "and a correct PIN with no session-issuing callback must not authenticate either",
            noSession.validate(TEST_PIN) is PinResult.NotConfigured,
        )

        // Authenticate
        val result = ServiceLocator.pinManager.validate(TEST_PIN)
        assertTrue(result is PinResult.Success)
        val issuedToken = (result as PinResult.Success).token
        assertTrue("the callback must have issued a real session", issuedToken.isNotBlank())
        assertTrue(
            "the issued token must be a session this SessionManager knows",
            ServiceLocator.sessionManager.validateSession(issuedToken),
        )

        // Create session
        val token = ServiceLocator.sessionManager.createSession()
        assertNotNull(token)
        assertTrue(ServiceLocator.sessionManager.validateSession(token!!))

        // Add source
        val entity = ChannelEntity(sourceType = "yt_playlist", sourceId = "PLtest123", sourceUrl = "https://www.youtube.com/playlist?list=PLtest123", displayName = "Test Playlist")
        val id = db.channelDao().insert(entity)
        assertTrue(id > 0)

        // List sources
        val channels = db.channelDao().getAll()
        assertEquals(1, channels.size)
        assertEquals("PLtest123", channels[0].sourceId)

        // Delete source
        db.channelDao().deleteById(id)
        assertEquals(0, db.channelDao().count())
    }

    @Test
    fun e2e_addSourceViaDao_verifyInDb() = runBlocking {
        db.channelDao().insert(ChannelEntity(sourceType = "yt_playlist", sourceId = "PLabc", sourceUrl = "url1", displayName = "ABC"))
        db.channelDao().insert(ChannelEntity(sourceType = "yt_video", sourceId = "vid1", sourceUrl = "url2", displayName = "DEF"))

        val all = db.channelDao().getAll()
        assertEquals(2, all.size)

        val found = db.channelDao().getBySourceId("PLabc")
        assertNotNull(found)
        assertEquals("ABC", found!!.displayName)
    }

    @Test
    fun e2e_fullReset_clearsEverything() = runBlocking {
        // Add some data
        db.channelDao().insert(ChannelEntity(sourceType = "yt_playlist", sourceId = "PL1", sourceUrl = "url1", displayName = "P1"))
        db.channelDao().insert(ChannelEntity(sourceType = "yt_playlist", sourceId = "PL2", sourceUrl = "url2", displayName = "P2"))
        tv.safetubeforkids.app.data.events.PlayEventRecorder.init(db)
        db.playEventDao().insert(tv.safetubeforkids.app.data.events.PlayEventEntity(
            videoId = "v1", playlistId = "PL1", startedAt = System.currentTimeMillis()
        ))

        // Full reset. W10: the credential is cleared rather than replaced by a new random PIN - there
        // is no "reset to a PIN you can read" any more, which is the point of a persistent credential.
        db.channelDao().deleteAll()
        db.playEventDao().deleteAll()
        ServiceLocator.pinManager.clearAll()
        ServiceLocator.sessionManager.invalidateAll()

        assertEquals(0, db.channelDao().count())
        assertEquals(0, db.playEventDao().count())
        assertEquals(0, ServiceLocator.sessionManager.getActiveSessionCount())
        assertFalse("and the TV is back to first run", ServiceLocator.pinManager.isConfigured())
    }

    @Test
    fun e2e_offlineMode_togglesCorrectly() {
        assertFalse(tv.safetubeforkids.app.util.OfflineSimulator.isOffline)
        tv.safetubeforkids.app.util.OfflineSimulator.toggle()
        assertTrue(tv.safetubeforkids.app.util.OfflineSimulator.isOffline)
        tv.safetubeforkids.app.util.OfflineSimulator.toggle()
        assertFalse(tv.safetubeforkids.app.util.OfflineSimulator.isOffline)
    }
}
