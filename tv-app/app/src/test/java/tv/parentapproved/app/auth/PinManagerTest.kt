package tv.safetubeforkids.app.auth

import java.security.SecureRandom
import java.util.Random
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * The Parent PIN as a persistent credential, and the Recovery Code that can replace it.
 *
 * This was `PinManagerTest`, and it used to test a generator: a PIN that was invented on every app
 * start and shown on the television. W10 replaced that model, and these tests were rewritten around
 * what replaced it rather than deleted - the lockout policy, the fail-closed rule and the counter
 * persistence are the same behaviour they always were, now applied to a credential the parent chose.
 *
 * The iteration count is lowered here (and only here) so the suite stays fast: 120,000 PBKDF2 rounds
 * per assertion would make this file take minutes, and the cost is a property of `Pbkdf2SecretHasher`,
 * not of the rules under test.
 */
class PinManagerTest {

    private var currentTime = 0L
    private lateinit var store: InMemoryParentCredentialStore
    private lateinit var pinManager: PinManager

    private val fastHasher = Pbkdf2SecretHasher(iterations = 1_000)

    @Before
    fun setup() {
        currentTime = 1000000L
        store = InMemoryParentCredentialStore()
        pinManager = PinManager(
            store = store,
            clock = { currentTime },
            onPinValidated = { "test-session-token" },
            hasher = fastHasher,
        )
        pinManager.setup(PIN)
    }

    // --- first run ----------------------------------------------------------------------------------

    @Test
    fun aFreshDeviceHasNoParentPinAndSaysSo() {
        val fresh = PinManager(store = InMemoryParentCredentialStore(), clock = { currentTime }, hasher = fastHasher)

        assertFalse("nothing is configured before setup", fresh.isConfigured())
        assertTrue(
            "and a PIN cannot be validated on a device that has none",
            fresh.validate(PIN) is PinResult.NotSetUp,
        )
    }

    @Test
    fun setupCreatesTheCredentialAndIssuesARecoveryCode() {
        val fresh = PinManager(store = InMemoryParentCredentialStore(), clock = { currentTime }, hasher = fastHasher)

        val result = fresh.setup(PIN, PIN)

        assertTrue(result is PinSetupResult.Created)
        assertTrue(fresh.isConfigured())
        val code = (result as PinSetupResult.Created).recoveryCode
        assertTrue("a recovery code is issued with the PIN", RecoveryCode.isWellFormed(code))
        assertEquals("and it is the code the TV can show", code, fresh.pendingRecoveryCode())
    }

    @Test
    fun setupRefusesASecondTimeInsteadOfReplacingTheCredential() {
        val second = pinManager.setup("654321", "654321")

        assertTrue("an existing PIN is never silently regenerated", second is PinSetupResult.AlreadyConfigured)
        assertTrue("and the original still works", pinManager.validate(PIN) is PinResult.Success)
    }

    @Test
    fun theRecoveryCodeIsOnlyReadableUntilTheParentAcknowledgesIt() {
        assertNotNull(pinManager.pendingRecoveryCode())

        pinManager.acknowledgeRecoveryCode()

        assertNull("after acknowledging, the plaintext is gone from memory", pinManager.pendingRecoveryCode())
    }

    // --- what a PIN may be --------------------------------------------------------------------------

    @Test
    fun aPinMustBeSixDigits() {
        val fresh = PinManager(store = InMemoryParentCredentialStore(), clock = { currentTime }, hasher = fastHasher)

        listOf("", "12345", "1234567", "12345a", "12 456", "abcdef").forEach { candidate ->
            val result = fresh.setup(candidate, candidate)
            assertTrue("'$candidate' is not a Parent PIN", result is PinSetupResult.Invalid)
            assertFalse(fresh.isConfigured())
        }
        assertTrue(fresh.setup("000000", "000000") is PinSetupResult.Created)
    }

    @Test
    fun theConfirmationMustMatch() {
        val fresh = PinManager(store = InMemoryParentCredentialStore(), clock = { currentTime }, hasher = fastHasher)

        val result = fresh.setup("123456", "123457")

        assertTrue(result is PinSetupResult.Invalid)
        assertFalse("a mistyped confirmation must not create a credential", fresh.isConfigured())
    }

    // --- signing in ---------------------------------------------------------------------------------

    @Test
    fun theCorrectPinReturnsASession() {
        val result = pinManager.validate(PIN)

        assertTrue(result is PinResult.Success)
        assertEquals("test-session-token", (result as PinResult.Success).token)
    }

    @Test
    fun aWrongPinIsRejectedAndCostsAnAttempt() {
        val result = pinManager.validate("999999")

        assertTrue(result is PinResult.Invalid)
        assertEquals("four tries are left after the first failure", 4, (result as PinResult.Invalid).attemptsRemaining)
        assertEquals(1, pinManager.getFailedAttempts())
    }

    @Test
    fun theLockoutPolicyIsUnchanged() {
        repeat(4) { assertTrue(pinManager.validate("999999") is PinResult.Invalid) }

        val fifth = pinManager.validate("999999")

        assertTrue("the fifth failure still rate-limits", fifth is PinResult.RateLimited)
        assertEquals(5 * 60 * 1000L, (fifth as PinResult.RateLimited).retryAfterMs)
        assertTrue(pinManager.isLockedOut())

        currentTime += fifth.retryAfterMs + 1
        assertFalse("the lockout still expires with the clock", pinManager.isLockedOut())
        assertTrue(pinManager.validate("999999") is PinResult.Invalid)
    }

    @Test
    fun aCorrectPinClearsTheFailures() {
        repeat(4) { pinManager.validate("999999") }
        assertEquals(4, pinManager.getFailedAttempts())

        assertTrue(pinManager.validate(PIN) is PinResult.Success)

        assertEquals(0, pinManager.getFailedAttempts())
        assertFalse(pinManager.isLockedOut())
    }

    @Test
    fun theCorrectPinIsNotAFailedAttemptWhenNoSessionCanBeIssued() {
        val noSession = PinManager(
            store = store,
            clock = { currentTime },
            onPinValidated = { "" },
            hasher = fastHasher,
        )

        val result = noSession.validate(PIN)

        assertTrue("an empty token is not a success", result is PinResult.NotConfigured)
        assertFalse(result is PinResult.Success)
        assertEquals("a correct PIN is not a failed attempt", 0, noSession.getFailedAttempts())
        assertFalse(noSession.isLockedOut())
    }

    @Test
    fun checkPinVerifiesWithoutIssuingASession() {
        var issued = 0
        val counted = PinManager(
            store = store,
            clock = { currentTime },
            onPinValidated = { issued++; "token" },
            hasher = fastHasher,
        )

        assertTrue(counted.checkPin(PIN) is PinCheckResult.Verified)
        assertEquals("the TV's own screens must not mint sessions", 0, issued)
        assertTrue(counted.checkPin("999999") is PinCheckResult.Wrong)
    }

    // --- persistence --------------------------------------------------------------------------------

    @Test
    fun thePinSurvivesAProcessRestart() {
        // The store is what outlives the process; a second PinManager over it is the next app start.
        val afterRestart = PinManager(
            store = store,
            clock = { currentTime },
            onPinValidated = { "new-token" },
            hasher = fastHasher,
        )

        assertTrue("the credential is still there", afterRestart.isConfigured())
        assertTrue(afterRestart.validate(PIN) is PinResult.Success)
        assertTrue("and the old PIN is the only one", afterRestart.validate("999999") is PinResult.Invalid)
    }

    @Test
    fun theRecoveryCodeSurvivesAProcessRestartAndStillWorks() {
        val code = pinManager.pendingRecoveryCode()!!

        val afterRestart = PinManager(store = store, clock = { currentTime }, hasher = fastHasher)

        assertNull("the plaintext is not persisted", afterRestart.pendingRecoveryCode())
        assertTrue(afterRestart.verifyRecoveryCode(code) is RecoveryCheckResult.Verified)
    }

    @Test
    fun failedAttemptsSurviveARestart() {
        val persistence = FakePinLockoutPersistence()
        val first = PinManager(
            store = store, clock = { currentTime }, hasher = fastHasher,
            pinLockoutPersistence = persistence,
        )
        repeat(3) { first.validate("999999") }

        val second = PinManager(
            store = store, clock = { currentTime }, hasher = fastHasher,
            pinLockoutPersistence = persistence,
        )

        assertEquals("a lockout cannot be escaped by restarting the TV", 3, second.getFailedAttempts())
    }

    @Test
    fun aLockoutSurvivesARestart() {
        val persistence = FakePinLockoutPersistence()
        val first = PinManager(
            store = store, clock = { currentTime }, hasher = fastHasher,
            pinLockoutPersistence = persistence,
        )
        repeat(5) { first.validate("999999") }
        assertTrue(first.isLockedOut())

        val second = PinManager(
            store = store, clock = { currentTime }, hasher = fastHasher,
            pinLockoutPersistence = persistence,
        )

        assertTrue(second.isLockedOut())
    }

    // --- what is stored -----------------------------------------------------------------------------

    @Test
    fun thePinItselfIsNotStoredAnywhere() {
        val stored = store.readPin()!!

        assertNotEquals("the PIN must not be the stored value", PIN, stored.hash)
        assertFalse("nor inside it", stored.hash.contains(PIN))
        assertTrue("a salt is stored with it", stored.salt.isNotEmpty())
        assertTrue("and a real iteration count", stored.iterations >= 1_000)
    }

    @Test
    fun twoDevicesWithTheSamePinStoreDifferentVerifiers() {
        val other = InMemoryParentCredentialStore()
        PinManager(store = other, clock = { currentTime }, hasher = fastHasher).setup(PIN, PIN)

        assertNotEquals(
            "the salt is per credential, so one precomputed table serves nobody",
            store.readPin()!!.hash,
            other.readPin()!!.hash,
        )
    }

    @Test
    fun theRecoveryCodeIsNotStoredInPlaintextEither() {
        val code = pinManager.pendingRecoveryCode()!!
        val stored = store.readRecovery()!!

        assertNotEquals(RecoveryCode.normalise(code), stored.hash)
        assertFalse(stored.hash.contains(RecoveryCode.normalise(code)))
    }

    // --- changing the PIN ---------------------------------------------------------------------------

    @Test
    fun changePinRequiresTheCurrentOne() {
        val result = pinManager.changePin("999999", "654321", "654321")

        assertTrue(result is PinChangeResult.WrongPin)
        assertTrue("the old PIN still works", pinManager.validate(PIN) is PinResult.Success)
        assertTrue("and the new one was not installed", pinManager.validate("654321") is PinResult.Invalid)
    }

    @Test
    fun changePinReplacesTheCredentialAndNotifies() {
        var changes = 0
        val manager = PinManager(
            store = store,
            clock = { currentTime },
            onPinValidated = { "token" },
            onCredentialsChanged = { changes++ },
            hasher = fastHasher,
        )

        val result = manager.changePin(PIN, "654321", "654321")

        assertTrue(result is PinChangeResult.Changed)
        assertEquals("the sessions issued under the old credential are told to go", 1, changes)
        assertTrue(manager.validate("654321") is PinResult.Success)
        assertTrue("the old PIN is no longer a PIN", manager.validate(PIN) is PinResult.Invalid)
    }

    @Test
    fun changePinLeavesTheRecoveryCodeAlone() {
        val code = pinManager.pendingRecoveryCode()!!

        pinManager.changePin(PIN, "654321", "654321")

        assertTrue(
            "a parent changing their PIN has not lost their paper",
            pinManager.verifyRecoveryCode(code) is RecoveryCheckResult.Verified,
        )
    }

    @Test
    fun changePinValidatesTheNewPinAndItsConfirmation() {
        assertTrue(pinManager.changePin(PIN, "12345", "12345") is PinChangeResult.Invalid)
        assertTrue(pinManager.changePin(PIN, "654321", "654322") is PinChangeResult.Invalid)
        assertTrue("nothing was replaced", pinManager.validate(PIN) is PinResult.Success)
    }

    // --- recovering ---------------------------------------------------------------------------------

    @Test
    fun aWrongRecoveryCodeIsRejected() {
        assertTrue(pinManager.verifyRecoveryCode("8K4P-7M2Q-91TX") is RecoveryCheckResult.Unknown)
    }

    @Test
    fun aMalformedRecoveryCodeIsNotCountedAsAGuess() {
        assertTrue(pinManager.verifyRecoveryCode("8K4P-7M2Q") is RecoveryCheckResult.NotWellFormed)
        assertTrue(pinManager.verifyRecoveryCode("8K4P-7M2Q-91T!") is RecoveryCheckResult.NotWellFormed)

        assertTrue("a typo must not spend an attempt", pinManager.verifyRecoveryCode("8K4P-7M2Q-91TX") is RecoveryCheckResult.Unknown)
        assertTrue("which is what an attempt looks like", pinManager.verifyRecoveryCode("8K4P-7M2Q-91TX") is RecoveryCheckResult.Unknown)
    }

    @Test
    fun dashesAndLowerCaseAreForgiven() {
        val code = pinManager.pendingRecoveryCode()!!

        assertTrue(pinManager.verifyRecoveryCode(code.lowercase()) is RecoveryCheckResult.Verified)
        assertTrue(pinManager.verifyRecoveryCode(code.replace("-", " ")) is RecoveryCheckResult.Verified)
        assertTrue(pinManager.verifyRecoveryCode("  $code  ") is RecoveryCheckResult.Verified)
    }

    @Test
    fun resetWithRecoveryReplacesThePinAndRotatesTheCode() {
        val oldCode = pinManager.pendingRecoveryCode()!!

        val result = pinManager.resetWithRecovery(oldCode, "654321", "654321")

        assertTrue(result is PinResetResult.Reset)
        val newCode = (result as PinResetResult.Reset).recoveryCode
        assertNotEquals("the code that was used is replaced", oldCode, newCode)
        assertTrue(RecoveryCode.isWellFormed(newCode))
        assertTrue("the new PIN works", pinManager.validate("654321") is PinResult.Success)
        assertTrue("the forgotten one is gone", pinManager.validate(PIN) is PinResult.Invalid)
    }

    @Test
    fun aUsedRecoveryCodeCannotBeUsedTwice() {
        val code = pinManager.pendingRecoveryCode()!!
        pinManager.resetWithRecovery(code, "654321", "654321")

        assertTrue(
            "a recovery code is one-time",
            pinManager.verifyRecoveryCode(code) is RecoveryCheckResult.Unknown,
        )
        assertTrue(pinManager.resetWithRecovery(code, "111111", "111111") is PinResetResult.Unknown)
        assertTrue("and the second attempt changed nothing", pinManager.validate("654321") is PinResult.Success)
    }

    @Test
    fun resetWithRecoveryNotifiesSoSessionsCanBeInvalidated() {
        var changes = 0
        val ownStore = InMemoryParentCredentialStore()
        val manager = PinManager(
            store = ownStore,
            clock = { currentTime },
            onPinValidated = { "token" },
            onCredentialsChanged = { changes++ },
            hasher = fastHasher,
        )
        manager.setup(PIN, PIN)
        val code = manager.pendingRecoveryCode()!!
        // Setting up a credential is itself a credential change (the TV's own sync session predates
        // it), so the count is taken from here: what is under test is the *reset* notifying.
        changes = 0

        manager.resetWithRecovery(code, "654321", "654321")

        assertEquals("a session issued before a PIN reset must not outlive it", 1, changes)
    }

    @Test
    fun resetWithRecoveryValidatesTheNewPin() {
        val code = pinManager.pendingRecoveryCode()!!

        assertTrue(pinManager.resetWithRecovery(code, "12345", "12345") is PinResetResult.Invalid)
        assertTrue(pinManager.resetWithRecovery(code, "654321", "654322") is PinResetResult.Invalid)
        assertTrue("the PIN is unchanged", pinManager.validate(PIN) is PinResult.Success)
    }

    @Test
    fun aRecoveryCodeCannotSignInByItself() {
        val code = pinManager.pendingRecoveryCode()!!

        // The code is a way to *replace* a PIN, never a way to be signed in. If it authenticated, it
        // would be a permanent second password that a parent cannot revoke from memory.
        assertTrue(pinManager.validate(code) is PinResult.Invalid)
        assertFalse(pinManager.validate(code) is PinResult.Success)
        assertTrue(pinManager.validate(RecoveryCode.normalise(code)) is PinResult.Invalid)
    }

    // --- rotating -----------------------------------------------------------------------------------

    @Test
    fun rotatingIssuesANewCodeAndKillsTheOldOne() {
        val oldCode = pinManager.pendingRecoveryCode()!!

        val newCode = pinManager.rotateRecoveryCode()

        assertNotEquals(oldCode, newCode)
        assertEquals("the new code is the one the TV can show", newCode, pinManager.pendingRecoveryCode())
        assertTrue(pinManager.verifyRecoveryCode(oldCode) is RecoveryCheckResult.Unknown)
        assertTrue(pinManager.verifyRecoveryCode(newCode) is RecoveryCheckResult.Verified)
    }

    @Test
    fun rotatingKeepsThePinItself() {
        pinManager.rotateRecoveryCode()

        assertTrue(pinManager.validate(PIN) is PinResult.Success)
    }

    // --- the destructive reset's door ---------------------------------------------------------------

    @Test
    fun clearAllRemovesBothCredentials() {
        val code = pinManager.pendingRecoveryCode()!!

        pinManager.clearAll()

        assertFalse(pinManager.isConfigured())
        assertNull(pinManager.pendingRecoveryCode())
        assertTrue(pinManager.validate(PIN) is PinResult.NotSetUp)
        assertTrue(pinManager.verifyRecoveryCode(code) is RecoveryCheckResult.NotSetUp)
    }

    @Test
    fun afterAWipeTheDeviceIsBackToFirstRun() {
        pinManager.clearAll()

        val fresh = PinManager(store = store, clock = { currentTime }, hasher = fastHasher)

        assertFalse("a wiped TV behaves like a new one", fresh.isConfigured())
        assertTrue(fresh.setup("123456", "123456") is PinSetupResult.Created)
    }

    // --- the production hasher ----------------------------------------------------------------------

    @Test
    fun theDefaultHasherIsRealPbkdf2WithItsOwnSalt() {
        val real = Pbkdf2SecretHasher()
        val first = real.hash("123456")
        val second = real.hash("123456")

        assertNotEquals("a fresh salt every time", first.salt, second.salt)
        assertNotEquals(first.hash, second.hash)
        assertTrue(real.verify("123456", first))
        assertFalse(real.verify("123457", first))
        assertTrue(
            "the production iteration count is the documented one",
            first.iterations == Pbkdf2SecretHasher.DEFAULT_ITERATIONS,
        )
    }

    @Test
    fun aVerifierFromADifferentAlgorithmIsStillVerified() {
        // A device that hashed with SHA1 (API 24/25) must keep working after an Android upgrade, which
        // is why the algorithm is stored inside the verifier rather than assumed.
        val sha1 = Pbkdf2SecretHasher(iterations = 1_000, algorithmOverride = Pbkdf2SecretHasher.ALGORITHM_SHA1)
        val stored = sha1.hash("123456")

        assertTrue(Pbkdf2SecretHasher(iterations = 1_000).verify("123456", stored))
        assertFalse(Pbkdf2SecretHasher(iterations = 1_000).verify("654321", stored))
    }

    @Test
    fun anUnreadableVerifierIsRefusedRatherThanAccepted() {
        val broken = StoredSecret(hash = "not hex", salt = "also not hex", iterations = 1_000, algorithm = "PBKDF2WithHmacSHA256")

        assertFalse("a corrupt credential fails closed", Pbkdf2SecretHasher(iterations = 1_000).verify("123456", broken))
    }

    @Test
    fun theDefaultRecoveryCodeSourceIsACsprng() {
        assertTrue(
            "the seam exists for tests; production must never fall back to a predictable source",
            PinManager(store = InMemoryParentCredentialStore(), hasher = fastHasher).randomSource is SecureRandom,
        )
    }

    private class FakePinLockoutPersistence : PinLockoutPersistence {
        private var failedAttempts = 0
        private var lockoutUntil = 0L
        private var lockoutCount = 0

        override fun save(failedAttempts: Int, lockoutUntil: Long, lockoutCount: Int) {
            this.failedAttempts = failedAttempts
            this.lockoutUntil = lockoutUntil
            this.lockoutCount = lockoutCount
        }

        override fun loadFailedAttempts(): Int = failedAttempts
        override fun loadLockoutUntil(): Long = lockoutUntil
        override fun loadLockoutCount(): Int = lockoutCount
    }

    private companion object {
        const val PIN = "482913"
    }
}
