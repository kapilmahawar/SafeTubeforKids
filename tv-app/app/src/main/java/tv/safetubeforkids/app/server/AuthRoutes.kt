package tv.safetubeforkids.app.server

import tv.safetubeforkids.app.auth.PinChangeResult
import tv.safetubeforkids.app.auth.PinManager
import tv.safetubeforkids.app.auth.PinResetResult
import tv.safetubeforkids.app.auth.PinResult
import tv.safetubeforkids.app.auth.PinSetupResult
import tv.safetubeforkids.app.auth.RecoveryCheckResult
import tv.safetubeforkids.app.auth.SessionManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable

@Serializable
data class AuthRequest(val pin: String? = null)

@Serializable
data class AuthResponse(
    val success: Boolean,
    val token: String? = null,
    val attemptsRemaining: Int? = null,
    val retryAfterMs: Long? = null,
    val error: String? = null,
)

/** Whether this TV has a Parent PIN yet: the one fact the dashboard needs before offering a login. */
@Serializable
data class AuthStateResponse(
    val success: Boolean = true,
    val configured: Boolean,
    /** True while a freshly generated Recovery Code is waiting to be read off the TV. Never the code. */
    val recoveryCodePending: Boolean = false,
)

@Serializable
data class ChangePinRequest(val currentPin: String? = null, val newPin: String? = null, val confirmPin: String? = null)

@Serializable
data class RecoveryResetRequest(
    val recoveryCode: String? = null,
    val newPin: String? = null,
    val confirmPin: String? = null,
)

@Serializable
data class RecoveryVerifyRequest(val recoveryCode: String? = null)

/** A response that carries a Recovery Code: sent only to an authenticated parent, or to one who just used the old one. */
@Serializable
data class RecoveryCodeResponse(
    val success: Boolean = true,
    val recoveryCode: String,
    val message: String? = null,
)

@Serializable
data class SimpleSuccess(val success: Boolean = true, val message: String? = null)

/**
 * Parent access over HTTP: signing in, and the ways back in when the PIN is forgotten.
 *
 * **What these routes are not.** None of them touch the catalog, the approved sources or anything
 * `PlaybackAuthorization` reads. A successful login here produces a dashboard session and nothing
 * else - the same session shape the ephemeral model used, so every existing route that already
 * requires a session keeps working unchanged. W10 adds exactly one new way to *obtain* that session
 * (a Recovery Code, which can also *replace* a forgotten PIN) and no new authority.
 *
 * **Why the destructive reset is absent.** There is no HTTP route that wipes the TV, and that is a
 * requirement rather than an omission: an unauthenticated request must not be able to erase a child's
 * library. The wipe lives in the TV's own interface, behind a typed confirmation phrase.
 */
fun Route.authRoutes(pinManager: PinManager, sessionManager: SessionManager) {
    post("/auth/refresh") {
        val authHeader = call.request.header("Authorization")
        val token = authHeader?.removePrefix("Bearer ")
        if (token == null) {
            call.respond(HttpStatusCode.Unauthorized, AuthResponse(success = false, error = "Missing token"))
            return@post
        }
        val newToken = sessionManager.refreshSession(token)
        if (newToken == null) {
            call.respond(HttpStatusCode.Unauthorized, AuthResponse(success = false, error = "Invalid or expired token"))
            return@post
        }
        call.respond(HttpStatusCode.OK, AuthResponse(success = true, token = newToken))
    }

    /**
     * First-run setup, answered without a session on purpose: the dashboard has to be able to say
     * "finish setting up on your TV" rather than showing a PIN form that cannot possibly work. The
     * only thing it reveals is whether a Parent PIN exists, which is not a secret - and the Recovery
     * Code's *existence* is reported without the code itself ever being served here.
     */
    get("/auth/state") {
        call.respond(
            HttpStatusCode.OK,
            AuthStateResponse(
                configured = pinManager.isConfigured(),
                recoveryCodePending = pinManager.pendingRecoveryCode() != null,
            )
        )
    }

    post("/auth") {
        val body = try {
            call.receive<AuthRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, AuthResponse(success = false, error = "Missing or invalid request body"))
            return@post
        }

        val pin = body.pin
        if (pin.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, AuthResponse(success = false, error = "PIN is required"))
            return@post
        }

        when (val result = pinManager.validate(pin)) {
            is PinResult.Success -> {
                call.respond(HttpStatusCode.OK, AuthResponse(success = true, token = result.token))
            }
            is PinResult.Invalid -> {
                call.respond(HttpStatusCode.Unauthorized, AuthResponse(
                    success = false,
                    attemptsRemaining = result.attemptsRemaining,
                    error = "Invalid PIN"
                ))
            }
            is PinResult.RateLimited -> {
                call.respond(HttpStatusCode.TooManyRequests, AuthResponse(
                    success = false,
                    retryAfterMs = result.retryAfterMs,
                    error = "Too many attempts"
                ))
            }
            // The PIN was right but no session could be issued for it (no callback wired, or a blank
            // token). Fail closed and say so: this must never be an OK response and never carry a token.
            is PinResult.NotConfigured -> {
                call.respond(HttpStatusCode.ServiceUnavailable, AuthResponse(
                    success = false,
                    error = "Authentication is unavailable"
                ))
            }
            // Never set up, or wiped. Distinct from the above: there is nothing to type that works.
            is PinResult.NotSetUp -> {
                call.respond(HttpStatusCode.Conflict, AuthResponse(
                    success = false,
                    error = "This TV has no Parent PIN yet. Set one up on the TV first."
                ))
            }
        }
    }

    /**
     * Change the PIN. Requires a session *and* the current PIN: a session is something a phone holds,
     * so it must not be enough on its own to replace the credential the parent signs in with.
     */
    post("/auth/pin") {
        if (!validateSession(sessionManager)) return@post

        val body = try {
            call.receive<ChangePinRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, AuthResponse(success = false, error = "Missing or invalid request body"))
            return@post
        }

        val current = body.currentPin
        val new = body.newPin
        if (current.isNullOrBlank() || new.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, AuthResponse(success = false, error = "Both the current and the new PIN are required"))
            return@post
        }

        when (val result = pinManager.changePin(current, new, body.confirmPin)) {
            is PinChangeResult.Changed -> call.respond(
                HttpStatusCode.OK,
                SimpleSuccess(message = "Your Parent PIN was changed. Sign in again with the new one.")
            )
            is PinChangeResult.Invalid -> call.respond(
                HttpStatusCode.BadRequest,
                AuthResponse(success = false, error = result.reason)
            )
            is PinChangeResult.WrongPin -> call.respond(
                HttpStatusCode.Unauthorized,
                AuthResponse(success = false, error = "That is not the current PIN")
            )
            is PinChangeResult.RateLimited -> call.respond(
                HttpStatusCode.TooManyRequests,
                AuthResponse(success = false, retryAfterMs = result.retryAfterMs, error = "Too many attempts")
            )
            is PinChangeResult.NotSetUp -> call.respond(
                HttpStatusCode.Conflict,
                AuthResponse(success = false, error = "This TV has no Parent PIN yet")
            )
        }
    }

    /** Step one of "I forgot my PIN": is this Recovery Code the one? Answers yes or no, nothing more. */
    post("/auth/recovery/verify") {
        val body = try {
            call.receive<RecoveryVerifyRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, AuthResponse(success = false, error = "Missing or invalid request body"))
            return@post
        }

        val code = body.recoveryCode
        if (code.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, AuthResponse(success = false, error = "A Recovery Code is required"))
            return@post
        }

        when (val result = pinManager.verifyRecoveryCode(code)) {
            RecoveryCheckResult.Verified -> call.respond(HttpStatusCode.OK, SimpleSuccess(message = "That is the right Recovery Code."))
            RecoveryCheckResult.NotWellFormed -> call.respond(
                HttpStatusCode.BadRequest,
                AuthResponse(success = false, error = "A Recovery Code is twelve letters and numbers, like 8K4P-7M2Q-91TX.")
            )
            RecoveryCheckResult.Unknown -> call.respond(
                HttpStatusCode.Unauthorized,
                AuthResponse(success = false, error = "That Recovery Code is not right")
            )
            is RecoveryCheckResult.RateLimited -> call.respond(
                HttpStatusCode.TooManyRequests,
                AuthResponse(success = false, retryAfterMs = result.retryAfterMs, error = "Too many attempts")
            )
            RecoveryCheckResult.NotSetUp -> call.respond(
                HttpStatusCode.Conflict,
                AuthResponse(success = false, error = "This TV has no Parent PIN yet")
            )
        }
    }

    /**
     * Step two: replace the forgotten PIN, and rotate the code that was just used.
     *
     * The new code comes back in the response *and* is left pending on the TV, because a parent doing
     * this from a phone may well be standing in the same room as the television - and if they are not,
     * the TV still has it when they get there. It is never served by any other route, and never to a
     * caller who has not just proved they hold the old one.
     */
    post("/auth/recovery") {
        val body = try {
            call.receive<RecoveryResetRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, AuthResponse(success = false, error = "Missing or invalid request body"))
            return@post
        }

        val code = body.recoveryCode
        val newPin = body.newPin
        if (code.isNullOrBlank() || newPin.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, AuthResponse(success = false, error = "A Recovery Code and a new PIN are required"))
            return@post
        }

        when (val result = pinManager.resetWithRecovery(code, newPin, body.confirmPin)) {
            is PinResetResult.Reset -> call.respond(
                HttpStatusCode.OK,
                RecoveryCodeResponse(
                    recoveryCode = result.recoveryCode,
                    message = "Your Parent PIN was replaced. Write down this new Recovery Code - the old one no longer works."
                )
            )
            is PinResetResult.Invalid -> call.respond(
                HttpStatusCode.BadRequest,
                AuthResponse(success = false, error = result.reason)
            )
            PinResetResult.Unknown -> call.respond(
                HttpStatusCode.Unauthorized,
                AuthResponse(success = false, error = "That Recovery Code is not right")
            )
            PinResetResult.NotSetUp -> call.respond(
                HttpStatusCode.Conflict,
                AuthResponse(success = false, error = "This TV has no Parent PIN yet")
            )
        }
    }

    /** A new Recovery Code, for a parent who knows the PIN. Session required; nothing else can ask. */
    post("/auth/recovery/rotate") {
        if (!validateSession(sessionManager)) return@post

        val code = pinManager.rotateRecoveryCode()
        call.respond(
            HttpStatusCode.OK,
            RecoveryCodeResponse(
                recoveryCode = code,
                message = "Write this down. The previous Recovery Code no longer works."
            )
        )
    }

    /**
     * Sign out everywhere. Session required, and it signs the caller out too - which is what "all
     * sessions" has to mean, or it would be a lie the parent only discovers later.
     */
    post("/auth/sessions/revoke") {
        if (!validateSession(sessionManager)) return@post
        sessionManager.invalidateAll()
        call.respond(HttpStatusCode.OK, SimpleSuccess(message = "Signed out everywhere. Sign in again to use this page."))
    }
}

suspend fun RoutingContext.validateSession(sessionManager: SessionManager): Boolean {
    val authHeader = call.request.header("Authorization")
    val token = authHeader?.removePrefix("Bearer ")
        ?: call.request.cookies["session"]
    if (token == null || !sessionManager.validateSession(token)) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
        return false
    }
    return true
}
