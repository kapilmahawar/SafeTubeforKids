package tv.safetubeforkids.app.playback

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the remote's keys mean inside the player.
 *
 * LEFT and RIGHT are deliberately three different things depending on what holds the focus, and this is
 * the half of that contract that lives in code a unit test can reach:
 *
 * ```text
 * ON THE SURFACE, LEFT AND RIGHT ARE SEEK
 * EVERY OTHER KEY THE PLAYER HONOURS MAPS TO EXACTLY ONE ACTION
 * AND A KEY THE PLAYER DOES NOT USE MAPS TO NOTHING AT ALL
 * ```
 *
 * The other two thirds - LEFT/RIGHT moving between the settings buttons, and LEFT/RIGHT being returned
 * unconsumed so the transport focus graph walks Previous..Next - live in the composable's key handler and
 * are guarded, in order, by `scripts/player-key-contract.test.js`.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerKeysTest {

    @Test
    fun `on the surface the directional keys are seek, and the media keys agree with them`() {
        assertEquals(PlaybackKeys.Action.SeekBackward, PlaybackKeys.actionOf(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PlaybackKeys.Action.SeekForward, PlaybackKeys.actionOf(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(PlaybackKeys.Action.SeekBackward, PlaybackKeys.actionOf(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(PlaybackKeys.Action.SeekForward, PlaybackKeys.actionOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
    }

    @Test
    fun `play, pause and the OK keys all mean the same one thing`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
        ).forEach { key ->
            assertEquals("key $key", PlaybackKeys.Action.TogglePlayPause, PlaybackKeys.actionOf(key))
        }
    }

    @Test
    fun `the queue keys walk the approved queue and not the catalog`() {
        assertEquals(PlaybackKeys.Action.NextApproved, PlaybackKeys.actionOf(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(PlaybackKeys.Action.PreviousApproved, PlaybackKeys.actionOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
    }

    @Test
    fun `the up and down keys reveal the controls rather than seeking`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
        ).forEach { key ->
            assertEquals("key $key", PlaybackKeys.Action.RevealControls, PlaybackKeys.actionOf(key))
        }
    }

    @Test
    fun `a key the player does not use is not claimed by it`() {
        listOf(
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_HOME,
        ).forEach { key ->
            assertNull("key $key must not be consumed by the player", PlaybackKeys.actionOf(key))
        }
    }
}
