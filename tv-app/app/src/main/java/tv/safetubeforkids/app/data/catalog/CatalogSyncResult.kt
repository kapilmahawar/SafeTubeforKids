package tv.safetubeforkids.app.data.catalog

/**
 * Why a catalog synchronization ended the way it did.
 *
 * One case per outcome a caller has to be able to tell apart and report, so nothing has to be
 * parsed out of a string or swallowed. None of them carries a session token, a PIN or a credential.
 */
sealed class CatalogSyncResult {

    /** The local catalog was replaced with the server's catalog at [catalogVersion]. */
    data class Updated(val catalogVersion: Long) : CatalogSyncResult()

    /** The server is already at the locally installed version, so nothing was rewritten. */
    data class AlreadyCurrent(val catalogVersion: Long) : CatalogSyncResult()

    /** Could not reach the server, or it answered with an error status. */
    data class ServerUnavailable(val reason: String) : CatalogSyncResult()

    /** The server refused the request as unauthenticated/unauthorized. */
    data object Unauthorized : CatalogSyncResult()

    /** A response arrived but was not a readable catalog document. */
    data class InvalidResponse(val reason: String) : CatalogSyncResult()

    /** Readable JSON, but a contract version this build does not speak. */
    data class UnsupportedSchema(
        val serverSchemaVersion: Int,
        val supportedSchemaVersion: Int,
    ) : CatalogSyncResult()

    /** A complete catalog that failed validation. Nothing was written. */
    data class InvalidCatalog(val problems: List<CatalogPayloadValidator.Problem>) : CatalogSyncResult()

    /** The server offered a catalog older than the one installed locally. */
    data class VersionRegression(val serverVersion: Long, val localVersion: Long) : CatalogSyncResult()

    /** The local Room replacement failed; the transaction rolled back, so the old catalog stands. */
    data class LocalWriteFailed(val reason: String) : CatalogSyncResult()

    /** True when the local catalog is at the server's version (whether or not it had to be rewritten). */
    val isSuccess: Boolean
        get() = this is Updated || this is AlreadyCurrent
}
