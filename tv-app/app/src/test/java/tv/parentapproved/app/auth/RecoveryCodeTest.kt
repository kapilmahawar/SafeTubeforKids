package tv.safetubeforkids.app.auth

import java.util.Random
import org.junit.Assert.*
import org.junit.Test

/**
 * The Recovery Code: its shape, its alphabet, its entropy, and the way a parent's typing is forgiven.
 *
 * The claims here are the ones a security decision rests on, so they are measured rather than
 * asserted: the alphabet has no look-alike pairs, a generated code is twelve characters from it, and
 * a thousand codes are a thousand different codes.
 */
class RecoveryCodeTest {

    @Test
    fun aCodeIsThreeGroupsOfFour() {
        val code = RecoveryCode.generate()

        assertEquals("12 characters in 3 groups", 14, code.length)
        assertEquals(code, RecoveryCode.group(code.replace("-", "")))
        assertEquals(listOf(4, 4, 4), code.split("-").map { it.length })
    }

    @Test
    fun everyCharacterComesFromTheAlphabet() {
        repeat(200) {
            RecoveryCode.generate().replace("-", "").forEach { character ->
                assertTrue("'$character' is not in the alphabet", RecoveryCode.ALPHABET.indexOf(character) >= 0)
            }
        }
    }

    @Test
    fun theAlphabetHasNoLookAlikePairs() {
        // The whole reason this code can be written down off a television and typed back a year later:
        // the letters that get confused with digits are simply not in the alphabet, so the digits can
        // stay.
        listOf('I', 'L', 'O', 'U').forEach { confusing ->
            assertFalse("'$confusing' is too easy to confuse with another character", RecoveryCode.ALPHABET.indexOf(confusing) >= 0)
        }
        listOf('0', '1').forEach { digit ->
            assertTrue(
                "the digit is unambiguous once its look-alike letter is gone",
                RecoveryCode.ALPHABET.indexOf(digit) >= 0,
            )
        }
        assertEquals("no duplicates in the alphabet", RecoveryCode.ALPHABET.toSet().size, RecoveryCode.ALPHABET.length)
        assertEquals("thirty-two symbols: exactly five bits each", 32, RecoveryCode.ALPHABET.length)
    }

    @Test
    fun theCodeCarriesEnoughEntropyToBeWorthMoreThanThePinItProtects() {
        val bitsPerCharacter = kotlin.math.log2(RecoveryCode.ALPHABET.length.toDouble())
        val bits = bitsPerCharacter * RecoveryCode.LENGTH

        assertEquals("five bits per character", 5.0, bitsPerCharacter, 0.0001)
        assertEquals("so exactly sixty for twelve characters", 60.0, bits, 0.0001)
    }

    @Test
    fun aThousandCodesAreAThousandDifferentCodes() {
        val codes = (1..1_000).map { RecoveryCode.generate() }.toSet()

        assertEquals("no collisions in a thousand draws", 1_000, codes.size)
    }

    @Test
    fun generationDrawsOneCharacterAtATimeFromTheAlphabet() {
        val scripted = ScriptedRandom(intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11))

        val code = RecoveryCode.generate(scripted)

        assertEquals("one draw per character", RecoveryCode.LENGTH, scripted.bounds.size)
        assertTrue("each draw is bounded by the alphabet", scripted.bounds.all { it == RecoveryCode.ALPHABET.length })
        assertEquals(
            RecoveryCode.ALPHABET.take(12).chunked(4).joinToString("-"),
            code,
        )
    }

    @Test
    fun normalisingForgivesWhatHandCopyingIntroduces() {
        assertEquals("8K4P7M2Q91TX", RecoveryCode.normalise("8k4p-7m2q-91tx"))
        assertEquals("8K4P7M2Q91TX", RecoveryCode.normalise(" 8K4P 7M2Q 91TX "))
        assertEquals("8K4P7M2Q91TX", RecoveryCode.normalise("8K4P—7M2Q—91TX"))
        assertEquals("8K4P7M2Q91TX", RecoveryCode.normalise("8K4P.7M2Q.91TX"))
    }

    @Test
    fun wellFormedMeansTheRightLengthAndOnlyAlphabetSymbols() {
        assertTrue(RecoveryCode.isWellFormed("8K4P-7M2Q-91TX"))
        assertTrue(RecoveryCode.isWellFormed("8k4p7m2q91tx"))

        assertFalse("too short", RecoveryCode.isWellFormed("8K4P-7M2Q-91T"))
        assertFalse("too long", RecoveryCode.isWellFormed("8K4P-7M2Q-91TXZ"))
        assertFalse("a symbol this app never issues", RecoveryCode.isWellFormed("8K4P-7M2Q-91TO"))
        assertFalse("empty", RecoveryCode.isWellFormed(""))
    }

    /** Deterministic, so a code's shape can be asserted without a statistical claim. */
    private class ScriptedRandom(private val values: IntArray) : Random() {
        val bounds = mutableListOf<Int>()
        private var index = 0

        override fun nextInt(bound: Int): Int {
            bounds += bound
            if (index >= values.size) throw AssertionError("more characters were asked for than were scripted")
            return values[index++]
        }
    }
}
