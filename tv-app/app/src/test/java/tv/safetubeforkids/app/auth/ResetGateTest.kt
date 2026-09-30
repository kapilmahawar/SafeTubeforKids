package tv.safetubeforkids.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary in front of the destructive reset (W13.1b).
 *
 * The audit found that `SafeTubeReset.wipe` took no credential: the only thing between a child and a
 * wiped television was a phrase printed on the screen they were typing it into. The reset now requires
 * either parent credential, verified by the mechanisms that already exist, and this suite pins both
 * paths: what authorizes, what refuses, what is counted against which limiter, and - the part that
 * matters most - that no credential never authorizes anything.
 *
 * The Recovery Code is deliberately not rotated by authorizing: verifying a code is not using it. The
 * wipe destroys the credential store anyway, so rotation here would be busywork that also invalidated a
 * parent's paper for nothing.
 */
class ResetGateTest {

    /** The production wiring in miniature: one credential store, one session manager, one limiter pair. */
    private class Fixture(configure: Boolean = true, pin: String = TEST_PIN) {
        val store = InMemoryParentCredentialStore()
        val sessions = SessionManager()
        val pins = PinManager(
            store = store,
            onPinValidated = { sessions.createSession() ?: "" },
            hasher = TestHasher,
        )
        val recoveryCode: String? = if (configure) {
            (pins.setup(pin, pin) as PinSetupResult.Created).recoveryCode
        } else {
            null
        }
        val gate = ResetGate(pins)
    }

    private fun granted(result: ResetAuthorizationResult): ResetAuthorization {
        assertTrue("expected a grant, got $result", result is ResetAuthorizationResult.Granted)
        return (result as ResetAuthorizationResult.Granted).authorization
    }

    private fun refused(result: ResetAuthorizationResult): String {
        assertTrue("expected a refusal, got $result", result is ResetAuthorizationResult.Refused)
        return (result as ResetAuthorizationResult.Refused).message
    }

    // --- nothing authorizes without a credential -----------------------------

    @Test
    fun `no credential authorizes nothing`() {
        val fixture = Fixture()

        // Wrong PIN, wrong code, a short PIN and a malformed code: every shape of "I do not know the
        // credential" has to end in a refusal, and none of them may hand out a proof.
        listOf(
            fixture.gate.authorizeWithPin("000000"),
            fixture.gate.authorizeWithPin("123"),
            fixture.gate.authorizeWithPin(""),
            fixture.gate.authorizeWithRecoveryCode("0000-0000-0000"),
            fixture.gate.authorizeWithRecoveryCode("nonsense"),
        ).forEach { result ->
            assertTrue("a ResetAuthorization was handed out without a credential: $result",
                result is ResetAuthorizationResult.Refused)
        }
    }

    @Test
    fun `a tv with no credential refuses rather than allowing`() {
        // Fail closed. There is no parent to tell apart from anybody else on a TV that has no credential,
        // and since W10 the navigation host sends such a TV to onboarding to choose one.
        val fixture = Fixture(configure = false)

        assertEquals("This TV has no Parent PIN yet.", refused(fixture.gate.authorizeWithPin(TEST_PIN)))
        assertEquals("This TV has no Recovery Code.", refused(fixture.gate.authorizeWithRecoveryCode("000000000000")))
    }

    // --- the Parent PIN path -------------------------------------------------

    @Test
    fun `the right pin authorizes and leaves the existing session behind`() {
        val fixture = Fixture()

        val proof = granted(fixture.gate.authorizeWithPin(TEST_PIN))

        assertEquals(ResetAuthorization.Method.PARENT_PIN, proof.method)
        assertEquals("authorization is the existing session mechanism, not a new flag",
            1, fixture.sessions.getActiveSessionCount())
    }

    @Test
    fun `a wrong pin authorizes nothing and spends one of the existing attempts`() {
        val fixture = Fixture()

        val first = refused(fixture.gate.authorizeWithPin("999999"))
        assertTrue("the parent is told which credential failed: '$first'", first.contains("not the Parent PIN"))
        assertTrue("and how many tries remain: '$first'", first.contains("4 tries left"))

        assertFalse("a wrong PIN must not be a way in", fixture.gate.authorizeWithPin("888888")
            is ResetAuthorizationResult.Granted)
    }

    @Test
    fun `a pin that is not six digits is a typo, not a guess`() {
        val fixture = Fixture()

        val message = refused(fixture.gate.authorizeWithPin("123"))

        assertEquals("A Parent PIN is six digits.", message)
        assertTrue("no attempt was spent: the right PIN still works",
            fixture.pins.checkPin(TEST_PIN) is PinCheckResult.Verified)
    }

    @Test
    fun `the existing pin lockout applies to the reset too`() {
        val fixture = Fixture()

        repeat(AttemptLimiter.MAX_ATTEMPTS) { fixture.gate.authorizeWithPin("999999") }

        val locked = refused(fixture.gate.authorizeWithPin(TEST_PIN))
        assertTrue("the right PIN is refused while the lockout stands: '$locked'",
            locked.startsWith("Too many tries"))
        assertTrue("and the gate used the real limiter",
            fixture.pins.checkPin(TEST_PIN) is PinCheckResult.RateLimited)
    }

    // --- the Recovery Code path ----------------------------------------------

    @Test
    fun `the right recovery code authorizes`() {
        val fixture = Fixture()

        val proof = granted(fixture.gate.authorizeWithRecoveryCode(fixture.recoveryCode!!))

        assertEquals(ResetAuthorization.Method.RECOVERY_CODE, proof.method)
    }

    @Test
    fun `authorizing with the recovery code does not rotate it`() {
        // Verification is not use. The wipe destroys the credential store anyway, so rotating here would
        // only mean invalidating a parent's paper for nothing.
        val fixture = Fixture()

        granted(fixture.gate.authorizeWithRecoveryCode(fixture.recoveryCode!!))

        assertEquals("the code still works afterwards",
            RecoveryCheckResult.Verified, fixture.pins.verifyRecoveryCode(fixture.recoveryCode))
    }

    @Test
    fun `a malformed recovery code is a typo, not a guess`() {
        val fixture = Fixture()

        val message = refused(fixture.gate.authorizeWithRecoveryCode("ABC"))

        assertEquals("A Recovery Code is 12 characters, in three groups of four.", message)
        assertEquals("and it did not spend the code's attempts",
            RecoveryCheckResult.Verified, fixture.pins.verifyRecoveryCode(fixture.recoveryCode!!))
    }

    @Test
    fun `a well formed but wrong recovery code authorizes nothing`() {
        val fixture = Fixture()

        val message = refused(fixture.gate.authorizeWithRecoveryCode("000000000000"))

        assertEquals("That is not the Recovery Code.", message)
    }

    @Test
    fun `the recovery code has its own lockout, separate from the pin`() {
        val fixture = Fixture()

        repeat(AttemptLimiter.MAX_ATTEMPTS) { fixture.gate.authorizeWithRecoveryCode("000000000000") }

        val locked = refused(fixture.gate.authorizeWithRecoveryCode(fixture.recoveryCode!!))
        assertTrue("the code is refused while its lockout stands: '$locked'", locked.startsWith("Too many tries"))
        assertTrue("and the PIN is unaffected, because the two limiters are separate",
            fixture.gate.authorizeWithPin(TEST_PIN) is ResetAuthorizationResult.Granted)
    }

    @Test
    fun `a proof says which credential authorized it`() {
        // The wipe logs this, which is how a support conversation dates and attributes a reset.
        val fixture = Fixture()

        assertEquals(ResetAuthorization.Method.PARENT_PIN,
            granted(fixture.gate.authorizeWithPin(TEST_PIN)).method)
        assertEquals(ResetAuthorization.Method.RECOVERY_CODE,
            granted(fixture.gate.authorizeWithRecoveryCode(fixture.recoveryCode!!)).method)
    }
}
