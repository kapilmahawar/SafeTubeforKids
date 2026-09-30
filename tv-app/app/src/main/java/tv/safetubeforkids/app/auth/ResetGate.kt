package tv.safetubeforkids.app.auth

/**
 * Proof that a parent authorized the destructive reset.
 *
 * **Why a type instead of a boolean.** `SafeTubeReset.wipe` used to take nothing but a `Context`, so
 * every caller was authorized by definition - the audit (W13.1b) found the destructive wipe reachable
 * with no credential check anywhere below the screen. The wipe now *requires* one of these, and the only
 * thing that can produce one is [ResetGate] after a credential has been verified. A future caller that
 * wants to wipe a television has to obtain a verified parent credential first, or the code does not
 * compile.
 *
 * `internal` rather than `private`, honestly: Kotlin has no way to say "only this file may construct
 * this", so module visibility is the strongest constraint available without adding a Gradle module for
 * one class. What closes the gap is a test rather than the compiler - `ResetMutationBoundaryTest`
 * asserts that this constructor is named in exactly one file, so a second construction site fails CI
 * instead of quietly adding a way around the credential check.
 */
class ResetAuthorization internal constructor(
    /** Which credential proved the parent's authority, for the audit line the wipe writes. */
    internal val method: Method,
) {
    enum class Method { PARENT_PIN, RECOVERY_CODE }
}

/** The outcome of asking a parent to authorize the destructive reset. */
sealed class ResetAuthorizationResult {
    /** The credential was right. The reset may now proceed to its own confirmation. */
    data class Granted(val authorization: ResetAuthorization) : ResetAuthorizationResult()

    /** Nothing is authorized. [message] is written for a parent to read on the television. */
    data class Refused(val message: String) : ResetAuthorizationResult()
}

/**
 * The one boundary in front of the destructive reset.
 *
 * **Both credentials the product already recognises.** A Parent PIN or a Recovery Code, verified by the
 * mechanisms that already exist - [PinManager.validate] and [PinManager.verifyRecoveryCode], each with
 * its own persisted attempt counter and its own escalating lockout. Nothing new is introduced: no third
 * secret, no second limiter, no second session type, and no change to either credential's rules. The
 * Recovery Code is not rotated here: verification is not use, and the reset destroys the credential
 * store anyway.
 *
 * **Authorization happens before the mutation, and cannot happen inside it.** The wipe clears the
 * credential store and both attempt counters, so a check performed at or after the wipe would be
 * checking state it had already deleted. This gate therefore runs first and hands out a proof; the wipe
 * takes that proof and does not look at the credential store at all.
 *
 * **What this is not.** It is not playback authorization and cannot become it: nothing here is consulted
 * by `PlaybackAuthorization`, and a parent who authorizes a reset still plays exactly what they could
 * play before until the wipe actually runs.
 */
class ResetGate(private val pinManager: PinManager) {

    /**
     * The Parent PIN path.
     *
     * Verification is [PinManager.validate], the same call the dashboard signs in with, so a wrong PIN
     * spends the existing persisted attempt, a fifth one starts the existing lockout, and a right PIN
     * issues an existing dashboard session. A PIN that is not six digits is refused as a typo without
     * spending an attempt, which is the same distinction the recovery screen makes.
     */
    fun authorizeWithPin(pin: String): ResetAuthorizationResult {
        if (!PinManager.isSixDigits(pin)) {
            return ResetAuthorizationResult.Refused(NOT_SIX_DIGITS)
        }

        return when (val check = pinManager.validate(pin)) {
            is PinResult.Success -> ResetAuthorizationResult.Granted(
                ResetAuthorization(ResetAuthorization.Method.PARENT_PIN)
            )

            is PinResult.Invalid -> ResetAuthorizationResult.Refused(
                if (check.attemptsRemaining <= 0) TOO_MANY_TRIES
                else "$WRONG_PIN ${triesLeft(check.attemptsRemaining)}"
            )

            is PinResult.RateLimited -> ResetAuthorizationResult.Refused(waitMessage(check.retryAfterMs))

            // Fail closed. A TV with no PIN has no parent to tell apart from anybody else, and since W10
            // the navigation host sends such a TV to onboarding to choose one - so refusing costs a
            // family nothing, while allowing here would be the old hole with a new name.
            PinResult.NotSetUp, PinResult.NotConfigured -> ResetAuthorizationResult.Refused(NO_PIN)
        }
    }

    /**
     * The Recovery Code path.
     *
     * Verification is [PinManager.verifyRecoveryCode] - the same call the dashboard's "forgot PIN" flow
     * makes - so a mis-typed code is not counted as a guess (`NotWellFormed`), a wrong one spends the
     * Recovery Code's own persisted attempt, and its lockout is separate from the PIN's. That the code
     * grants this authority is not new: the product already treats holding it as sufficient proof of a
     * parent, because it is what replaces a forgotten PIN.
     */
    fun authorizeWithRecoveryCode(code: String): ResetAuthorizationResult {
        return when (val check = pinManager.verifyRecoveryCode(code)) {
            RecoveryCheckResult.Verified -> ResetAuthorizationResult.Granted(
                ResetAuthorization(ResetAuthorization.Method.RECOVERY_CODE)
            )

            RecoveryCheckResult.NotWellFormed -> ResetAuthorizationResult.Refused(NOT_WELL_FORMED)
            RecoveryCheckResult.Unknown -> ResetAuthorizationResult.Refused(WRONG_CODE)
            is RecoveryCheckResult.RateLimited -> ResetAuthorizationResult.Refused(waitMessage(check.retryAfterMs))
            RecoveryCheckResult.NotSetUp -> ResetAuthorizationResult.Refused(NO_CODE)
        }
    }

    private fun triesLeft(remaining: Int): String =
        if (remaining == 1) "One try left before a wait." else "$remaining tries left before a wait."

    private fun waitMessage(retryAfterMs: Long): String {
        val minutes = ((retryAfterMs + 59_999L) / 60_000L).coerceAtLeast(1L)
        val wait = if (minutes == 1L) "a minute" else "$minutes minutes"
        return "Too many tries. Wait $wait and try again."
    }

    private companion object {
        const val NOT_SIX_DIGITS = "A Parent PIN is six digits."
        const val WRONG_PIN = "That is not the Parent PIN."
        const val TOO_MANY_TRIES = "Too many tries. Wait a few minutes and try again."
        const val NO_PIN = "This TV has no Parent PIN yet."
        const val NOT_WELL_FORMED = "A Recovery Code is 12 characters, in three groups of four."
        const val WRONG_CODE = "That is not the Recovery Code."
        const val NO_CODE = "This TV has no Recovery Code."
    }
}
