package tv.safetubeforkids.app.auth

import java.security.SecureRandom
import java.util.Random

/** The outcome of a Parent PIN check that may issue a dashboard session. Unchanged from W7. */
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

    /**
     * This TV has no Parent PIN at all: it has never been set up, or a destructive reset removed the
     * credential. Distinct from [NotConfigured], which means "a PIN exists but no session can be
     * issued" - the dashboard answers these two differently, because one is the parent's first-run
     * setup and the other is a fault on the TV.
     */
    object NotSetUp : PinResult()
}

/** Creating the Parent PIN, once, during first-run setup. */
sealed class PinSetupResult {
    /** Created. [recoveryCode] is the one and only time this code is readable - show it, then forget it. */
    data class Created(val recoveryCode: String) : PinSetupResult()

    /** A Parent PIN already exists. It is never silently replaced. */
    object AlreadyConfigured : PinSetupResult()

    data class Invalid(val reason: String) : PinSetupResult()
}

/**
 * Is this the Parent PIN? No session, no side effects beyond the attempt counter.
 *
 * This exists for the TV's own screens, which need to know that the person holding the remote is the
 * parent - to allow the Recovery Code to be replaced, for instance - without signing anybody in. The
 * dashboard's sign-in path is [PinManager.validate], which is this plus a session.
 */
sealed class PinCheckResult {
    object Verified : PinCheckResult()
    object NotSetUp : PinCheckResult()
    object Wrong : PinCheckResult()
    data class RateLimited(val retryAfterMs: Long) : PinCheckResult()
}

/** Changing the Parent PIN, which requires knowing the current one. */
sealed class PinChangeResult {
    object Changed : PinChangeResult()
    object NotSetUp : PinChangeResult()
    object WrongPin : PinChangeResult()
    data class RateLimited(val retryAfterMs: Long) : PinChangeResult()
    data class Invalid(val reason: String) : PinChangeResult()
}

/** Checking a Recovery Code. */
sealed class RecoveryCheckResult {
    object Verified : RecoveryCheckResult()
    object NotSetUp : RecoveryCheckResult()
    object NotWellFormed : RecoveryCheckResult()
    object Unknown : RecoveryCheckResult()
    data class RateLimited(val retryAfterMs: Long) : RecoveryCheckResult()
}

/** Resetting the Parent PIN with a Recovery Code. */
sealed class PinResetResult {
    /**
     * The PIN was replaced. The old Recovery Code is gone and [recoveryCode] replaces it - the caller
     * must put this in front of the parent, on the TV, or it is lost.
     */
    data class Reset(val recoveryCode: String) : PinResetResult()

    object NotSetUp : PinResetResult()
    object Unknown : PinResetResult()
    data class Invalid(val reason: String) : PinResetResult()
}

/**
 * The parent's access credential: one persistent Parent PIN, and the Recovery Code that can replace it.
 *
 * **What changed in W10, and why it matters.** This class used to generate a random six-digit PIN in
 * its constructor and show it on the TV. Every app start produced a new one, so the "PIN" was really a
 * one-session pairing code that happened to look like a PIN - which is why it had to be printed on the
 * television and encoded in the pairing QR code, and why an app restart silently invalidated whatever
 * the parent had written down. There is now exactly one Parent PIN, chosen by the parent, verifiable
 * for as long as the TV lives, and never readable again - not by an API, not by a debug intent, not by
 * the TV screen. What the TV can show instead is a Recovery Code, and only at the moment it is
 * generated.
 *
 * **What this class is not.** It is not playback authorization, and it cannot become it. Nothing here
 * adds a row to `channels` or `videos`, nothing here is consulted by `PlaybackAuthorization`, and a
 * successful [validate] returns a dashboard session and nothing else. A parent who signs in can change
 * what the *child sees* only through the catalog and the approved sources, exactly as before; a
 * recovery code, a PIN or a session that was never used to approve a source still plays nothing.
 * `ParentAccessTest` and `ParentAccessSecurityTest` assert precisely that, including the negative
 * direction: no credential operation can make an unapproved video playable.
 */
class PinManager(
    private val store: ParentCredentialStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPinValidated: ((String) -> String)? = null,
    /**
     * Called after the credential itself changes - a new PIN, a reset, a destructive wipe. The wiring
     * turns this into "invalidate every dashboard session", which is the requirement that makes a PIN
     * change mean something: a session issued under the old credential must not outlive it.
     */
    private val onCredentialsChanged: (() -> Unit)? = null,
    private val hasher: SecretHasher = Pbkdf2SecretHasher(),
    pinLockoutPersistence: PinLockoutPersistence? = null,
    recoveryLockoutPersistence: PinLockoutPersistence? = null,
    /**
     * Where Recovery Code characters come from. Cryptographically secure by default; the parameter
     * exists as a seam for deterministic tests, exactly as the old PIN generator's did.
     */
    internal val randomSource: Random = SecureRandom(),
) {
    private val pinAttempts = AttemptLimiter(clock, pinLockoutPersistence)
    private val recoveryAttempts = AttemptLimiter(clock, recoveryLockoutPersistence)

    /**
     * The last Recovery Code generated, held in memory only.
     *
     * This is the one concession the "store a verifier, never the secret" rule has to make, and it is
     * bounded deliberately: the code has to be *shown* to the parent exactly once, and a hash cannot be
     * shown. It lives here rather than on disk, it is dropped the moment the parent acknowledges it,
     * and it dies with the process - so a TV that is switched off overnight does not keep a readable
     * recovery secret lying around, and no API can serve it after the fact.
     */
    private var pendingRecoveryCode: String? = null

    fun isConfigured(): Boolean = store.isConfigured()

    /** The Recovery Code waiting to be written down, or null when there is nothing to show. */
    fun pendingRecoveryCode(): String? = pendingRecoveryCode

    /** The parent has written it down (or dismissed it): stop holding it in memory. */
    fun acknowledgeRecoveryCode() {
        pendingRecoveryCode = null
    }

    /**
     * First-run setup: the parent chooses their PIN, and this TV issues them a Recovery Code.
     *
     * Refuses to run twice. That is the requirement "if a PIN already exists, never silently
     * regenerate it" enforced where it cannot be forgotten - an app that regenerated on startup is
     * exactly the defect W10 removes, and a second call here would reintroduce it one layer down.
     */
    fun setup(pin: String, confirm: String? = null): PinSetupResult {
        if (isConfigured()) return PinSetupResult.AlreadyConfigured

        val problem = pinProblem(pin, confirm)
        if (problem != null) return PinSetupResult.Invalid(problem)

        store.writePin(hasher.hash(pin))
        pinAttempts.recordSuccess()

        val recoveryCode = issueRecoveryCode()
        onCredentialsChanged?.invoke()
        return PinSetupResult.Created(recoveryCode)
    }

    /**
     * Is this the Parent PIN? Counts a wrong answer against the limiter, issues nothing.
     */
    fun checkPin(pin: String): PinCheckResult {
        val stored = store.readPin() ?: return PinCheckResult.NotSetUp

        val retryAfter = pinAttempts.retryAfterMs()
        if (retryAfter > 0) return PinCheckResult.RateLimited(retryAfter)

        if (!hasher.verify(pin, stored)) {
            val lockout = pinAttempts.recordFailure()
            return if (lockout > 0) PinCheckResult.RateLimited(lockout) else PinCheckResult.Wrong
        }

        pinAttempts.recordSuccess()
        return PinCheckResult.Verified
    }

    /**
     * Sign in: the PIN check, plus a dashboard session for it.
     *
     * Unchanged in shape from the ephemeral model, including the part that matters most - a correct
     * PIN is an authentication only if a session can actually be issued for it. A blank token is
     * reported as [PinResult.NotConfigured] rather than as a success carrying nothing.
     */
    fun validate(pin: String): PinResult = when (val check = checkPin(pin)) {
        is PinCheckResult.Verified -> {
            val issueSession = onPinValidated ?: return PinResult.NotConfigured
            val token = issueSession(pin)
            if (token.isBlank()) PinResult.NotConfigured else PinResult.Success(token)
        }
        is PinCheckResult.NotSetUp -> PinResult.NotSetUp
        is PinCheckResult.Wrong -> PinResult.Invalid(pinAttempts.attemptsRemaining())
        is PinCheckResult.RateLimited -> PinResult.RateLimited(check.retryAfterMs)
    }

    /**
     * Change the PIN. Requires the current one, so a session that leaked is not enough on its own to
     * take the TV over, and the Recovery Code is deliberately left alone: a parent changing their PIN
     * has not lost their paper.
     */
    fun changePin(currentPin: String, newPin: String, confirm: String? = null): PinChangeResult {
        val stored = store.readPin() ?: return PinChangeResult.NotSetUp

        val retryAfter = pinAttempts.retryAfterMs()
        if (retryAfter > 0) return PinChangeResult.RateLimited(retryAfter)

        if (!hasher.verify(currentPin, stored)) {
            val lockout = pinAttempts.recordFailure()
            return if (lockout > 0) PinChangeResult.RateLimited(lockout) else PinChangeResult.WrongPin
        }

        val problem = pinProblem(newPin, confirm)
        if (problem != null) return PinChangeResult.Invalid(problem)

        store.writePin(hasher.hash(newPin))
        pinAttempts.recordSuccess()
        onCredentialsChanged?.invoke()
        return PinChangeResult.Changed
    }

    /**
     * Is this the Recovery Code? Well-formedness is checked before the derivation, and a
     * malformed code is not counted as a failed attempt - a parent typing `8K4P7M2Q91T` (one
     * character short) has made a typo, not a guess.
     */
    fun verifyRecoveryCode(code: String): RecoveryCheckResult {
        val stored = store.readRecovery() ?: return RecoveryCheckResult.NotSetUp
        if (!RecoveryCode.isWellFormed(code)) return RecoveryCheckResult.NotWellFormed

        val retryAfter = recoveryAttempts.retryAfterMs()
        if (retryAfter > 0) return RecoveryCheckResult.RateLimited(retryAfter)

        if (!hasher.verify(RecoveryCode.normalise(code), stored)) {
            val lockout = recoveryAttempts.recordFailure()
            return if (lockout > 0) RecoveryCheckResult.RateLimited(lockout) else RecoveryCheckResult.Unknown
        }

        recoveryAttempts.recordSuccess()
        return RecoveryCheckResult.Verified
    }

    /**
     * Forgotten PIN, Recovery Code in hand: replace the PIN and rotate the code.
     *
     * Rotation is not optional. A Recovery Code that survived its own use would be a permanent second
     * password that the parent cannot change from memory - so the one they just used is invalidated in
     * the same breath as the PIN it replaced, and a new one is issued for them to write down.
     */
    fun resetWithRecovery(code: String, newPin: String, confirm: String? = null): PinResetResult {
        when (val check = verifyRecoveryCode(code)) {
            RecoveryCheckResult.Verified -> Unit
            RecoveryCheckResult.NotSetUp -> return PinResetResult.NotSetUp
            RecoveryCheckResult.Unknown, RecoveryCheckResult.NotWellFormed -> return PinResetResult.Unknown
            is RecoveryCheckResult.RateLimited -> return PinResetResult.Invalid(
                "Too many tries. Wait " + seconds(check.retryAfterMs) + " and try again."
            )
        }

        val problem = pinProblem(newPin, confirm)
        if (problem != null) return PinResetResult.Invalid(problem)

        store.writePin(hasher.hash(newPin))
        pinAttempts.recordSuccess()

        val newCode = issueRecoveryCode()
        onCredentialsChanged?.invoke()
        return PinResetResult.Reset(newCode)
    }

    /**
     * A fresh Recovery Code, for a parent who knows their PIN and wants to replace the code they
     * cannot find. The caller must already have authenticated them - this class issues codes, it does
     * not decide who may ask for one.
     */
    fun rotateRecoveryCode(): String {
        val code = issueRecoveryCode()
        return code
    }

    /**
     * The destructive reset's own door, called only by `SafeTubeReset`: no PIN, no Recovery Code, no
     * configured flag, no attempt counters. Everything this class owns, gone.
     */
    fun clearAll() {
        store.clear()
        pendingRecoveryCode = null
        pinAttempts.recordSuccess()
        recoveryAttempts.recordSuccess()
    }

    fun isLockedOut(): Boolean = pinAttempts.isLockedOut()

    fun getFailedAttempts(): Int = pinAttempts.failedAttempts()

    private fun issueRecoveryCode(): String {
        val code = RecoveryCode.generate(randomSource)
        store.writeRecovery(hasher.hash(RecoveryCode.normalise(code)))
        pendingRecoveryCode = code
        return code
    }

    private fun pinProblem(pin: String, confirm: String?): String? {
        if (!isSixDigits(pin)) return "A Parent PIN is six digits."
        if (confirm != null && confirm != pin) return "The two PINs are not the same."
        return null
    }

    private fun seconds(ms: Long): String {
        val minutes = (ms + 59_999) / 60_000
        return if (minutes <= 1) "a minute" else "$minutes minutes"
    }

    companion object {
        const val PIN_LENGTH = 6

        fun isSixDigits(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it.isDigit() }
    }
}
