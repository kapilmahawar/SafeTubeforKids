package tv.safetubeforkids.app.timelimits

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tv.safetubeforkids.app.server.SAFE_TUBE_SERVER_PORT
import tv.safetubeforkids.app.util.AppLogger
import java.util.concurrent.TimeUnit

/** What the TV's own dashboard server said about the child's "request more time". */
sealed interface TimeLimitRequestResult {
    /** The server took it, so the parent will see a pending request. */
    data object Accepted : TimeLimitRequestResult

    /** The server answered and refused it - its own two-minute throttle, or an error. */
    data class Rejected(val statusCode: Int) : TimeLimitRequestResult

    /** No answer at all: the request never left the TV. */
    data object Unreachable : TimeLimitRequestResult
}

/**
 * What the lock screen shows. Only [SENT] may display the confirmation, and it is reachable only from
 * [TimeLimitRequestResult.Accepted] - an attempt is not an answer.
 */
enum class TimeLimitRequestState { IDLE, SENT, FAILED }

fun TimeLimitRequestResult.toRequestState(): TimeLimitRequestState = when (this) {
    TimeLimitRequestResult.Accepted -> TimeLimitRequestState.SENT
    is TimeLimitRequestResult.Rejected -> TimeLimitRequestState.FAILED
    TimeLimitRequestResult.Unreachable -> TimeLimitRequestState.FAILED
}

/** Sends the child's request and reports what actually happened. */
interface TimeLimitRequestSender {
    suspend fun requestMoreTime(): TimeLimitRequestResult
}

/**
 * Delivers the request to the TV's own dashboard server.
 *
 * This is the round trip the rest of the app already makes to its embedded server - OkHttp on
 * [Dispatchers.IO] against `http://127.0.0.1:<port>`, exactly as the catalog client and the relay
 * bridge do - with the lock screen's tighter time budget, because a child is waiting on it.
 *
 * It replaces an `HttpURLConnection` opened inside `rememberCoroutineScope().launch`, which runs on the
 * main dispatcher: Android refuses network work there, and the old code swallowed the failure in an
 * empty `catch`, so the screen said "Request sent!" for a request that never left the TV.
 */
class LoopbackTimeLimitRequestSender(
    baseUrl: String = "http://127.0.0.1:$SAFE_TUBE_SERVER_PORT",
    private val client: OkHttpClient = defaultClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TimeLimitRequestSender {

    private val requestUrl = baseUrl.trimEnd('/') + REQUEST_PATH

    override suspend fun requestMoreTime(): TimeLimitRequestResult = withContext(io) {
        // No body and no session: this is the one endpoint a locked-out child's TV may call, and the
        // server throttles repeats itself.
        val request = Request.Builder()
            .url(requestUrl)
            .post(EMPTY_BODY)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    TimeLimitRequestResult.Accepted
                } else {
                    // The reason belongs in the log for a parent or an engineer; the child is only told
                    // to try again.
                    AppLogger.warn("Request more time was refused: HTTP ${response.code}")
                    TimeLimitRequestResult.Rejected(response.code)
                }
            }
        } catch (e: Exception) {
            AppLogger.warn(
                "Request more time could not be delivered: ${e.message ?: e::class.java.simpleName}"
            )
            TimeLimitRequestResult.Unreachable
        }
    }

    companion object {
        const val REQUEST_PATH = "/time-limits/request"

        /** Bounded, so a server that accepts a connection and then stalls still ends the attempt. */
        const val CONNECT_TIMEOUT_SECONDS = 2L
        const val READ_TIMEOUT_SECONDS = 2L
        const val CALL_TIMEOUT_SECONDS = 4L

        private val EMPTY_BODY = ByteArray(0).toRequestBody("application/json".toMediaTypeOrNull())

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
