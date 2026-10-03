package tv.safetubeforkids.app.debug

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.auth.InMemoryParentCredentialStore
import tv.safetubeforkids.app.auth.PinManager
import tv.safetubeforkids.app.auth.PinResult
import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.data.cache.CacheDatabase

/**
 * The two debug intents that *replace* or *clear* a parent credential, kept out of
 * [DebugReceiverIntentTest] on purpose (W13.8).
 *
 * They are not destructive to the installation as written - the fixture below installs an in-memory
 * credential store and an in-memory database, so the receiver mutates the test's own store and never the
 * device's `SharedPreferences`, and a suite run against the family Mi Box left its PIN intact. That
 * protection is implicit, though: drop one line from the fixture and `DEBUG_RESET_PIN` starts clearing a real
 * credential. So these two tests are separated, named for what they do, and they only run when the caller
 * opts in explicitly. Without the argument they are *skipped*, which is visible in the report; they are not
 * silently turned into passes and not deleted.
 *
 * Run them only against a disposable installation (an emulator, or a TV you are willing to set up again):
 *
 * ```text
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=tv.safetubeforkids.app.debug.CredentialTokenInstrumentedTest \
 *   -Pandroid.testInstrumentationRunnerArguments.runDestructiveCredentialTests=true
 * ```
 *
 * The default `connectedDebugAndroidTest` never runs them: `scripts/check-destructive-instrumentation.test.js`
 * fails the build if a credential-replacing intent appears anywhere else in the instrumentation sources.
 */
@RunWith(AndroidJUnit4::class)
class CredentialTokenInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var receiver: DebugReceiver

    private companion object {
        const val TEST_PIN = "482913"

        /** The opt-in argument; anything other than "true" leaves these tests skipped. */
        const val OPT_IN_ARGUMENT = "runDestructiveCredentialTests"
    }

    @Before
    fun requireOptInAndInstallAnIsolatedCredential() {
        assumeTrue(
            "DEBUG_SET_PIN and DEBUG_RESET_PIN replace or clear a parent credential. They are skipped " +
                "unless explicitly opted in with -Pandroid.testInstrumentationRunnerArguments." +
                "$OPT_IN_ARGUMENT=true, and they should only ever be pointed at a disposable installation.",
            InstrumentationRegistry.getArguments().getString(OPT_IN_ARGUMENT) == "true",
        )

        val db = CacheDatabase.getInMemoryInstance(context)
        val sessions = SessionManager()
        ServiceLocator.initForTest(
            db,
            PinManager(
                store = InMemoryParentCredentialStore(),
                onPinValidated = { sessions.createSession() ?: "" },
            ).also { it.setup(TEST_PIN, TEST_PIN) },
            sessions,
        )
        receiver = DebugReceiver()
    }

    @Test
    fun debugSetPin_installsAKnownCredential() {
        val intent = Intent("tv.safetubeforkids.app.DEBUG_SET_PIN").apply { putExtra("pin", "135790") }
        receiver.onReceive(context, intent)

        assertTrue(ServiceLocator.pinManager.isConfigured())
        // The result is named in the message: an earlier version of this test failed with a bare
        // AssertionError, which left the actual answer - no session issuer in the fixture - to be guessed
        // at twice.
        val result = ServiceLocator.pinManager.validate("135790")
        assertTrue(
            "the PIN the receiver installed should sign in, but validate answered $result",
            result is PinResult.Success,
        )
    }

    @Test
    fun debugResetPin_clearsTheCredentialAndReturnsTheTvToSetup() {
        val intent = Intent("tv.safetubeforkids.app.DEBUG_RESET_PIN")
        receiver.onReceive(context, intent)

        assertFalse("a reset leaves nothing to sign in with", ServiceLocator.pinManager.isConfigured())
        assertTrue(ServiceLocator.pinManager.validate(TEST_PIN) is PinResult.NotSetUp)
    }
}
