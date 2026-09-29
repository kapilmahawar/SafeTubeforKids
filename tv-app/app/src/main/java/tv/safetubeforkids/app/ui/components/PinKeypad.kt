package tv.safetubeforkids.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.safetubeforkids.app.auth.PinManager
import tv.safetubeforkids.app.ui.theme.KidAccent
import tv.safetubeforkids.app.ui.theme.KidSurface
import tv.safetubeforkids.app.ui.theme.KidText
import tv.safetubeforkids.app.ui.theme.KidTextDim

/**
 * Six digits, entered on a keypad, on a television.
 *
 * This is the first text entry in the app, and it is a keypad rather than a text field on purpose: a
 * parent typing a PIN with a remote should press a digit they can see, not hunt for the system
 * keyboard, and the digits are all a PIN can contain. Backspace is the only other control, and it is
 * the only other thing a six-digit entry needs.
 *
 * The dots show how many digits are in, never which - the PIN is a secret, and a television is a
 * screen in a room a child walks through.
 */
@Composable
fun PinKeypad(
    pin: String,
    label: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * Where focus should land when the keypad appears.
     *
     * A prompt that is shown over another screen has to say where the remote is, or the first D-pad
     * press goes to whatever was focused underneath - which, on the Settings screen, is the very button
     * that opened the prompt. Optional, so the screens that already show a keypad keep their behaviour.
     */
    initialFocus: FocusRequester? = null,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = KidTextDim)
        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            (0 until PinManager.PIN_LENGTH).forEach { index ->
                val filled = index < pin.length
                // A fixed box per position, not a glyph that sizes itself: `●` and `○` are different
                // widths and heights in this font, so a row of them changes size as the parent types -
                // and because the screen centres its content, the whole keypad then shifts under their
                // finger. It was measurable on the television (41px on the first digit) and it is the
                // kind of thing that turns "press 4" into "press 5" for a person with a remote.
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(40.dp)
                        .background(KidSurface, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (filled) "●" else "○",
                        fontSize = 24.sp,
                        color = if (filled) KidAccent else KidTextDim,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { digit ->
                    DigitButton(
                        digit = digit.toString(),
                        enabled = enabled,
                        // The first digit of the first row is the natural place for the remote.
                        modifier = if (digit == '1' && initialFocus != null) {
                            Modifier.focusRequester(initialFocus)
                        } else {
                            Modifier
                        },
                    ) { onChange(pin + digit) }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DigitButton("0", enabled) { onChange(pin + "0") }
            Button(
                onClick = { onChange(pin.dropLast(1)) },
                enabled = enabled && pin.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.width(88.dp).height(52.dp),
            ) {
                Text("⌫", color = KidText, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DigitButton(
    digit: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.width(88.dp).height(52.dp),
    ) {
        Text(
            digit,
            color = KidText,
            fontSize = 20.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
