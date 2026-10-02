package tv.safetubeforkids.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The transport row's control identity, left to right.
 *
 * This is the row the D-pad graph is built from: one focus target per label, laid out in this order,
 * with the ends linked back to each other so the row cannot hand focus to the full-screen surface.
 * The physical Mi Box walk (DOWN to enter, RIGHT across all five, LEFT back, then UP/DOWN to leave)
 * is the real verification that the navigation works; this test pins the identity that walk depends
 * on, so a label rename or a reordered/removed control cannot silently invalidate it.
 */
class TransportControlLabelsTest {

    @Test
    fun `the row exposes the five transport controls in d-pad order`() {
        assertEquals(
            listOf(
                "Previous",
                "Rewind 10 seconds",
                "Play",
                "Forward 10 seconds",
                "Next",
            ),
            TRANSPORT_CONTROL_LABELS,
        )
    }

    @Test
    fun `every control has a distinct accessibility label`() {
        assertEquals(
            "duplicate labels would make TalkBack and the focus dump ambiguous",
            TRANSPORT_CONTROL_LABELS.size,
            TRANSPORT_CONTROL_LABELS.toSet().size,
        )
    }

    @Test
    fun `the play_pause toggle is the middle control`() {
        assertEquals(5, TRANSPORT_CONTROL_LABELS.size)
        assertEquals("Play", TRANSPORT_CONTROL_LABELS[TRANSPORT_CONTROL_LABELS.size / 2])
        assertEquals(
            "seeking must sit either side of the toggle",
            "Rewind 10 seconds",
            TRANSPORT_CONTROL_LABELS[TRANSPORT_CONTROL_LABELS.size / 2 - 1],
        )
        assertEquals(
            "seeking must sit either side of the toggle",
            "Forward 10 seconds",
            TRANSPORT_CONTROL_LABELS[TRANSPORT_CONTROL_LABELS.size / 2 + 1],
        )
    }
}
