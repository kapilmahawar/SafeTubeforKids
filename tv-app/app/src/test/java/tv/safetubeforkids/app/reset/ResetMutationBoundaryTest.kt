package tv.safetubeforkids.app.reset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The guard that keeps the destructive wipe behind a credential.
 *
 * The W13.1b audit found the opposite of what this test asserts: `SafeTubeReset.wipe(context)` was
 * public, took no credential, and was reachable by any caller that could name it, with the phrase screen
 * as the only thing in the way. That is now a type-level precondition - the wipe takes a
 * `ResetAuthorization`, and the only thing that mints one is `ResetGate` after verifying a Parent PIN or
 * a Recovery Code - and this reads the sources to make sure it stays that way.
 *
 * It is the same kind of check the repository already uses for its other boundaries
 * (`SafeTubeResetTest.noUnauthenticatedRouteCanTriggerTheWipe`, `SettingsParentGateTest`), and it fails
 * for the honest reason: somebody added a way around the credential check.
 */
class ResetMutationBoundaryTest {

    private val appRoot = File("src/main/java/tv/safetubeforkids/app")

    /**
     * Code only, without comments: the documentation of this change names the call it replaced, and a
     * scan that counted comment text would report that as a call site.
     */
    private fun code(text: String): String = text.lineSequence()
        .filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
        }
        .joinToString("\n")

    private fun sources(): Map<String, String> {
        assertTrue("the main sources are where this test expects them", appRoot.isDirectory)
        return appRoot.walkTopDown()
            .filter { it.extension == "kt" }
            .associate { it.relativeTo(appRoot).path.replace(File.separatorChar, '/') to code(it.readText()) }
    }

    @Test
    fun `the destructive wipe requires a credential proof in its signature`() {
        val declaration = sources()["reset/SafeTubeReset.kt"]
        assertTrue("SafeTubeReset.kt is where this test expects it", declaration != null)

        assertTrue(
            "wipe() must take a ResetAuthorization, or every caller is authorized by definition",
            Regex("""suspend fun wipe\([^)]*authorization:\s*ResetAuthorization""").containsMatchIn(declaration!!),
        )
    }

    @Test
    fun `only the reset screen and the debug instrument can call the wipe`() {
        val callers = sources()
            .filter { (path, text) -> path != "reset/SafeTubeReset.kt" && text.contains("SafeTubeReset.wipe(") }
            .keys
            .toSortedSet()

        assertEquals(
            "a new caller of the wipe needs a reason, a credential, and a test",
            setOf("debug/DebugReceiver.kt", "ui/screens/ResetSafeTubeScreen.kt"),
            callers,
        )
    }

    @Test
    fun `only the gate can mint an authorization`() {
        val minting = sources()
            .filter { (_, text) -> text.contains("ResetAuthorization(") }
            .keys
            .toSortedSet()

        assertEquals(
            "a proof must come from ResetGate, which verifies a credential, and nowhere else",
            setOf("auth/ResetGate.kt"),
            minting,
        )
    }

    @Test
    fun `the debug instrument presents a credential rather than bypassing the gate`() {
        // A test instrument is not a reason to keep a door open. It reads a `pin` extra and puts it
        // through the same gate the screen uses, so there is no path anywhere from "no credential" to a
        // wiped television.
        val debug = sources()["debug/DebugReceiver.kt"]
        assertTrue("DebugReceiver.kt is where this test expects it", debug != null)
        assertTrue("the instrument must require a PIN", debug!!.contains("""getStringExtra("pin")"""))
        assertTrue("and must authorize through the gate", debug.contains("resetGate.authorizeWithPin"))
    }

    @Test
    fun `the credential store is only cleared by the wipe and the debug instrument`() {
        // `PinManager.clearAll` is the wipe's own door and stays unauthenticated by design - it is called
        // *inside* the wipe, after the credential has been verified, because clearing the store is part of
        // what a reset is. It must not acquire a third caller.
        val callers = sources()
            .filter { (_, text) -> text.contains("pinManager.clearAll()") }
            .keys
            .toSortedSet()

        assertEquals(
            "clearing the parent credential is the wipe's business, and the debug instrument's",
            setOf("debug/DebugReceiver.kt", "reset/SafeTubeReset.kt"),
            callers,
        )
    }
}
