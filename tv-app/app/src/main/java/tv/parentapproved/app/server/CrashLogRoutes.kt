package tv.parentapproved.app.server

import android.content.Context
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import tv.parentapproved.app.CrashHandler
import tv.parentapproved.app.auth.SessionManager

/**
 * Response type must be a concrete serializable class: a heterogeneous
 * Map<String, Any> cannot be serialized by kotlinx.serialization, which made this endpoint
 * fail with HTTP 500 and left crash reports unreadable.
 */
@Serializable
data class CrashLogResponse(
    val hasCrash: Boolean,
    val log: String,
)

fun Route.crashLogRoutes(sessionManager: SessionManager, appContext: Context) {
    get("/crash-log") {
        if (!validateSession(sessionManager)) return@get
        val log = CrashHandler.readCrashLog(appContext)
        call.respond(CrashLogResponse(hasCrash = log != null, log = log ?: ""))
    }
}
