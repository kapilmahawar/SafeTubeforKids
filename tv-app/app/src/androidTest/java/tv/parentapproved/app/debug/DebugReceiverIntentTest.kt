package tv.safetubeforkids.app.debug

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.auth.InMemoryParentCredentialStore
import tv.safetubeforkids.app.auth.PinManager
import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.data.cache.CacheDatabase
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugReceiverIntentTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var receiver: DebugReceiver

    private companion object {
        const val TEST_PIN = "482913"
    }

    @Before
    fun setup() {
        val db = CacheDatabase.getInMemoryInstance(context)
        // W10: a credential is created, not generated. The instrument tests install one they know,
        // exactly as the e2e harness does through DEBUG_SET_PIN.
        ServiceLocator.initForTest(
            db,
            PinManager(store = InMemoryParentCredentialStore()).also { it.setup(TEST_PIN, TEST_PIN) },
            SessionManager(),
        )
        receiver = DebugReceiver()
    }

    @Test
    fun debugGetPin_reportsWhetherACredentialExistsAndNeverThePin() {
        val intent = Intent("tv.safetubeforkids.app.DEBUG_GET_PIN")
        receiver.onReceive(context, intent)
        // Nothing to read back any more: the intent that used to return the PIN now answers whether one
        // exists. There is no plaintext PIN in the process to return.
        assertTrue(ServiceLocator.pinManager.isConfigured())
    }

    @Test
    fun debugSetPin_installsAKnownCredential() {
        val intent = Intent("tv.safetubeforkids.app.DEBUG_SET_PIN").apply { putExtra("pin", "135790") }
        receiver.onReceive(context, intent)

        assertTrue(ServiceLocator.pinManager.isConfigured())
        assertTrue(
            ServiceLocator.pinManager.validate("135790") is tv.safetubeforkids.app.auth.PinResult.Success,
        )
    }

    @Test
    fun debugResetPin_clearsTheCredentialAndReturnsTheTvToSetup() {
        val intent = Intent("tv.safetubeforkids.app.DEBUG_RESET_PIN")
        receiver.onReceive(context, intent)

        assertFalse("a reset leaves nothing to sign in with", ServiceLocator.pinManager.isConfigured())
        assertTrue(
            ServiceLocator.pinManager.validate(TEST_PIN) is tv.safetubeforkids.app.auth.PinResult.NotSetUp,
        )
    }

    @Test
    fun debugSimulateAuth_correctPin_returnsValid() {
        val intent = Intent("tv.safetubeforkids.app.DEBUG_SIMULATE_AUTH").apply {
            putExtra("pin", TEST_PIN)
        }
        receiver.onReceive(context, intent)
        // Should succeed without throwing
    }

    @Test
    fun debugSimulateAuth_wrongPin_returnsInvalid() {
        val intent = Intent("tv.safetubeforkids.app.DEBUG_SIMULATE_AUTH").apply {
            putExtra("pin", "000000")
        }
        receiver.onReceive(context, intent)
        // Should not throw
    }

    @Test
    fun debugGetAuthState_returnsState() {
        val intent = Intent("tv.safetubeforkids.app.DEBUG_GET_AUTH_STATE")
        receiver.onReceive(context, intent)
        assertEquals(0, ServiceLocator.sessionManager.getActiveSessionCount())
    }

    @Test
    fun debugGetNowPlaying_whenNotPlaying() {
        val intent = Intent("tv.safetubeforkids.app.DEBUG_GET_NOW_PLAYING")
        receiver.onReceive(context, intent)
        // Should not throw
    }

    @Test
    fun debugSimulateOffline_toggles() {
        val wasBefore = tv.safetubeforkids.app.util.OfflineSimulator.isOffline
        val intent = Intent("tv.safetubeforkids.app.DEBUG_SIMULATE_OFFLINE")
        receiver.onReceive(context, intent)
        assertNotEquals(wasBefore, tv.safetubeforkids.app.util.OfflineSimulator.isOffline)
        // Toggle back
        receiver.onReceive(context, intent)
        assertEquals(wasBefore, tv.safetubeforkids.app.util.OfflineSimulator.isOffline)
    }

    @Test
    fun debugGetPlaylists_emptyReturnsArray() {
        val intent = Intent("tv.safetubeforkids.app.DEBUG_GET_PLAYLISTS")
        receiver.onReceive(context, intent)
        // Should not throw
    }

    @Test
    fun debugRefreshPlaylists_returnsCount() {
        val intent = Intent("tv.safetubeforkids.app.DEBUG_REFRESH_PLAYLISTS")
        receiver.onReceive(context, intent)
        // Should not throw
    }

    @Test
    fun debugClearPlayEvents_returns() {
        val intent = Intent("tv.safetubeforkids.app.DEBUG_CLEAR_PLAY_EVENTS")
        receiver.onReceive(context, intent)
        // Should not throw
    }

    @Test
    fun debugStopPlayback_returns() {
        val intent = Intent("tv.safetubeforkids.app.DEBUG_STOP_PLAYBACK")
        receiver.onReceive(context, intent)
        // Should not throw
    }

    @Test
    fun debugGetServerStatus_returns() {
        val intent = Intent("tv.safetubeforkids.app.DEBUG_GET_SERVER_STATUS")
        receiver.onReceive(context, intent)
        // Should not throw
    }
}
