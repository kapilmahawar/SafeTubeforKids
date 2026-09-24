package tv.safetubeforkids.app.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random

sealed class PinResult {
    data class Success(val token: String) : PinResult()
    data class Invalid(val attemptsRemaining: Int) : PinResult()
    data class RateLimited(val retryAfterMs: Long) : PinResult()

    /**
     * The PIN was correct, but this device cannot issue a session for it, so the caller must not treat
     * it as an authentication: either no session-issuing callback is wired, or the wired one returned a
     * blank token.
     *
     * This exists because `onPinValidated?.invoke(pin) ?: ""` turned a missing callback into a nominal
     * [Success] carrying an empty token - an authentication-shaped result for a request that
     * authenticated nobody. It is deliberately not [Invalid]: the PIN was not wrong, so it must not be
     * counted as a failed attempt either.
     */
    object NotConfigured : PinResult()
}

interface PinLockoutPersistence {
    fun save(failedAttempts: Int, lockoutUntil: Long, lockoutCount: Int)
    fun loadFailedAttempts(): Int
    fun loadLockoutUntil(): Long
    fun loadLockoutCount(): Int
}

class PinManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPinValidated: ((String) -> String)? = null,
    private val lockoutPersistence: PinLockoutPersistence? = null,
    /**
     * Where PIN digits come from. The default is a cryptographically secure source: this PIN is the only
     * credential for the parent dashboard, which can add and remove approved content, so a predictable
     * generator would let anyone who saw one PIN work out the next. The parameter exists as a seam for
     * deterministic tests; production never passes one.
     */
    internal val randomSource: Random = SecureRandom(),
) {
    private var currentPin: String = generatePin()
    private var failedAttempts: Int = lockoutPersistence?.loadFailedAttempts() ?: 0
    private var lockoutUntil: Long = lockoutPersistence?.loadLockoutUntil() ?: 0
    private var lockoutCount: Int = lockoutPersistence?.loadLockoutCount() ?: 0

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val BASE_LOCKOUT_MS = 5 * 60 * 1000L // 5 minutes
        /** A PIN is always this many digits long. */
        private const val PIN_LENGTH = 6
        /** Each digit is drawn from 0..9. */
        private const val DIGITS = 10
    }

    fun generatePin(): String {
        // One digit per position, each drawn in 0..9, assembled as a string: a PIN is a sequence of
        // digits, never an integer, so a leading zero is a digit like any other and the result is always
        // six characters long.
        val pin = (1..PIN_LENGTH).map { randomSource.nextInt(DIGITS) }.joinToString("")
        currentPin = pin
        return pin
    }

    fun getCurrentPin(): String = currentPin

    fun validate(pin: String): PinResult {
        val now = clock()

        if (now < lockoutUntil) {
            return PinResult.RateLimited(lockoutUntil - now)
        }

        // Reset failed attempts counter when lockout has expired
        if (lockoutUntil > 0 && now >= lockoutUntil) {
            failedAttempts = 0
            lockoutUntil = 0
            persistLockout()
        }

        if (MessageDigest.isEqual(pin.toByteArray(), currentPin.toByteArray())) {
            // Fail closed. A correct PIN is an authentication only if a session can actually be issued
            // for it, and the counter reset belongs to that success rather than to merely matching.
            val issueSession = onPinValidated ?: return PinResult.NotConfigured
            val token = issueSession(pin)
            if (token.isBlank()) return PinResult.NotConfigured

            failedAttempts = 0
            lockoutCount = 0
            persistLockout()
            return PinResult.Success(token)
        }

        failedAttempts++
        if (failedAttempts >= MAX_ATTEMPTS) {
            lockoutCount++
            val multiplier = 1L shl (lockoutCount - 1).coerceAtMost(10)
            lockoutUntil = now + BASE_LOCKOUT_MS * multiplier
            persistLockout()
            return PinResult.RateLimited(BASE_LOCKOUT_MS * multiplier)
        }

        persistLockout()
        return PinResult.Invalid(MAX_ATTEMPTS - failedAttempts)
    }

    fun resetPin(): String {
        val newPin = generatePin()
        failedAttempts = 0
        lockoutCount = 0
        lockoutUntil = 0
        persistLockout()
        return newPin
    }

    fun isLockedOut(): Boolean = clock() < lockoutUntil

    fun getFailedAttempts(): Int = failedAttempts

    private fun persistLockout() {
        lockoutPersistence?.save(failedAttempts, lockoutUntil, lockoutCount)
    }
}
