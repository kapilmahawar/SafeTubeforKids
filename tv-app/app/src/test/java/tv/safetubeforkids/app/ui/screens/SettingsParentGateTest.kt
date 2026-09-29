package tv.safetubeforkids.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The guard against the W13.1 defect coming back.
 *
 * The thing that was wrong was not a missing check in the abstract - it was a screen that reached into
 * a singleton and deleted rows itself:
 *
 * ```kotlin
 * SettingsBtn("Clear Events") { PlayEventRecorder.clearAll() }
 * SettingsBtn("Sign Out All Sessions") { ServiceLocator.sessionManager.invalidateAll() }
 * ```
 *
 * A unit test on `ParentGate` cannot see that, because that call never went through a gate. So this
 * reads the sources: the destructive mutations must have exactly one call site each, that call site
 * must be the wiring that puts them behind the gate, and the Settings screen must offer them only
 * through the prompt that asks for the Parent PIN.
 *
 * It is the same kind of check the repository already uses for the server's route table
 * (`SafeTubeResetTest.noUnauthenticatedRouteCanTriggerTheWipe`), and it fails for the honest reason:
 * somebody reintroduced a direct call.
 */
class SettingsParentGateTest {

    private val appRoot = File("src/main/java/tv/safetubeforkids/app")

    private fun source(relative: String): String {
        val file = File(appRoot, relative)
        assertTrue("the source this test guards is where it expects it: ${file.path}", file.isFile)
        return file.readText()
    }

    /**
     * Code only, without comments.
     *
     * `ParentGate`'s own documentation names the call it replaced, and a scan that counted comment text
     * would report that as a call site - which is exactly the sort of false positive that gets a guard
     * test deleted instead of trusted.
     */
    private fun code(text: String): String = text.lineSequence()
        .filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
        }
        .joinToString("\n")

    private fun allSources(): Map<String, String> {
        assertTrue("the main sources are where this test expects them", appRoot.isDirectory)
        return appRoot.walkTopDown()
            .filter { it.extension == "kt" }
            // Forward slashes so the expectations below are the same on every machine.
            .associate { it.relativeTo(appRoot).path.replace(File.separatorChar, '/') to code(it.readText()) }
    }

    @Test
    fun `the settings screen cannot clear the watch history itself`() {
        val settings = source("ui/screens/SettingsScreen.kt")

        assertTrue(
            "the watch history must not be cleared from the screen: it is what the time limits count",
            !settings.contains("PlayEventRecorder.clearAll"),
        )
        assertTrue(
            "and parent sessions must not be invalidated from the screen either",
            !settings.contains("invalidateAll"),
        )
    }

    @Test
    fun `the settings screen offers the parent-only actions through the pin prompt`() {
        val settings = source("ui/screens/SettingsScreen.kt")

        assertTrue(
            "the two gated actions must be opened through the prompt, not performed",
            settings.contains("ParentPinPrompt("),
        )
        listOf("RESET_WATCH_TIME", "SIGN_OUT_SESSIONS").forEach { action ->
            assertTrue("$action must be requested from the settings screen", settings.contains(action))
        }
    }

    @Test
    fun `the prompt performs nothing by itself but asks the gate`() {
        val prompt = source("ui/components/ParentPinPrompt.kt")

        assertTrue(
            "the prompt must hand the PIN to the gate, which is the only thing that performs",
            prompt.contains("parentGate.run"),
        )
        assertTrue(
            "the prompt must not reach for a mutation or a store of its own",
            !prompt.contains("PlayEventRecorder") && !prompt.contains("invalidateAll"),
        )
    }

    @Test
    fun `the watch-history reset has exactly one call site, and it is the gate's wiring`() {
        val callers = allSources()
            .filter { (_, text) -> text.contains("PlayEventRecorder.clearAll()") }
            .keys
            .toSortedSet()

        assertEquals(
            "the only caller of the watch-history reset must be the wiring that puts it behind the PIN",
            setOf("ServiceLocator.kt"),
            callers,
        )
    }

    @Test
    fun `every route to invalidating parent sessions is authenticated`() {
        // `invalidateAll` on the session manager is legitimate in five places, and each one is either
        // credential-gated or deliberate: the credential-change hook and the gate's own wiring
        // (ServiceLocator), the recovery screen (which asks for the current PIN first), the dashboard's
        // sign-out route (which requires a session), the destructive reset (the wipe), and the debug
        // receiver (debug builds only). A new, unauthenticated one is the defect.
        val callers = allSources()
            .filter { (_, text) -> text.contains("sessionManager.invalidateAll()") }
            .keys
            .toSortedSet()

        assertEquals(
            "a new caller of invalidateAll needs a reason and a test, not just a button",
            setOf(
                "ServiceLocator.kt",
                "debug/DebugReceiver.kt",
                "reset/SafeTubeReset.kt",
                "server/AuthRoutes.kt",
                "ui/screens/RecoveryCodeScreen.kt",
            ),
            callers,
        )
    }
}
