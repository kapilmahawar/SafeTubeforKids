package tv.safetubeforkids.app.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardAssetTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun indexHtml_existsInAssets() {
        val assets = context.assets.list("") ?: emptyArray()
        assertTrue("index.html should exist in assets", assets.contains("index.html"))
    }

    @Test
    fun indexHtml_containsTheAppShell() {
        val html = context.assets.open("index.html").bufferedReader().readText()
        assertTrue("index.html should have the screen outlet", html.contains("id=\"view\""))
        assertTrue("index.html should have the two sections", html.contains("data-action=\"go-library\""))
        assertTrue("index.html should have the theme control", html.contains("data-action=\"toggle-theme\""))
        assertTrue("index.html should load the scripts the shell needs", html.contains("catalog-editor.js"))
        assertTrue("index.html should load the catalog-file model", html.contains("catalog-yaml.js"))
        assertTrue("index.html should load the parent-access model", html.contains("parent-access.js"))
        assertFalse("and no asset offers a first-run setup step from the browser", html.contains("?pin="))
    }

    @Test
    fun indexHtml_carriesNoInlineHandler() {
        val html = context.assets.open("index.html").bufferedReader().readText()
        // The server's content policy no longer exempts inline script, so one would be dead markup.
        assertFalse("index.html must not carry an inline handler",
            Regex("""\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html))
    }

    @Test
    fun themeJs_existsInAssets() {
        val assets = context.assets.list("") ?: emptyArray()
        assertTrue("theme.js should exist in assets", assets.contains("theme.js"))
    }

    @Test
    fun catalogYamlJs_existsInAssets() {
        val assets = context.assets.list("") ?: emptyArray()
        assertTrue("catalog-yaml.js should exist in assets", assets.contains("catalog-yaml.js"))
    }

    @Test
    fun parentAccessJs_existsInAssets() {
        val assets = context.assets.list("") ?: emptyArray()
        assertTrue("parent-access.js should exist in assets", assets.contains("parent-access.js"))
    }

    /**
     * The dashboard must not be able to reach the TV's destructive reset, and must not carry a
     * credential of its own: the reset is a screen on the television, and the Parent PIN is a verifier
     * the TV holds. Both are checked against the shipped assets rather than the sources they came from.
     */
    @Test
    fun noAssetCanWipeTheTvOrHoldACredential() {
        val files = listOf("index.html", "app.js", "parent-access.js", "catalog-yaml.js")
        files.forEach { name ->
            val source = context.assets.open(name).bufferedReader().readText()
            listOf("/reset", "SafeTubeReset", "localStorage.setItem('pin", "sessionStorage")
                .forEach { forbidden ->
                    assertFalse(
                        "$name must not carry a wipe route or a stored credential: $forbidden",
                        source.contains(forbidden),
                    )
                }
        }
    }

    @Test
    fun catalogYamlJs_carriesNoAuthorization() {
        val js = context.assets.open("catalog-yaml.js").bufferedReader().readText()
        // The file describes the child's library. Permission lives in the allowed sources, so an
        // imported file can never be a way to let something new play.
        listOf("/playlists", "/channels", "/sources", "approve").forEach { forbidden ->
            assertFalse(
                "catalog-yaml.js must not be able to allow anything: $forbidden",
                js.contains(forbidden, ignoreCase = true),
            )
        }
    }

    @Test
    fun theTreeRendererIsGone() {
        val assets = context.assets.list("") ?: emptyArray()
        assertFalse("catalog-tree.js was replaced by the library screens", assets.contains("catalog-tree.js"))
    }

    @Test
    fun appJs_containsAuthFunction() {
        val js = context.assets.open("app.js").bufferedReader().readText()
        assertTrue("app.js should contain auth logic", js.contains("/auth"))
    }

    @Test
    fun noAssetManagesAppsOrKioskAnyMore() {
        val files = listOf("index.html", "app.js", "style.css", "theme.js", "catalog-yaml.js", "parent-access.js")
        files.forEach { name ->
            val source = context.assets.open(name).bufferedReader().readText()
            listOf("kiosk", "Installed Apps", "app_whitelist", "/apps").forEach { forbidden ->
                assertFalse(
                    "$name still manages apps or kiosk: $forbidden",
                    source.contains(forbidden, ignoreCase = true),
                )
            }
        }
    }
}
