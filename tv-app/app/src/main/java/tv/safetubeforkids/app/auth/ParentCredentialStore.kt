package tv.safetubeforkids.app.auth

/**
 * One stored secret, as it lives on disk: a verifier, never the secret itself.
 *
 * The plaintext Parent PIN and the plaintext Recovery Code exist in exactly two places - in the
 * parent's head (or on the paper they wrote it on) and in memory while they are being typed. What is
 * persisted is this: a PBKDF2 verifier, the salt it was derived with, the iteration count that was
 * used, and which digest that count was spent on.
 *
 * [algorithm] is stored per secret rather than assumed, because the two algorithms available on this
 * project's minimum SDK are not the same one: PBKDF2WithHmacSHA256 only exists from API 26, so a
 * device at API 24 or 25 hashes with SHA1 instead. A verifier therefore has to carry the recipe that
 * produced it, or a device that changes its mind about which digest it has would lock the parent out
 * of their own TV.
 */
data class StoredSecret(
    val hash: String,
    val salt: String,
    val iterations: Int,
    val algorithm: String,
)

/**
 * Where the parent-access credential lives.
 *
 * A tiny interface with one implementation for the TV (`SharedPrefsParentCredentialStore`) and one
 * for tests (`InMemoryParentCredentialStore`), for the same reason [SessionPersistence] has the same
 * shape: the rules about *how long a credential may exist* must be testable without an Android
 * context, and a test that had to write real preferences to check a lockout would be testing
 * SharedPreferences rather than the lockout.
 *
 * Nothing here can read a PIN or a Recovery Code back out. There is no `readPin(): String`, and that
 * is the point: a store that could hand the plaintext back would be a store that an API could leak it
 * through. [clear] is the destructive reset's own door: it removes both credentials, which is exactly
 * what "you have forgotten everything and want to start again" means.
 */
interface ParentCredentialStore {

    /** True once a Parent PIN has been created. The one persistent fact first-run setup is built on. */
    fun isConfigured(): Boolean

    fun readPin(): StoredSecret?
    fun readRecovery(): StoredSecret?

    fun writePin(secret: StoredSecret)
    fun writeRecovery(secret: StoredSecret)

    /** Removes the Parent PIN, the Recovery Code and the configured flag. Nothing else. */
    fun clear()
}

/** The credential, held in memory. Used by tests, and by nothing a family installs. */
class InMemoryParentCredentialStore(
    private var pin: StoredSecret? = null,
    private var recovery: StoredSecret? = null,
) : ParentCredentialStore {

    override fun isConfigured(): Boolean = pin != null

    override fun readPin(): StoredSecret? = pin

    override fun readRecovery(): StoredSecret? = recovery

    override fun writePin(secret: StoredSecret) {
        pin = secret
    }

    override fun writeRecovery(secret: StoredSecret) {
        recovery = secret
    }

    override fun clear() {
        pin = null
        recovery = null
    }
}
