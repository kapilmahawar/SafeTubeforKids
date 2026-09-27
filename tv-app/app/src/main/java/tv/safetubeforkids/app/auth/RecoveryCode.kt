package tv.safetubeforkids.app.auth

import java.security.SecureRandom
import java.util.Random

/**
 * The code a parent writes down once and keeps, so a forgotten Parent PIN is recoverable.
 *
 * Twelve characters in three groups - `8K4P-7M2Q-91TX` - drawn from a thirty-two symbol alphabet
 * chosen for being read aloud and copied by hand rather than for compactness: every digit, and only
 * the capital letters that cannot be mistaken for one of them.
 *
 * Entropy: thirty-two symbols, twelve characters, exactly five bits each, so sixty bits. That is not
 * a guestimate - `RecoveryCodeTest` measures the alphabet, the length and the spread of generated
 * codes. It is about twelve orders of magnitude past the six-digit PIN it protects, which is the
 * point: this is the credential that carries real strength, and the PIN is the one a person has to
 * type on a remote.
 */
object RecoveryCode {

    /** Groups of characters, separated by dashes when shown to a human. */
    const val GROUPS = 3
    const val GROUP_SIZE = 4

    /**
     * Thirty-two symbols: every digit, and the letters that cannot be mistaken for one another.
     *
     * `I`, `L` and `O` are absent, and `U` with them (it is the pair `U`/`V` that gets misread in a
     * handwritten code). Digits `0` and `1` are kept - a code a parent reads off a television screen
     * is printed in a monospace face where they are unmistakable, and the letters they are usually
     * confused with are exactly the ones that are not in this alphabet.
     *
     * Thirty-two symbols is also five bits per character, which makes the entropy of a code trivial
     * arithmetic rather than an estimate: twelve characters, sixty bits.
     */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    val LENGTH = GROUPS * GROUP_SIZE

    /** A new code, in the shape a parent reads out loud or copies down. */
    fun generate(random: Random = SecureRandom()): String {
        val characters = (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return group(characters)
    }

    /** `8K4P7M2Q91TX` -> `8K4P-7M2Q-91TX`, for display. */
    fun group(characters: String): String =
        characters.chunked(GROUP_SIZE).joinToString("-")

    /**
     * What the parent actually typed, as the string that is hashed.
     *
     * Dashes, spaces and lower case are all things a person introduces when copying something by
     * hand, and none of them are part of the secret, so all of them are removed rather than treated as
     * a wrong code. The same normalisation runs when the code is created and when it is verified,
     * which is what makes them agree.
     */
    fun normalise(input: String): String = input
        .trim()
        .uppercase()
        .filter { it.isLetterOrDigit() }

    /**
     * True when [input] could be a code this app issued: the right length, and only symbols from the
     * alphabet. Used to answer "that is not even the right shape" without spending a PBKDF2
     * derivation on it - and, importantly, without touching the failure counter, so a parent who
     * mistypes the separators is not locked out for it.
     */
    fun isWellFormed(input: String): Boolean {
        val normalised = normalise(input)
        return normalised.length == LENGTH && normalised.all { ALPHABET.indexOf(it) >= 0 }
    }
}
