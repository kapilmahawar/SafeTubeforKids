package tv.safetubeforkids.app.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.safetubeforkids.app.util.CatalogSyncDebug
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What one attempt to read the server's catalog produced. */
sealed class CatalogFetch {
    data class Ok(val snapshot: CatalogSnapshot) : CatalogFetch()

    /** No valid parent session: a 401 or a 403. */
    data object Unauthorized : CatalogFetch()

    /** The server could not be reached, timed out, or answered with a non-success status. */
    data class Unavailable(val reason: String) : CatalogFetch()

    /** A response arrived but was not a readable catalog document. */
    data class Malformed(val reason: String) : CatalogFetch()
}

/**
 * How the TV reads the parent's catalog.
 *
 * Read-only on purpose. The TV consumes the catalog the parent configured and never writes it back,
 * so the sync layer has no code path that could modify the parent's configuration - and, separately,
 * no code path that could turn a catalog entry into playback permission.
 */
interface CatalogApi {
    suspend fun fetch(): CatalogFetch

    /** The catalog document path, so a test or a future remote server can point somewhere else. */
    val catalogPath: String
}

/**
 * Reads the catalog over HTTP from the SafeTube server.
 *
 * Uses the project's existing HTTP stack (OkHttp, as [tv.safetubeforkids.app.UpdateChecker] and the
 * relay connector do) rather than a second client library, and the same bearer-token scheme the rest
 * of the API uses.
 *
 * **Timeouts are bounded on purpose.** A sync must never hang the TV: connect and read are capped at
 * [CONNECT_TIMEOUT_SECONDS] seconds each and the whole call at [CALL_TIMEOUT_SECONDS], so a server
 * that accepts a connection and then goes silent still ends the attempt. Callers are expected to
 * treat a sync as best-effort background work - the local catalog is already on disk and is what the
 * UI reads.
 */
class HttpCatalogApi(
    private val baseUrl: String,
    private val tokenProvider: suspend () -> String?,
    private val client: OkHttpClient = defaultClient(),
    override val catalogPath: String = DEFAULT_CATALOG_PATH,
) : CatalogApi {

    override suspend fun fetch(): CatalogFetch = withContext(Dispatchers.IO) {
        // Debug-build switch used to verify on a real TV that a server outage leaves the local
        // catalog - and therefore the screen - untouched. Inert in release builds (see
        // CatalogSyncDebug), and it cannot affect authorization, which never consults it.
        if (CatalogSyncDebug.forceServerUnavailable) {
            return@withContext CatalogFetch.Unavailable("server unavailable (debug switch)")
        }

        val token = try {
            tokenProvider()
        } catch (e: Exception) {
            null
        }
        if (token.isNullOrBlank()) return@withContext CatalogFetch.Unauthorized

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + catalogPath)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> CatalogFetch.Unauthorized

                    !response.isSuccessful -> CatalogFetch.Unavailable("HTTP ${response.code}")

                    else -> {
                        val body = response.body?.string()
                        if (body.isNullOrBlank()) {
                            CatalogFetch.Malformed("empty response body")
                        } else {
                            CatalogJson.decodeSnapshot(body)
                                ?.let { CatalogFetch.Ok(it) }
                                ?: CatalogFetch.Malformed("response is not a readable catalog document")
                        }
                    }
                }
            }
        } catch (e: IOException) {
            CatalogFetch.Unavailable(e.message ?: "network error")
        } catch (e: Exception) {
            CatalogFetch.Unavailable(e.message ?: e::class.java.simpleName)
        }
    }

    companion object {
        const val DEFAULT_CATALOG_PATH = "/catalog"
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val READ_TIMEOUT_SECONDS = 5L
        const val CALL_TIMEOUT_SECONDS = 10L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
