package tv.safetubeforkids.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Parent PIN gate in front of the TV's destructive Settings actions (W13.1).
 *
 * The defect these pin down: `Clear Events` used to call `PlayEventRecorder.clearAll()` from a plain
 * `onClick` on a screen a child can reach, and `play_events` is what the daily limit and bedtime are
 * counted from - so two presses reset the day's screen time. `Sign Out All Sessions` did the same to
 * every parent session.
 *
 * What is asserted here is the whole contract: nothing is performed without the PIN, the right PIN
 * performs exactly one action, the wrong PIN performs nothing and spends the *existing* persisted
 * attempt and lockout, a short PIN is a typo rather than a guess, and no state of any kind is left
 * behind that would let a second attempt skip the check.
 */
class ParentGateTest {

    /** Counts what the gate performed, so "nothing happened" can be asserted rather than assumed. */
    private class Mutations {
        var watchTimeResets = 0
        var signOuts = 0
    }

    /**
     * The production wiring in miniature: one credential store, one session manager, and a PIN checked
     * through the same `onPinValidated` hook `ServiceLocator` installs.
     *
     * That hook is not decoration. `PinManager.validate` reports `NotConfigured` when nothing issues a
     * session, so a manager built without it can never authorize anything - and a test that skipped it
     * would be testing a television no parent could use.
     */
    private class Fixture(val store: InMemoryParentCredentialStore = InMemoryParentCredentialStore()) {
        val sessions = SessionManager()
        val mutations = Mutations()
        val pins: PinManager = testPinManager(
            store = store,
            onPinValidated = { sessions.createSession() ?: "" },
        )
        val gate: ParentGate = ParentGate(
            pinManager = pins,
            resetWatchTime = { mutations.watchTimeResets++ },
            signOutParentSessions = { mutations.signOuts++ },
        )
    }

    // --- A. no authorization, no mutation -----------------------------------

    @Test
    fun `without the pin nothing is performed`() {
        val fixture = Fixture()

        val result = fixture.gate.run(ParentOnlyAction.RESET_WATCH_TIME, "000000")

        assertTrue("a wrong PIN must be refused", result is ParentGateResult.Refused)
        assertEquals("the watch history must be untouched", 0, fixture.mutations.watchTimeResets)
        assertEquals("and nothing else may run either", 0, fixture.mutations.signOuts)
    }

    @Test
    fun `a tv with no parent pin refuses rather than allowing`() {
        // Fail closed. A TV with no PIN cannot tell a parent from anybody else, and since W10 the
        // navigation host sends such a TV to onboarding to choose one - so refusing costs nothing.
        val mutations = Mutations()
        val unconfigured = PinManager(store = InMemoryParentCredentialStore(), hasher = TestHasher)
        val gate = ParentGate(
            pinManager = unconfigured,
            resetWatchTime = { mutations.watchTimeResets++ },
            signOutParentSessions = { mutations.signOuts++ },
        )

        val result = gate.run(ParentOnlyAction.RESET_WATCH_TIME, TEST_PIN)

        assertEquals(
            "the parent is told why, not shown a success",
            "This TV has no Parent PIN yet.",
            (result as ParentGateResult.Refused).message,
        )
        assertEquals(0, mutations.watchTimeResets)
        assertEquals(0, mutations.signOuts)
    }

    // --- B. the right PIN performs the action --------------------------------

    @Test
    fun `the right pin performs the action and leaves the existing session behind`() {
        val fixture = Fixture()

        val result = fixture.gate.run(ParentOnlyAction.RESET_WATCH_TIME, TEST_PIN)

        assertTrue("the right PIN must authorize", result is ParentGateResult.Authorized)
        assertEquals("the watch history is cleared once", 1, fixture.mutations.watchTimeResets)
        assertEquals("and the other action is not run", 0, fixture.mutations.signOuts)
        assertEquals(
            "authentication is the existing session mechanism, not a new flag",
            1,
            fixture.sessions.getActiveSessionCount(),
        )
    }

    @Test
    fun `each action runs only its own mutation`() {
        val fixture = Fixture()

        fixture.gate.run(ParentOnlyAction.SIGN_OUT_SESSIONS, TEST_PIN)

        assertEquals("signing out must not erase the watch history", 0, fixture.mutations.watchTimeResets)
        assertEquals(1, fixture.mutations.signOuts)
    }

    @Test
    fun `signing out ends with no session left, including the one the gate just made`() {
        val fixture = Fixture()
        val gateOnRealSessions = ParentGate(
            pinManager = fixture.pins,
            resetWatchTime = {},
            signOutParentSessions = { fixture.sessions.invalidateAll() },
        )

        val result = gateOnRealSessions.run(ParentOnlyAction.SIGN_OUT_SESSIONS, TEST_PIN)

        assertTrue(result is ParentGateResult.Authorized)
        assertEquals("the parent asked for every session to go", 0, fixture.sessions.getActiveSessionCount())
    }

    // --- C. the wrong PIN ----------------------------------------------------

    @Test
    fun `a wrong pin performs nothing and says how many tries are left`() {
        val fixture = Fixture()

        val first = fixture.gate.run(ParentOnlyAction.RESET_WATCH_TIME, "111111") as ParentGateResult.Refused
        assertTrue("the parent is told it was not the PIN", first.message.contains("not the Parent PIN"))
        assertTrue("and how many tries remain: '${first.message}'", first.message.contains("4 tries left"))

        val second = fixture.gate.run(ParentOnlyAction.RESET_WATCH_TIME, "222222") as ParentGateResult.Refused
        assertTrue("the count goes down: '${second.message}'", second.message.contains("3 tries left"))

        assertEquals(0, fixture.mutations.watchTimeResets)
        assertEquals(0, fixture.mutations.signOuts)
    }

    @Test
    fun `a pin that is not six digits is a typo, not a guess`() {
        // The same distinction `verifyRecoveryCode` makes for a malformed Recovery Code: a parent who
        // typed three digits has made a mistake, and it must not spend one of the five attempts.
        val fixture = Fixture()

        val result = fixture.gate.run(ParentOnlyAction.RESET_WATCH_TIME, "123")

        assertEquals("A Parent PIN is six digits.", (result as ParentGateResult.Refused).message)
        assertEquals(0, fixture.mutations.watchTimeResets)
        assertTrue(
            "no attempt was spent: the right PIN still works",
            fixture.pins.checkPin(TEST_PIN) is PinCheckResult.Verified,
        )
    }

    // --- E. the existing lockout, not a new one ------------------------------

    @Test
    fun `the existing pin lockout applies to this gate too`() {
        val fixture = Fixture()

        // Five wrong answers is the existing limit (`AttemptLimiter.MAX_ATTEMPTS`).
        repeat(AttemptLimiter.MAX_ATTEMPTS) {
            fixture.gate.run(ParentOnlyAction.RESET_WATCH_TIME, "999999")
        }

        val lockedOut = fixture.gate.run(ParentOnlyAction.RESET_WATCH_TIME, TEST_PIN) as ParentGateResult.Refused
        assertTrue(
            "the right PIN is refused while the lockout stands: '${lockedOut.message}'",
            lockedOut.message.startsWith("Too many tries"),
        )
        assertEquals("and nothing was performed", 0, fixture.mutations.watchTimeResets)
        assertEquals(0, fixture.mutations.signOuts)

        assertTrue(
            "the gate used the real limiter, so the manager is locked out too",
            fixture.pins.checkPin(TEST_PIN) is PinCheckResult.RateLimited,
        )
    }

    // --- F. nothing survives that would skip the check -----------------------

    @Test
    fun `a second gate over the same credentials asks again`() {
        val fixture = Fixture()
        val before = fixture.store.readPin()

        assertTrue(
            "the first, authorized attempt runs the action",
            fixture.gate.run(ParentOnlyAction.RESET_WATCH_TIME, TEST_PIN) is ParentGateResult.Authorized,
        )

        // A fresh instance - what a process restart produces - must not remember that anything was
        // authorized. There is no "the parent is here" state to expire, because there is none to keep.
        val afterRestart = ParentGate(
            pinManager = fixture.pins,
            resetWatchTime = { fixture.mutations.watchTimeResets++ },
            signOutParentSessions = { fixture.mutations.signOuts++ },
        ).run(ParentOnlyAction.RESET_WATCH_TIME, "123456")

        assertTrue("a wrong PIN is still refused afterwards", afterRestart is ParentGateResult.Refused)
        assertEquals("only the first, authorized action ran", 1, fixture.mutations.watchTimeResets)
        assertEquals("authorizing wrote nothing to the credential store", before, fixture.store.readPin())
    }

    // --- G. no alternate route to the mutation -------------------------------

    @Test
    fun `the gate has no way to perform an action without a pin`() {
        // The defect was a screen that reached into a singleton by itself. The guard against that
        // coming back is structural: this class's only public operation takes a PIN.
        val publicMethods = ParentGate::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSortedSet()

        assertEquals("only run(pin) may perform anything", setOf("run"), publicMethods)
    }

    @Test
    fun `every gated action states what it does and how it is classified`() {
        // A change detector with a purpose: adding a destructive Settings action means adding it here,
        // in front of the gate, and writing down why - rather than wiring another onClick.
        assertEquals(
            "the silently destructive actions are the two the gate owns",
            setOf(ParentOnlyAction.RESET_WATCH_TIME, ParentOnlyAction.SIGN_OUT_SESSIONS),
            ParentOnlyAction.entries.toSet(),
        )
        ParentOnlyAction.entries.forEach { action ->
            assertFalse("${action.name} must name itself", action.label.isBlank())
            assertTrue(
                "${action.name} must say what it does, so the prompt can: '${action.reason}'",
                action.reason.length > 20,
            )
        }
    }
}
