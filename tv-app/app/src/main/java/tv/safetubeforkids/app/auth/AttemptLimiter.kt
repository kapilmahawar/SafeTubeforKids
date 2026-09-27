package tv.safetubeforkids.app.auth

/**
 * Where the failed-attempt counter survives a restart.
 *
 * A counter that lived only in memory would be a rate limit anyone could clear by pulling the plug,
 * which is not a rate limit at all. The interface is this small on purpose: three numbers, saved and
 * loaded, so the lockout rules stay testable without an Android context.
 */
interface PinLockoutPersistence {
    fun save(failedAttempts: Int, lockoutUntil: Long, lockoutCount: Int)
    fun loadFailedAttempts(): Int
    fun loadLockoutUntil(): Long
    fun loadLockoutCount(): Int
}

/**
 * The failed-attempt counter and escalating lockout, for one secret.
 *
 * This is the code `PinManager` already had, lifted out unchanged so that the persistent Parent PIN
 * and the persistent Recovery Code can each have their own instance: five wrong answers, then five
 * minutes, then ten, then twenty, doubling up to a ceiling. The behaviour is deliberately identical to
 * the ephemeral-PIN version, because that behaviour was already tested and already right.
 *
 * The counters are persisted, so a lockout cannot be escaped by rebooting the TV - which is the whole
 * reason a four-digit-per-second guesser cannot get anywhere with a six-digit secret.
 */
class AttemptLimiter(
    private val clock: () -> Long,
    private val persistence: PinLockoutPersistence? = null,
) {
    private var failedAttempts: Int = persistence?.loadFailedAttempts() ?: 0
    private var lockoutUntil: Long = persistence?.loadLockoutUntil() ?: 0
    private var lockoutCount: Int = persistence?.loadLockoutCount() ?: 0

    /** Milliseconds the caller must wait, or 0 when an attempt may be made now. */
    fun retryAfterMs(): Long {
        val now = clock()
        if (now >= lockoutUntil) {
            // Expiry clears the counter, so a parent who waits gets a full set of tries again.
            if (lockoutUntil > 0) {
                failedAttempts = 0
                lockoutUntil = 0
                persist()
            }
            return 0
        }
        return lockoutUntil - now
    }

    /** A correct secret clears everything, including the escalation history. */
    fun recordSuccess() {
        failedAttempts = 0
        lockoutCount = 0
        lockoutUntil = 0
        persist()
    }

    /** A wrong secret. Returns the lockout that just started, or 0 when tries remain. */
    fun recordFailure(): Long {
        failedAttempts++
        if (failedAttempts < MAX_ATTEMPTS) {
            persist()
            return 0
        }
        lockoutCount++
        val multiplier = 1L shl (lockoutCount - 1).coerceAtMost(10)
        lockoutUntil = clock() + BASE_LOCKOUT_MS * multiplier
        persist()
        return BASE_LOCKOUT_MS * multiplier
    }

    fun attemptsRemaining(): Int = (MAX_ATTEMPTS - failedAttempts).coerceAtLeast(0)

    fun isLockedOut(): Boolean = retryAfterMs() > 0

    fun failedAttempts(): Int = failedAttempts

    private fun persist() {
        persistence?.save(failedAttempts, lockoutUntil, lockoutCount)
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val BASE_LOCKOUT_MS = 5 * 60 * 1000L
    }
}
