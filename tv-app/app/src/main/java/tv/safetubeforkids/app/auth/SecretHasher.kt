package tv.safetubeforkids.app.auth

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Turns a secret into a verifier that can be checked but not read back.
 *
 * PBKDF2 with a per-secret random salt: a slow, salted, one-way function, which is what makes the
 * stored bytes useless to anyone who reads them. A plain `SHA-256(pin)` would not be, because six
 * digits is a million candidates - small enough to enumerate instantly - so the cost per guess is the
 * whole defence, and a per-secret salt is what stops one precomputed table from serving every TV.
 *
 * **What this can and cannot promise.** The Parent PIN is six digits because a parent has to be able
 * to type it on a remote, so it carries about twenty bits of entropy no matter how it is stored: an
 * attacker who copies the preferences file can always try all one million of them. The iteration count
 * is what makes that cost real (minutes to hours of dedicated hardware rather than milliseconds), the
 * rate limiter is what makes it impractical to do it *through* the dashboard, and the Recovery Code -
 * twelve characters from a thirty-symbol alphabet, around fifty-nine bits - is the credential that is
 * actually strong. Guessing the PIN is not the attack this design invites; reading it off the TV no
 * longer works at all, which is the improvement W10 makes.
 */
interface SecretHasher {

    /** A fresh verifier for [secret]: new salt, current iteration count. */
    fun hash(secret: String): StoredSecret

    /** True when [secret] produces [stored], compared in constant time. */
    fun verify(secret: String, stored: StoredSecret): Boolean
}

/**
 * PBKDF2, with the strongest digest this device actually has.
 *
 * `PBKDF2WithHmacSHA256` arrived in API 26; this app runs from API 24, where asking for it throws
 * `NoSuchAlgorithmException`. The algorithm is therefore chosen once, at construction, and recorded
 * inside every verifier it produces - so a device that upgrades its Android version keeps verifying
 * the credential it created earlier instead of deciding it no longer understands it.
 */
class Pbkdf2SecretHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
    algorithmOverride: String? = null,
) : SecretHasher {

    private val algorithm: String = algorithmOverride ?: strongestAvailable()

    override fun hash(secret: String): StoredSecret {
        val salt = ByteArray(SALT_BYTES)
        random.nextBytes(salt)
        return StoredSecret(
            hash = encode(derive(secret, salt, iterations, algorithm)),
            salt = encode(salt),
            iterations = iterations,
            algorithm = algorithm,
        )
    }

    override fun verify(secret: String, stored: StoredSecret): Boolean {
        val salt = try {
            decode(stored.salt)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val expected = try {
            decode(stored.hash)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val actual = derive(secret, salt, stored.iterations, stored.algorithm)
        // Constant time: a comparison that stops at the first differing byte leaks how much of a guess
        // was right, which is exactly the feedback a PIN guesser wants.
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(secret: String, salt: ByteArray, iterations: Int, algorithm: String): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Hex, not Base64, and deliberately so: `java.util.Base64` only exists from API 26 and
     * `android.util.Base64` does not exist off a device, so either choice would make this class
     * untestable on the JVM. Hex is longer and costs nothing here - these strings are written once per
     * credential and read once per sign-in.
     */
    private fun encode(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun decode(text: String): ByteArray {
        require(text.length % 2 == 0) { "not a hex string" }
        return ByteArray(text.length / 2) { index ->
            val pair = text.substring(index * 2, index * 2 + 2)
            pair.toIntOrNull(16)?.toByte() ?: throw IllegalArgumentException("not a hex string")
        }
    }

    companion object {
        /**
         * Chosen by measurement on the device this app runs on, not by habit.
         *
         * W10's first real-device run found the hard way that the cost matters: at 120,000 iterations a
         * derivation on a Mi Box 4 took **about 5.5 seconds**, and creating a Parent PIN (which derives
         * twice - once for the PIN and once for the Recovery Code) blocked the TV's input for eleven
         * seconds, produced an ANR ("Waited 5006ms for MotionEvent", 102% CPU) and ended with Android
         * force-finishing the activity. Setup crashed, and the log is in `docs/W10_PARENT_ACCESS.md`.
         *
         * So this number is a latency budget as well as a security choice. On the same device 30,000
         * iterations costs roughly a second, which is a wait a parent does not notice on a sign-in and
         * is still a million-guess wall of several hours for anyone who copies the preferences file.
         * The credential that carries real strength here is the sixty-bit Recovery Code; the PIN is six
         * digits and always was.
         *
         * The KDF must also never run on the UI thread - see `OnboardingScreen`, which derives off the
         * main dispatcher for exactly this reason.
         */
        const val DEFAULT_ITERATIONS = 30_000

        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256

        const val ALGORITHM_SHA256 = "PBKDF2WithHmacSHA256"
        const val ALGORITHM_SHA1 = "PBKDF2WithHmacSHA1"

        private fun strongestAvailable(): String = when {
            available(ALGORITHM_SHA256) -> ALGORITHM_SHA256
            else -> ALGORITHM_SHA1
        }

        private fun available(algorithm: String): Boolean = try {
            SecretKeyFactory.getInstance(algorithm)
            true
        } catch (e: Exception) {
            false
        }
    }
}
