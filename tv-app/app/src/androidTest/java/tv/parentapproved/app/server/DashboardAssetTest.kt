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
        val files = listOf("index.html", "app.js", "style.css", "theme.js")
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
