package tv.safetubeforkids.app.auth

import java.security.SecureRandom
import java.util.Random
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PinManagerTest {

    private var currentTime = 0L
    private lateinit var pinManager: PinManager

    @Before
    fun setup() {
        currentTime = 1000000L
        // A session-issuing callback is wired, because a correct PIN is only an authentication when
        // something can issue a token for it. Without one, `validate` now fails closed
        // (PinResult.NotConfigured) instead of returning a Success carrying an empty token, so the
        // tests that need an unconfigured device build their own PinManager.
        pinManager = PinManager(
            clock = { currentTime },
            onPinValidated = { "test-session-token" },
        )
    }

    @Test
    fun generatePin_returns6Digits() {
        val pin = pinManager.getCurrentPin()
        assertEquals(6, pin.length)
        assertTrue("PIN should be all numeric", pin.all { it.isDigit() })
    }

    @Test
    fun generatePin_differentEachTime() {
        val pins = (1..10).map { PinManager(clock = { currentTime }).getCurrentPin() }.toSet()
        assertTrue("At least 2 different PINs from 10 generations", pins.size >= 2)
    }

    @Test
    fun validatePin_correctPinReturnsTrue() {
        val pin = pinManager.getCurrentPin()
        val result = pinManager.validate(pin)
        assertTrue("Correct PIN should return Success", result is PinResult.Success)
    }

    @Test
    fun validatePin_wrongPinReturnsFalse() {
        val result = pinManager.validate("000000")
        assertTrue("Wrong PIN should return Invalid", result is PinResult.Invalid)
    }

    @Test
    fun rateLimiting_locksAfter5Failures() {
        repeat(4) {
            pinManager.validate("wrong!")
        }
        val fifthResult = pinManager.validate("wrong!")
        assertTrue("5th failure should trigger rate limiting", fifthResult is PinResult.RateLimited)
    }

    @Test
    fun rateLimiting_resetsAfterTimeout() {
        repeat(5) { pinManager.validate("wrong!") }
        // Advance past 5 minute lockout
        currentTime += 5 * 60 * 1000L + 1
        val result = pinManager.validate("wrong!")
        assertTrue("After timeout, should allow attempt (Invalid, not RateLimited)", result is PinResult.Invalid)
    }

    @Test
    fun rateLimiting_exponentialBackoff() {
        // First lockout: 5 min
        repeat(5) { pinManager.validate("wrong!") }
        val first = pinManager.validate("wrong!") as PinResult.RateLimited

        // Advance past first lockout
        currentTime += first.retryAfterMs + 1

        // Second lockout: 10 min
        repeat(5) { pinManager.validate("wrong!") }
        val second = pinManager.validate("wrong!") as PinResult.RateLimited
        assertTrue("Second lockout should be longer", second.retryAfterMs > first.retryAfterMs)

        // Advance past second lockout
        currentTime += second.retryAfterMs + 1

        // Third lockout: 20 min
        repeat(5) { pinManager.validate("wrong!") }
        val third = pinManager.validate("wrong!") as PinResult.RateLimited
        assertTrue("Third lockout should be longer than second", third.retryAfterMs > second.retryAfterMs)
    }

    @Test
    fun rateLimiting_successResetsCounter() {
        val pin = pinManager.getCurrentPin()
        repeat(3) { pinManager.validate("wrong!") }
        assertEquals(3, pinManager.getFailedAttempts())

        pinManager.validate(pin)
        assertEquals(0, pinManager.getFailedAttempts())
    }

    @Test
    fun resetPin_generatesNewPin() {
        val oldPin = pinManager.getCurrentPin()
        // Reset enough times to ensure a different PIN (statistically near-certain)
        var different = false
        repeat(10) {
            val newPin = pinManager.resetPin()
            if (newPin != oldPin) different = true
        }
        assertTrue("Reset should eventually generate a different PIN", different)
    }

    @Test
    fun resetPin_invalidatesOldPin() {
        val oldPin = pinManager.getCurrentPin()
        pinManager.resetPin()
        val result = pinManager.validate(oldPin)
        // Old PIN should now fail (unless by extreme coincidence it's the same)
        // We test the mechanism: currentPin changed, so validate against old should fail
        val newPin = pinManager.getCurrentPin()
        if (oldPin != newPin) {
            assertTrue("Old PIN should fail after reset", result is PinResult.Invalid)
        }
    }

    @Test
    fun isLockedOut_returnsTrueWhenLocked() {
        repeat(5) { pinManager.validate("wrong!") }
        assertTrue("Should be locked out after 5 failures", pinManager.isLockedOut())
    }

    @Test
    fun lockoutPersistence_survivesRestart() {
        val fakePersistence = FakePinLockoutPersistence()
        val pm1 = PinManager(clock = { currentTime }, lockoutPersistence = fakePersistence)
        val pin = pm1.getCurrentPin()

        // Fail 3 times
        repeat(3) { pm1.validate("wrong!") }
        assertEquals(3, pm1.getFailedAttempts())

        // Simulate restart: create new PinManager with same persistence
        val pm2 = PinManager(clock = { currentTime }, lockoutPersistence = fakePersistence)
        assertEquals(3, pm2.getFailedAttempts())
    }

    @Test
    fun lockoutPersistence_lockoutSurvivesRestart() {
        val fakePersistence = FakePinLockoutPersistence()
        val pm1 = PinManager(clock = { currentTime }, lockoutPersistence = fakePersistence)

        // Trigger lockout
        repeat(5) { pm1.validate("wrong!") }
        assertTrue(pm1.isLockedOut())

        // Simulate restart
        val pm2 = PinManager(clock = { currentTime }, lockoutPersistence = fakePersistence)
        assertTrue("Lockout should survive restart", pm2.isLockedOut())
    }

    // --- PIN generation from the injected source (Phase 6 P0-2) -------------------------------------

    /**
     * A deterministic digit source: it hands back exactly the values it was given, in order, and
     * remembers what it was asked for. No statistical test is involved.
     */
    private class ScriptedRandom(private val digits: IntArray) : Random() {
        val bounds = mutableListOf<Int>()
        private var index = 0

        override fun nextInt(bound: Int): Int {
            bounds += bound
            if (index >= digits.size) {
                throw AssertionError("the PIN asked for more digits than were scripted")
            }
            return digits[index++]
        }
    }

    @Test
    fun generatePin_takesEveryDigitFromTheInjectedRandomSource() {
        val random = ScriptedRandom(intArrayOf(1, 2, 3, 4, 5, 6))
        val pm = PinManager(clock = { currentTime }, randomSource = random)

        assertEquals("123456", pm.getCurrentPin())
    }

    @Test
    fun generatePin_keepsLeadingZeroesSoThePinIsAlwaysSixCharacters() {
        val leadingZero = PinManager(
            clock = { currentTime },
            randomSource = ScriptedRandom(intArrayOf(0, 0, 1, 2, 3, 4)),
        )
        val pin = leadingZero.getCurrentPin()
        assertEquals("001234", pin)
        assertEquals("a leading zero must not shorten the PIN", 6, pin.length)

        val allZeroes = PinManager(
            clock = { currentTime },
            randomSource = ScriptedRandom(intArrayOf(0, 0, 0, 0, 0, 0)),
        )
        assertEquals("000000", allZeroes.getCurrentPin())
        assertEquals(6, allZeroes.getCurrentPin().length)
    }

    @Test
    fun generatePin_asksForSixDigitsEachInZeroToNine() {
        val random = ScriptedRandom(intArrayOf(9, 9, 9, 9, 9, 9))
        PinManager(clock = { currentTime }, randomSource = random)

        assertEquals("one digit per position", 6, random.bounds.size)
        assertTrue(
            "each digit must be drawn in 0..9, never derived from a wider draw",
            random.bounds.all { it == 10 },
        )
    }

    @Test
    fun generatePin_defaultSourceIsACsprng() {
        // The seam exists for tests; production must never quietly fall back to a predictable source.
        assertTrue(
            "the production default must be a SecureRandom",
            PinManager(clock = { currentTime }).randomSource is SecureRandom,
        )
    }

    @Test
    fun resetPin_alsoTakesItsDigitsFromTheInjectedSource() {
        val random = ScriptedRandom(intArrayOf(4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5))
        val pm = PinManager(clock = { currentTime }, randomSource = random)

        assertEquals("444444", pm.getCurrentPin())
        assertEquals("555555", pm.resetPin())
        assertEquals("a reset PIN keeps six characters too", 6, pm.getCurrentPin().length)
    }

    // --- fail closed when no session can be issued (Phase 6 P0-2) -----------------------------------

    @Test
    fun validate_withoutASessionCallbackFailsClosed() {
        val unconfigured = PinManager(clock = { currentTime })

        val result = unconfigured.validate(unconfigured.getCurrentPin())

        assertTrue(
            "a correct PIN on a device that cannot issue a session is not an authentication",
            result is PinResult.NotConfigured,
        )
        assertFalse("it must never be a Success", result is PinResult.Success)
    }

    @Test
    fun validate_withoutASessionCallbackNeitherResetsNorIncrementsTheFailureCount() {
        val unconfigured = PinManager(clock = { currentTime })
        repeat(3) { unconfigured.validate("wrong!") }
        assertEquals(3, unconfigured.getFailedAttempts())

        assertTrue(unconfigured.validate(unconfigured.getCurrentPin()) is PinResult.NotConfigured)

        assertEquals("a correct PIN is not a failed attempt", 3, unconfigured.getFailedAttempts())
        assertFalse("and it did not lock the device out either", unconfigured.isLockedOut())
    }

    @Test
    fun validate_withABlankTokenFromTheCallbackIsNotASuccess() {
        val emptyToken = PinManager(clock = { currentTime }, onPinValidated = { "" })
        assertTrue(
            "an empty token must not be handed back as a success",
            emptyToken.validate(emptyToken.getCurrentPin()) is PinResult.NotConfigured,
        )

        val whitespaceToken = PinManager(clock = { currentTime }, onPinValidated = { "   " })
        assertTrue(
            "nor a blank one",
            whitespaceToken.validate(whitespaceToken.getCurrentPin()) is PinResult.NotConfigured,
        )
    }

    @Test
    fun anEmptyTokenCannotAuthenticateASession() {
        // The belt to those braces: even if an empty string were ever handed out as a token, it
        // authenticates nothing.
        val sessions = SessionManager()
        assertFalse(sessions.validateSession(""))
        assertFalse(sessions.validateSession("not-a-real-token"))
    }

    // --- the normal success path and the lockout policy are unchanged --------------------------------

    @Test
    fun validate_withASessionCallbackReturnsSuccessAndItsToken() {
        val wired = PinManager(clock = { currentTime }, onPinValidated = { "tok-1" })

        val result = wired.validate(wired.getCurrentPin())

        assertTrue(result is PinResult.Success)
        assertEquals("tok-1", (result as PinResult.Success).token)
    }

    @Test
    fun validate_withASessionCallbackStillResetsTheFailureCountOnSuccess() {
        val wired = PinManager(clock = { currentTime }, onPinValidated = { "tok-2" })
        repeat(4) { wired.validate("wrong!") }
        assertEquals(4, wired.getFailedAttempts())

        assertTrue(wired.validate(wired.getCurrentPin()) is PinResult.Success)

        assertEquals("a real success still clears the failures", 0, wired.getFailedAttempts())
        assertFalse(wired.isLockedOut())
    }

    @Test
    fun lockoutIsUnchangedWhenASessionCallbackIsWired() {
        val wired = PinManager(clock = { currentTime }, onPinValidated = { "tok-3" })

        repeat(4) { assertTrue(wired.validate("wrong!") is PinResult.Invalid) }

        val fifth = wired.validate("wrong!")
        assertTrue("the fifth failure still rate-limits", fifth is PinResult.RateLimited)
        assertEquals(
            "the first lockout is still five minutes",
            5 * 60 * 1000L,
            (fifth as PinResult.RateLimited).retryAfterMs,
        )
        assertTrue(wired.isLockedOut())

        currentTime += fifth.retryAfterMs + 1
        assertFalse("the lockout still expires with the clock", wired.isLockedOut())
        assertTrue(wired.validate("wrong!") is PinResult.Invalid)
    }

    @Test
    fun aWrongPinStillRecordsItsFailureInPersistentLockoutState() {
        val persistence = FakePinLockoutPersistence()
        val pm1 = PinManager(
            clock = { currentTime },
            onPinValidated = { "tok-4" },
            lockoutPersistence = persistence,
        )

        pm1.validate("wrong!")
        assertEquals(1, pm1.getFailedAttempts())

        val pm2 = PinManager(
            clock = { currentTime },
            onPinValidated = { "tok-5" },
            lockoutPersistence = persistence,
        )
        assertEquals("the failure still survives a restart", 1, pm2.getFailedAttempts())
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
}
