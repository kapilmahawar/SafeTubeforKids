package tv.safetubeforkids.app.auth

import android.content.SharedPreferences

/**
 * The failed-attempt counters, in preferences.
 *
 * One file can hold more than one counter, because W10 rate-limits two different secrets - the Parent
 * PIN and the Recovery Code - and a parent fumbling one of them must not spend the other's attempts.
 * [keyPrefix] is how they are told apart; the default is the prefix the file already used, so an
 * existing installation's counter keeps working across the upgrade instead of resetting to zero tries.
 */
class SharedPrefsPinLockoutPersistence(
    private val prefs: SharedPreferences,
    private val keyPrefix: String = PREFIX_PIN,
) : PinLockoutPersistence {

    companion object {
        const val PREFIX_PIN = "pin_"

        /** The Recovery Code's own counter, in the same file, under its own names. */
        const val PREFIX_RECOVERY = "recovery_"

        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val KEY_LOCKOUT_COUNT = "lockout_count"
    }

    override fun save(failedAttempts: Int, lockoutUntil: Long, lockoutCount: Int) {
        prefs.edit()
            .putInt(keyPrefix + KEY_FAILED_ATTEMPTS, failedAttempts)
            .putLong(keyPrefix + KEY_LOCKOUT_UNTIL, lockoutUntil)
            .putInt(keyPrefix + KEY_LOCKOUT_COUNT, lockoutCount)
            .apply()
    }

    override fun loadFailedAttempts(): Int = prefs.getInt(keyPrefix + KEY_FAILED_ATTEMPTS, 0)
    override fun loadLockoutUntil(): Long = prefs.getLong(keyPrefix + KEY_LOCKOUT_UNTIL, 0)
    override fun loadLockoutCount(): Int = prefs.getInt(keyPrefix + KEY_LOCKOUT_COUNT, 0)
}
