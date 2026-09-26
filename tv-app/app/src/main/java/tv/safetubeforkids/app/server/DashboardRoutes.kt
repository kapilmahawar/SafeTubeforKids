package tv.safetubeforkids.app.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * The policy the dashboard ships under.
 *
 * `img-src` names both YouTube artwork hosts (`img.youtube.com` and `i.ytimg.com`): a card's picture
 * is a url the TV's approved cache supplied, and YouTube hands those out from either host.
 *
 * `script-src` no longer carries `'unsafe-inline'`. The rewritten dashboard has no inline `onclick`
 * and no inline `<script>` at all - every control is wired in `app.js` and the theme is set by
 * `theme.js` before first paint - so the exemption is gone, and a page that ever grows an inline
 * handler again would be refused by the browser rather than trusted.
 *
 * `style-src` keeps `'unsafe-inline'`: the dashboard sets CSS custom properties from JavaScript to
 * drive the library's progress bars, which is an inline style by the letter of the policy.
 */
private fun ApplicationCall.addSecurityHeaders() {
    response.headers.append(
        "Content-Security-Policy",
        "default-src 'self'; img-src 'self' https://img.youtube.com https://i.ytimg.com; " +
            "style-src 'self' 'unsafe-inline'; script-src 'self'",
    )
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("X-Frame-Options", "DENY")
    response.headers.append("Referrer-Policy", "no-referrer")
}

fun Route.dashboardRoutes() {
    get("/") {
        val html = javaClass.classLoader?.getResourceAsStream("assets/index.html")
            ?.bufferedReader()?.readText()
        if (html != null) {
            call.addSecurityHeaders()
            call.respondText(html, ContentType.Text.Html)
        } else {
            call.respondText("<html><body><h1>SafeTube Dashboard</h1><p>Assets not found</p></body></html>", ContentType.Text.Html)
        }
    }

    // Serve assets at both /assets/* (legacy) and root-relative paths.
    // Root-relative paths allow the same HTML to work on both local Ktor and the relay.
    val assetFiles = mapOf(
        "app.js" to ContentType("application", "javascript"),
        "theme.js" to ContentType("application", "javascript"),
        "catalog-editor.js" to ContentType("application", "javascript"),
        "style.css" to ContentType.Text.CSS,
        "favicon.svg" to ContentType("image", "svg+xml"),
    )

    for ((fileName, contentType) in assetFiles) {
        for (path in listOf("/assets/$fileName", "/$fileName")) {
            get(path) {
                val content = javaClass.classLoader?.getResourceAsStream("assets/$fileName")
                    ?.bufferedReader()?.readText()
                if (content != null) {
                    call.addSecurityHeaders()
                    call.respondText(content, contentType)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }
}
