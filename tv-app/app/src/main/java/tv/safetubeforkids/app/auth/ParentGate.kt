package tv.safetubeforkids.app.auth

/**
 * A TV Settings action that changes protected state, and therefore needs the Parent PIN.
 *
 * **Why this exists.** Until W13.1 the Settings screen performed its destructive work itself, with a
 * plain `onClick`. Two of those actions were one press away from a child holding the remote:
 *
 *  - `Clear Events` called `PlayEventRecorder.clearAll()`, which deletes `play_events` - and
 *    `play_events` is exactly what the daily limit and bedtime are counted from
 *    (`RoomWatchTimeProvider.getTodayWatchSeconds` → `PlayEventDao.sumDurationToday` →
 *    `TimeLimitManager.canPlay`). A child could therefore reset the day's screen time in two presses
 *    and keep watching, which is a hole in a parent-configured safety control, not a cosmetic one;
 *  - `Sign Out All Sessions` invalidated every parent session.
 *
 * **The classification of every action on that screen**, so that "why is this gated" is written down
 * rather than inferred:
 *
 * | action | class | why |
 * | --- | --- | --- |
 * | version text, "Parent PIN: set / not set up yet", "Active sessions: N", recovery-code state | READ_ONLY | shows state, changes nothing |
 * | `Refresh Videos` | CHILD_SAFE_MUTATION | asks the TV to re-fetch its catalog and approved sources; it deletes nothing, approves nothing, and cannot change what may play |
 * | `Recovery Code` | PARENT_ONLY_MUTATION, **already gated** | `RecoveryCodeScreen` asks for the current Parent PIN before it rotates the code |
 * | `Sign Out All Sessions` | PARENT_ONLY_MUTATION | invalidates every parent session; gated here |
 * | `Clear Watch History` (was `Clear Events`) | PARENT_ONLY_MUTATION | deletes the watch history the time limits count; gated here, and the defect this class was written for |
 * | `Reset SafeTube` | PARENT_ONLY_MUTATION, **deliberately left with its existing confirmation** | it is the documented last resort for a parent who has lost *both* the PIN and the Recovery Code (`ResetSafeTubeScreen` and `SafeTubeReset` say so in as many words, and there is deliberately no HTTP route that can wipe anything). Its barrier is a typed five-word phrase plus a second, separate question - a reading of intent, not a button press. Putting a credential in front of it would delete the only way back in for the person that screen exists for. Changing that is a product decision, recorded in `docs/W13_1_VERIFICATION.md`, not something this phase should settle by accident |
 * | the `Debug` row (offline simulator, log panel) | debug builds only | `BuildConfig.IS_DEBUG`; not present in a build a family installs |
 */
enum class ParentOnlyAction(
    /** What the parent is authorizing, in the words the screen uses. */
    val label: String,
    /** One line under the prompt's title, so the prompt says what is about to happen. */
    val reason: String,
) {
    RESET_WATCH_TIME(
        label = "Clear Watch History",
        reason = "Resets the watch time that today's limit and bedtime are counted from.",
    ),

    SIGN_OUT_SESSIONS(
        label = "Sign Out All Sessions",
        reason = "Signs every parent phone out of this TV.",
    ),
}

/** The outcome of asking for parent authorization, and of the action that authorization allowed. */
sealed class ParentGateResult {
    /** The PIN was right, and the action has been performed. */
    object Authorized : ParentGateResult()

    /** Nothing was performed. [message] is written for a parent to read on the television. */
    data class Refused(val message: String) : ParentGateResult()
}

/**
 * The single boundary in front of the TV's parent-only mutations.
 *
 * **One place, not one check per button.** The PIN is verified here, and the mutation is performed
 * here, in the same call: there is no method that performs an action without a PIN argument, and no
 * caller can perform one for itself. That matters because the defect being fixed was precisely a
 * screen that reached into a singleton and deleted rows by itself - the mutation has to be behind the
 * gate, not merely hidden behind it.
 *
 * **What it reuses, and what it deliberately does not add.** Verification is
 * [PinManager.validate], which is the same call the parent dashboard signs in with: it runs the
 * existing `checkPin`, so a wrong PIN spends the existing persisted attempt and a fifth one starts the
 * existing escalating lockout, and a right PIN issues an existing dashboard session through
 * `PinManager`'s own `onPinValidated` wiring. There is no second PIN, no second limiter, no
 * `isParent` flag, and nothing new persisted - a process restart leaves exactly the state the existing
 * session and credential rules describe.
 *
 * A PIN that is not six digits is refused as a typo rather than counted as a guess, which is the same
 * distinction `verifyRecoveryCode` makes for a malformed Recovery Code.
 *
 * This class has nothing to do with playback. It cannot add a row to `channels` or `videos`, it is not
 * consulted by `PlaybackAuthorization`, and a parent who authorizes these actions still plays exactly
 * what they could play before.
 */
class ParentGate(
    private val pinManager: PinManager,
    private val resetWatchTime: () -> Unit,
    private val signOutParentSessions: () -> Unit,
) {

    /**
     * Verify [pin] and, only if it is the Parent PIN, perform [action].
     *
     * Blocking: PBKDF2 is slow on purpose, so callers must not run this on the main thread - the
     * Settings screen does exactly what the recovery screen does and calls it from `Dispatchers.Default`.
     */
    fun run(action: ParentOnlyAction, pin: String): ParentGateResult {
        if (!PinManager.isSixDigits(pin)) {
            return ParentGateResult.Refused(NOT_SIX_DIGITS)
        }

        return when (val check = pinManager.validate(pin)) {
            is PinResult.Success -> {
                perform(action)
                ParentGateResult.Authorized
            }

            is PinResult.Invalid -> ParentGateResult.Refused(
                if (check.attemptsRemaining <= 0) TOO_MANY_TRIES else "$WRONG_PIN ${triesLeft(check.attemptsRemaining)}"
            )

            is PinResult.RateLimited -> ParentGateResult.Refused(waitMessage(check.retryAfterMs))

            // Fail closed. A TV with no PIN has no parent to tell apart from anybody else, and since
            // W10 the navigation host sends such a TV to onboarding to choose one - so refusing here
            // costs a family nothing, while allowing here would be the old hole with a new name.
            PinResult.NotSetUp, PinResult.NotConfigured -> ParentGateResult.Refused(NO_PIN)
        }
    }

    /**
     * The mutations, and the only place they are called from.
     *
     * `resetWatchTime` and `signOutParentSessions` are the production bodies
     * (`PlayEventRecorder.clearAll` and `SessionManager.invalidateAll`), injected by `ServiceLocator`
     * so that this class can be tested without a database or a device.
     */
    private fun perform(action: ParentOnlyAction) = when (action) {
        ParentOnlyAction.RESET_WATCH_TIME -> resetWatchTime()
        ParentOnlyAction.SIGN_OUT_SESSIONS -> signOutParentSessions()
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
    }
}
