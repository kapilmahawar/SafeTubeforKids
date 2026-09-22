package tv.safetubeforkids.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hold-to-accelerate seeking.
 *
 * A held remote key produces repeated key events with a rising repeatCount, which cannot be
 * reproduced over adb (injected events are always one-shots with repeatCount 0). This test is
 * therefore the real verification of the escalation curve.
 */
class SeekStepTest {

    @Test
    fun `a single press seeks ten seconds`() {
        assertEquals(10_000L, seekStepFor(0))
        assertEquals(10_000L, seekStepFor(1))
    }

    @Test
    fun `holding escalates through the steps`() {
        assertEquals(20_000L, seekStepFor(2))
        assertEquals(30_000L, seekStepFor(4))
        assertEquals(60_000L, seekStepFor(8))
        assertEquals(120_000L, seekStepFor(12))
    }

    @Test
    fun `escalation stays at the top step`() {
        assertEquals(120_000L, seekStepFor(40))
    }

    @Test
    fun `every step is a multiple of the base step`() {
        (0..20).forEach { repeat ->
            val step = seekStepFor(repeat)
            assertEquals("step $step must be a whole number of 10s jumps", 0L, step % 10_000L)
        }
    }
}
