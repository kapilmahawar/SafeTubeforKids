package tv.safetubeforkids.app.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * The parent credential, on disk.
 *
 * SharedPreferences rather than a Room table, and that is a deliberate choice worth stating: the
 * project already keeps its *authentication* state this way (`parentapproved_sessions`,
 * `parentapproved_pin_lockout`), because a credential is not catalog data - it is not synced, not
 * versioned, not part of the tree the child's screen is drawn from, and nothing in the catalog
 * pipeline should ever be able to read it. Putting it in the catalog database would also mean a
 * schema migration for a feature that needs none, and would put the parent's credential inside the
 * database that the destructive reset empties by design.
 *
 * This file, `parentapproved_parent_access`, holds exactly four things plus a flag: a verifier, a
 * salt, an iteration count and an algorithm, for each of the two secrets. `configured` is the only
 * fact first-run setup is built on, and it is stored rather than inferred so that "a Parent PIN
 * exists" survives even if one of the verifier fields were ever unreadable.
 */
class SharedPrefsParentCredentialStore(
    private val prefs: SharedPreferences,
) : ParentCredentialStore {

    override fun isConfigured(): Boolean = prefs.getBoolean(KEY_CONFIGURED, false)

    override fun readPin(): StoredSecret? = read(PREFIX_PIN)

    override fun readRecovery(): StoredSecret? = read(PREFIX_RECOVERY)

    override fun writePin(secret: StoredSecret) {
        write(PREFIX_PIN, secret)
        // The flag is written last: a process that dies mid-write leaves an unconfigured device with
        // no half-usable credential, rather than a configured one whose verifier is missing.
        prefs.edit().putBoolean(KEY_CONFIGURED, true).commit()
    }

    override fun writeRecovery(secret: StoredSecret) {
        write(PREFIX_RECOVERY, secret)
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private fun read(prefix: String): StoredSecret? {
        val hash = prefs.getString(prefix + KEY_HASH, null) ?: return null
        val salt = prefs.getString(prefix + KEY_SALT, null) ?: return null
        val iterations = prefs.getInt(prefix + KEY_ITERATIONS, 0)
        val algorithm = prefs.getString(prefix + KEY_ALGORITHM, null) ?: return null
        if (iterations <= 0) return null
        return StoredSecret(hash = hash, salt = salt, iterations = iterations, algorithm = algorithm)
    }

    private fun write(prefix: String, secret: StoredSecret) {
        prefs.edit()
            .putString(prefix + KEY_HASH, secret.hash)
            .putString(prefix + KEY_SALT, secret.salt)
            .putInt(prefix + KEY_ITERATIONS, secret.iterations)
            .putString(prefix + KEY_ALGORITHM, secret.algorithm)
            .commit()
    }

    companion object {
        const val FILE_NAME = "parentapproved_parent_access"

        /** Stored, not inferred: the flag first-run onboarding reads. */
        const val KEY_CONFIGURED = "parent_configured"

        private const val PREFIX_PIN = "pin_"
        private const val PREFIX_RECOVERY = "recovery_"

        private const val KEY_HASH = "hash"
        private const val KEY_SALT = "salt"
        private const val KEY_ITERATIONS = "iterations"
        private const val KEY_ALGORITHM = "algorithm"

        /**
         * Reads a second lockout counter for the Recovery Code out of the same file. The two secrets
         * are rate-limited independently, so a parent fumbling the recovery code does not spend the
         * PIN's attempts and vice versa.
         */
        const val LOCKOUT_FILE_NAME = "parentapproved_pin_lockout"

        fun open(context: Context): SharedPrefsParentCredentialStore =
            SharedPrefsParentCredentialStore(
                context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            )
    }
}
