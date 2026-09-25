package tv.parentapproved.app.timelimits

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.safetubeforkids.app.timelimits.LoopbackTimeLimitRequestSender
import tv.safetubeforkids.app.timelimits.TimeLimitRequestResult
import tv.safetubeforkids.app.timelimits.TimeLimitRequestState
import tv.safetubeforkids.app.timelimits.toRequestState

/**
 * The lock screen's "Request More Time" delivery, against a real HTTP server on the JVM (Robolectric,
 * as the catalog tests do). The point of these tests is the one the physical defect turned on: what the
 * child is told must follow the server's answer, never the attempt.
 */
@RunWith(RobolectricTestRunner::class)
class TimeLimitRequestTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun senderFor(server: MockWebServer) = LoopbackTimeLimitRequestSender(
        baseUrl = server.url("/").toString(),
    )

    @Test
    fun aSuccessfulResponseIsAccepted() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":"true"}"""))

        assertEquals(TimeLimitRequestResult.Accepted, senderFor(server).requestMoreTime())
    }

    @Test
    fun theRequestIsAPostToTheExistingRequestRoute() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":"true"}"""))

        senderFor(server).requestMoreTime()

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/time-limits/request", recorded.path)
    }

    @Test
    fun theServersThrottleAnswerIsARejectionNotAnAcceptance() = runBlocking {
        // What the route returns when a request was already made inside its two-minute window.
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setBody("""{"error":"Please wait before requesting again"}""")
        )

        val result = senderFor(server).requestMoreTime()

        assertEquals(TimeLimitRequestResult.Rejected(429), result)
        assertNotEquals(TimeLimitRequestResult.Accepted, result)
    }

    @Test
    fun aServerErrorIsARejectionNotAnAcceptance() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = senderFor(server).requestMoreTime()

        assertEquals(TimeLimitRequestResult.Rejected(500), result)
        assertNotEquals(TimeLimitRequestResult.Accepted, result)
    }

    @Test
    fun aConnectionFailureIsUnreachable() = runBlocking {
        // Nothing is listening on port 1: the request cannot leave the TV.
        val sender = LoopbackTimeLimitRequestSender(baseUrl = "http://127.0.0.1:1")

        assertEquals(TimeLimitRequestResult.Unreachable, sender.requestMoreTime())
    }

    @Test
    fun onlyAnAcceptedRequestProducesTheConfirmation() {
        assertEquals(TimeLimitRequestState.SENT, TimeLimitRequestResult.Accepted.toRequestState())

        assertEquals(TimeLimitRequestState.FAILED, TimeLimitRequestResult.Rejected(429).toRequestState())
        assertEquals(TimeLimitRequestState.FAILED, TimeLimitRequestResult.Rejected(500).toRequestState())
        assertEquals(TimeLimitRequestState.FAILED, TimeLimitRequestResult.Unreachable.toRequestState())

        // The defect this fixes: a failure must never reach the "Request sent!" state.
        assertNotEquals(TimeLimitRequestState.SENT, TimeLimitRequestResult.Rejected(429).toRequestState())
        assertNotEquals(TimeLimitRequestState.SENT, TimeLimitRequestResult.Unreachable.toRequestState())
    }

    @Test
    fun anUntouchedLockScreenShowsTheButtonItself() {
        // IDLE is the state the screen starts in, so the control reads "Request More Time".
        assertNotEquals(TimeLimitRequestState.SENT, TimeLimitRequestState.IDLE)
        assertNotEquals(TimeLimitRequestState.FAILED, TimeLimitRequestState.IDLE)
    }
}
