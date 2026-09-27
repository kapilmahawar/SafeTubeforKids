package tv.safetubeforkids.app.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import tv.safetubeforkids.app.auth.InMemoryParentCredentialStore
import tv.safetubeforkids.app.auth.PinChangeResult
import tv.safetubeforkids.app.auth.PinManager
import tv.safetubeforkids.app.auth.PinResetResult
import tv.safetubeforkids.app.auth.SessionManager
import tv.safetubeforkids.app.auth.TEST_PIN
import tv.safetubeforkids.app.auth.TestHasher

/**
 * Parent access over HTTP: signing in, changing the PIN, and the two ways back in when it is forgotten.
 *
 * Every test here is about a boundary the dashboard depends on: what a route answers *before* a
 * parent is authenticated, which of them require a session, and - the one that matters most - that a
 * Recovery Code can replace a PIN but can never be one.
 */
class ParentAccessRoutesTest {

    private class TimeRef(var value: Long = 1000000L)

    private class Wired {
        val timeRef = TimeRef()
        val sessions = SessionManager(clock = { timeRef.value })
        var sessionInvalidations = 0
        val pins = PinManager(
            store = InMemoryParentCredentialStore(),
            clock = { timeRef.value },
            onPinValidated = { sessions.createSession() ?: "" },
            onCredentialsChanged = { sessionInvalidations++; sessions.invalidateAll() },
            hasher = TestHasher,
        )

        /**
         * Creates the credential the way first-run setup does, and takes the change count from there:
         * setting a credential is itself a credential change, so what each test measures has to be
         * what happens *after* setup rather than setup itself.
         */
        fun setUpCredential() {
            pins.setup(TEST_PIN, TEST_PIN)
            sessionInvalidations = 0
        }
    }

    private fun app(wired: Wired = Wired(), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                authRoutes(wired.pins, wired.sessions)
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.signIn(pin: String = TEST_PIN): String {
        val response = client.post("/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"$pin"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject

    // --- first-run state ----------------------------------------------------------------------------

    @Test
    fun authStateSaysWhetherTheTvHasAParentPinYet() = app { 
        val before = client.get("/auth/state")
        assertEquals(HttpStatusCode.OK, before.status)
        assertEquals("false", body(before.bodyAsText())["configured"]?.jsonPrimitive?.content)
    }

    @Test
    fun authStateNeedsNoSessionAndNeverCarriesASecret() {
        val wired = Wired()
        wired.setUpCredential()
        val code = wired.pins.pendingRecoveryCode()!!

        app(wired) {
            val response = client.get("/auth/state")
            val text = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("true", body(text)["configured"]?.jsonPrimitive?.content)
            assertEquals("true", body(text)["recoveryCodePending"]?.jsonPrimitive?.content)
            assertFalse("the recovery code itself is never served here", text.contains(code))
            assertFalse("nor any part of it", text.contains(code.replace("-", "")))
        }
    }

    @Test
    fun signingInOnATvWithNoPinIsRefusedAsNotSetUpRatherThanAsAWrongPin() {
        app {
            val response = client.post("/auth") {
                contentType(ContentType.Application.Json)
                setBody("""{"pin":"$TEST_PIN"}""")
            }

            assertEquals(HttpStatusCode.Conflict, response.status)
            val json = body(response.bodyAsText())
            assertEquals("false", json["success"]?.jsonPrimitive?.content)
            assertNull("no token of any kind", json["token"]?.jsonPrimitive?.contentOrNull)
        }
    }

    // --- signing in ---------------------------------------------------------------------------------

    @Test
    fun theRightPinIssuesASessionAndTheWrongOneDoesNot() {
        val wired = Wired()
        wired.setUpCredential()

        app(wired) {
            val token = signIn()
            assertTrue("the session the route issued is real", wired.sessions.validateSession(token))

            val wrong = client.post("/auth") {
                contentType(ContentType.Application.Json)
                setBody("""{"pin":"999999"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
            assertNull("a failed sign-in issues nothing", body(wrong.bodyAsText())["token"]?.jsonPrimitive?.contentOrNull)
        }
    }

    // --- changing the PIN ---------------------------------------------------------------------------

    @Test
    fun changingThePinRequiresASession() {
        val wired = Wired()
        wired.setUpCredential()

        app(wired) {
            val response = client.post("/auth/pin") {
                contentType(ContentType.Application.Json)
                setBody("""{"currentPin":"$TEST_PIN","newPin":"654321"}""")
            }

            assertEquals("no session, no credential change", HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun changingThePinRequiresTheCurrentOneEvenWithASession() {
        val wired = Wired()
        wired.setUpCredential()

        app(wired) {
            val token = signIn()

            val response = client.post("/auth/pin") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"currentPin":"999999","newPin":"654321","confirmPin":"654321"}""")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(
                "a leaked session must not be enough to take the TV over",
                wired.pins.changePin("999999", "654321", "654321") is PinChangeResult.WrongPin,
            )
        }
    }

    @Test
    fun changingThePinReplacesItAndSignsEverySessionOut() {
        val wired = Wired()
        wired.setUpCredential()

        app(wired) {
            val token = signIn()
            val other = wired.sessions.createSession()!!

            val response = client.post("/auth/pin") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"currentPin":"$TEST_PIN","newPin":"654321","confirmPin":"654321"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("true", body(response.bodyAsText())["success"]?.jsonPrimitive?.content)
            assertEquals(1, wired.sessionInvalidations)
            assertFalse("the session that made the change is gone too", wired.sessions.validateSession(token))
            assertFalse("and so is every other one", wired.sessions.validateSession(other))

            assertEquals(HttpStatusCode.OK, client.post("/auth") {
                contentType(ContentType.Application.Json)
                setBody("""{"pin":"654321"}""")
            }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/auth") {
                contentType(ContentType.Application.Json)
                setBody("""{"pin":"$TEST_PIN"}""")
            }.status)
        }
    }

    @Test
    fun theNewPinIsValidatedBeforeAnythingIsReplaced() {
        val wired = Wired()
        wired.setUpCredential()

        app(wired) {
            val token = signIn()
            listOf("12345" to "12345", "654321" to "654322", "abcdef" to "abcdef").forEach { (new, confirm) ->
                val response = client.post("/auth/pin") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"currentPin":"$TEST_PIN","newPin":"$new","confirmPin":"$confirm"}""")
                }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
            assertEquals("the old PIN is untouched", 0, wired.sessionInvalidations)
        }
    }

    // --- recovery -----------------------------------------------------------------------------------

    @Test
    fun verifyingARecoveryCodeNeedsNoSessionAndSaysOnlyWhetherItIsRight() {
        val wired = Wired()
        wired.setUpCredential()
        val code = wired.pins.pendingRecoveryCode()!!

        app(wired) {
            val right = client.post("/auth/recovery/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"recoveryCode":"$code"}""")
            }
            assertEquals(HttpStatusCode.OK, right.status)

            val wrong = client.post("/auth/recovery/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"recoveryCode":"8K4P-7M2Q-91TX"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
            assertTrue(
                "and it never hands back a session",
                body(wrong.bodyAsText())["token"]?.jsonPrimitive?.contentOrNull == null,
            )
        }
    }

    @Test
    fun aRecoveryCodeIsNotAPinAndCannotSignIn() {
        val wired = Wired()
        wired.setUpCredential()
        val code = wired.pins.pendingRecoveryCode()!!

        app(wired) {
            listOf(code, code.replace("-", ""), code.substring(0, 6)).forEach { attempt ->
                val response = client.post("/auth") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"pin":"$attempt"}""")
                }
                assertNotEquals("a recovery code must never authenticate", HttpStatusCode.OK, response.status)
                assertNull(body(response.bodyAsText())["token"]?.jsonPrimitive?.contentOrNull)
            }
        }
    }

    @Test
    fun resettingWithARecoveryCodeReplacesThePinAndRotatesTheCode() {
        val wired = Wired()
        wired.setUpCredential()
        val oldCode = wired.pins.pendingRecoveryCode()!!
        val existingSession = wired.sessions.createSession()!!

        app(wired) {
            val response = client.post("/auth/recovery") {
                contentType(ContentType.Application.Json)
                setBody("""{"recoveryCode":"$oldCode","newPin":"654321","confirmPin":"654321"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val newCode = body(response.bodyAsText())["recoveryCode"]!!.jsonPrimitive.content
            assertNotEquals(oldCode, newCode)
            assertFalse("the used code is dead", wired.pins.verifyRecoveryCode(oldCode) is tv.safetubeforkids.app.auth.RecoveryCheckResult.Verified)
            assertFalse("a session from before the reset is dead too", wired.sessions.validateSession(existingSession))
            assertEquals(1, wired.sessionInvalidations)

            assertEquals(HttpStatusCode.OK, client.post("/auth") {
                contentType(ContentType.Application.Json)
                setBody("""{"pin":"654321"}""")
            }.status)
        }
    }

    @Test
    fun aForgottenPinCanBeResetWithoutAnySession() {
        // The whole point: a parent who cannot sign in is exactly who this route is for. It is still
        // authenticated - by the recovery code - and it still cannot do anything but replace the PIN.
        val wired = Wired()
        wired.setUpCredential()
        val code = wired.pins.pendingRecoveryCode()!!

        app(wired) {
            assertEquals(0, wired.sessions.getActiveSessionCount())

            val response = client.post("/auth/recovery") {
                contentType(ContentType.Application.Json)
                setBody("""{"recoveryCode":"$code","newPin":"654321","confirmPin":"654321"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("and it grants no session of its own", 0, wired.sessions.getActiveSessionCount())
        }
    }

    @Test
    fun aWrongRecoveryCodeResetsNothing() {
        val wired = Wired()
        wired.setUpCredential()

        app(wired) {
            val response = client.post("/auth/recovery") {
                contentType(ContentType.Application.Json)
                setBody("""{"recoveryCode":"8K4P-7M2Q-91TX","newPin":"654321","confirmPin":"654321"}""")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(
                "the old PIN is still the PIN",
                wired.pins.validate(TEST_PIN) is tv.safetubeforkids.app.auth.PinResult.Success,
            )
        }
    }

    @Test
    fun theNewPinFromARecoveryIsValidatedToo() {
        val wired = Wired()
        wired.setUpCredential()
        val code = wired.pins.pendingRecoveryCode()!!

        app(wired) {
            val response = client.post("/auth/recovery") {
                contentType(ContentType.Application.Json)
                setBody("""{"recoveryCode":"$code","newPin":"12345","confirmPin":"12345"}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                "and the code was not spent on a rejected attempt",
                wired.pins.verifyRecoveryCode(code) is tv.safetubeforkids.app.auth.RecoveryCheckResult.Verified,
            )
        }
    }

    @Test
    fun theResetResultTypeIsWhatTheRouteReports() {
        val wired = Wired()
        wired.setUpCredential()
        val code = wired.pins.pendingRecoveryCode()!!

        assertTrue(wired.pins.resetWithRecovery(code, "654321", "654321") is PinResetResult.Reset)
    }

    // --- rotating -----------------------------------------------------------------------------------

    @Test
    fun rotatingRequiresASession() {
        val wired = Wired()
        wired.setUpCredential()

        app(wired) {
            assertEquals(HttpStatusCode.Unauthorized, client.post("/auth/recovery/rotate").status)
        }
    }

    @Test
    fun rotatingIssuesANewCodeAndKillsTheOldOne() {
        val wired = Wired()
        wired.setUpCredential()
        val oldCode = wired.pins.pendingRecoveryCode()!!

        app(wired) {
            val token = signIn()
            val response = client.post("/auth/recovery/rotate") {
                header("Authorization", "Bearer $token")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val newCode = body(response.bodyAsText())["recoveryCode"]!!.jsonPrimitive.content
            assertNotEquals(oldCode, newCode)
            assertFalse(wired.pins.verifyRecoveryCode(oldCode) is tv.safetubeforkids.app.auth.RecoveryCheckResult.Verified)
            assertTrue(wired.pins.verifyRecoveryCode(newCode) is tv.safetubeforkids.app.auth.RecoveryCheckResult.Verified)
        }
    }

    @Test
    fun aRecoveryCodeIsNeverServedWithoutAuthentication() {
        val wired = Wired()
        wired.setUpCredential()
        val code = wired.pins.pendingRecoveryCode()!!

        app(wired) {
            // The public routes, one by one: none of them may carry the code.
            listOf("/auth/state", "/status").forEach { path ->
                val response = client.get(path)
                assertFalse(
                    "GET $path must not serve the recovery code",
                    response.bodyAsText().contains(code.replace("-", "")),
                )
            }
        }
    }

    // --- signing out everywhere ---------------------------------------------------------------------

    @Test
    fun revokingSessionsRequiresASessionAndThenRemovesEveryOne() {
        val wired = Wired()
        wired.setUpCredential()

        app(wired) {
            assertEquals(HttpStatusCode.Unauthorized, client.post("/auth/sessions/revoke").status)

            val token = signIn()
            val other = wired.sessions.createSession()!!

            val response = client.post("/auth/sessions/revoke") {
                header("Authorization", "Bearer $token")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(wired.sessions.validateSession(token))
            assertFalse(wired.sessions.validateSession(other))
            assertEquals(0, wired.sessions.getActiveSessionCount())
        }
    }
}
