package tv.safetubeforkids.app.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.auth.PinResult
import tv.safetubeforkids.app.ui.theme.KidAccent
import tv.safetubeforkids.app.ui.theme.KidBackground
import tv.safetubeforkids.app.ui.theme.KidSurface
import tv.safetubeforkids.app.ui.theme.KidText
import tv.safetubeforkids.app.ui.theme.KidTextDim
import tv.safetubeforkids.app.ui.theme.OverscanPadding
import tv.safetubeforkids.app.ui.theme.StatusWarning
import tv.safetubeforkids.app.util.AppLogger

private const val PIN_LENGTH = 6

/** Keypad labels in reading order; the last row carries the two non-digit keys. */
private val PIN_KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Clear", "0", "Back")

/**
 * The gate in front of everything a parent owns.
 *
 * A child holding the remote must not be able to read the pairing code, reset the PIN or erase the
 * watch history, so each of those screens asks for the PIN first. Entry works with the on-screen
 * keypad via the D-pad, with the number keys some remotes carry, or with DELETE to correct a digit.
 * [ServiceLocator.pinManager] applies its own attempt limit and lockout, so the gate cannot be worn
 * down by guessing.
 */
@Composable
fun PinGateScreen(
    title: String,
    subtitle: String,
    onCancel: () -> Unit,
    onUnlocked: () -> Unit,
) {
    var entry by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        AppLogger.log("PIN gate shown: $title")
    }

    fun submit(candidate: String) {
        when (val result = ServiceLocator.pinManager.validate(candidate)) {
            is PinResult.Success -> {
                AppLogger.log("PIN gate unlocked: $title")
                onUnlocked()
            }
            is PinResult.Invalid -> {
                AppLogger.warn("PIN gate rejected: ${result.attemptsRemaining} attempts left")
                message = "Wrong PIN - ${result.attemptsRemaining} attempts left"
                entry = ""
            }
            is PinResult.RateLimited -> {
                AppLogger.warn("PIN gate rate limited for ${result.retryAfterMs / 1000}s")
                message = "Too many attempts - try again in ${result.retryAfterMs / 1000}s"
                entry = ""
            }
        }
    }

    fun press(key: String) {
        when (key) {
            "Clear" -> {
                entry = ""
                message = null
            }
            "Back" -> onCancel()
            else -> {
                if (entry.length >= PIN_LENGTH) return
                entry += key
                message = null
                if (entry.length == PIN_LENGTH) submit(entry)
            }
        }
    }

    fun digitOf(keyCode: Int): String? =
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            (keyCode - KeyEvent.KEYCODE_0).toString()
        } else {
            null
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KidBackground)
            .padding(OverscanPadding)
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val keyCode = event.nativeKeyEvent.keyCode
                val typed = digitOf(keyCode)
                when {
                    keyCode == KeyEvent.KEYCODE_BACK -> {
                        onCancel()
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_DEL -> {
                        entry = entry.dropLast(1)
                        message = null
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_ENTER && entry.isNotEmpty() -> {
                        // Some remotes have no OK-to-select habit; Enter also submits a short PIN.
                        submit(entry)
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                        selected = (selected - 1).coerceAtLeast(0)
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        selected = (selected + 1).coerceAtMost(PIN_KEYS.size - 1)
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                        selected = (selected - 3).coerceAtLeast(0)
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                        selected = (selected + 3).coerceAtMost(PIN_KEYS.size - 1)
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                        keyCode == KeyEvent.KEYCODE_BUTTON_A -> {
                        press(PIN_KEYS[selected])
                        true
                    }
                    typed != null -> {
                        press(typed)
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = KidText,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = KidTextDim)
            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(PIN_LENGTH) { index ->
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(
                                color = if (index < entry.length) KidAccent else KidSurface,
                                shape = CircleShape,
                            ),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = message ?: " ",
                style = MaterialTheme.typography.bodyMedium,
                color = if (message != null) StatusWarning else Color.Transparent,
            )
            Spacer(Modifier.height(16.dp))

            PIN_KEYS.chunked(3).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEachIndexed { columnIndex, key ->
                        val index = rowIndex * 3 + columnIndex
                        val highlighted = index == selected
                        Box(
                            modifier = Modifier
                                .size(if (key.length > 1) 124.dp else 72.dp, 72.dp)
                                .background(
                                    color = if (highlighted) KidAccent else KidSurface,
                                    shape = RoundedCornerShape(14.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.titleLarge,
                                color = if (highlighted) Color.Black else KidText,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}
