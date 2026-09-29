package tv.safetubeforkids.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.auth.ParentGateResult
import tv.safetubeforkids.app.auth.ParentOnlyAction
import tv.safetubeforkids.app.auth.PinManager
import tv.safetubeforkids.app.ui.theme.KidAccent
import tv.safetubeforkids.app.ui.theme.KidSurface
import tv.safetubeforkids.app.ui.theme.KidText
import tv.safetubeforkids.app.ui.theme.KidTextDim
import tv.safetubeforkids.app.ui.theme.StatusError

/**
 * "This one needs the Parent PIN."
 *
 * Shown over the Settings screen when a parent-only action is pressed. It draws nothing but the
 * question and the existing [PinKeypad] - the same six-digit keypad, and the same
 * [tv.safetubeforkids.app.auth.PinManager] behind it, that the recovery screen and onboarding already
 * use, so a parent who has done this once on this TV has done it before.
 *
 * **The screen does not do the work.** This composable collects a PIN and calls
 * [tv.safetubeforkids.app.auth.ParentGate.run], which verifies and performs in one step. There is no
 * path from here to the mutation that skips the check, which is the whole point: the old defect was a
 * button that deleted rows itself.
 *
 * **Cancelling is safe by construction.** BACK, Cancel, or simply never answering all end in
 * [onCancelled] without a PIN ever reaching the gate, so nothing is performed. Nothing here is
 * remembered between shows: a PIN that was right a minute ago is asked for again, because the
 * authorization belongs to the action, not to the screen.
 */
@Composable
fun ParentPinPrompt(
    action: ParentOnlyAction,
    onAuthorized: (ParentOnlyAction) -> Unit,
    onCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstDigit = remember { FocusRequester() }

    // The remote has to be in the prompt, not on the button underneath it.
    LaunchedEffect(Unit) { runCatching { firstDigit.requestFocus() } }

    BackHandler(enabled = !working) { onCancelled() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.86f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(KidSurface, RoundedCornerShape(16.dp))
                .border(1.dp, KidAccent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(horizontal = 36.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Parent PIN needed",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = KidText,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = action.label,
                style = MaterialTheme.typography.titleMedium,
                color = KidAccent,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = action.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = KidTextDim,
            )
            Spacer(modifier = Modifier.height(22.dp))

            PinKeypad(
                pin = pin,
                label = "Parent PIN",
                onChange = { entered -> pin = entered; error = null },
                enabled = !working,
                initialFocus = firstDigit,
            )

            error?.let { message ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = StatusError)
            }

            Spacer(modifier = Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        working = true
                        // PBKDF2 is slow on purpose: never on the main thread, exactly as the recovery
                        // screen does it. A slow derivation on the UI thread is an ANR on this hardware.
                        scope.launch(Dispatchers.Default) {
                            val result = ServiceLocator.parentGate.run(action, pin)
                            withContext(Dispatchers.Main) {
                                working = false
                                when (result) {
                                    is ParentGateResult.Authorized -> onAuthorized(action)
                                    is ParentGateResult.Refused -> {
                                        // Stay open: the parent is still standing there, and the
                                        // existing limiter has already counted the attempt.
                                        error = result.message
                                        pin = ""
                                    }
                                }
                            }
                        }
                    },
                    enabled = pin.length == PinManager.PIN_LENGTH && !working,
                    colors = ButtonDefaults.buttonColors(containerColor = KidAccent),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = if (working) "Checking\u2026" else "Confirm",
                        color = Color.Black,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Button(
                    onClick = onCancelled,
                    enabled = !working,
                    colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Cancel", color = KidText, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Press Back to leave this without changing anything.",
                style = MaterialTheme.typography.bodySmall,
                color = KidTextDim,
            )
        }
    }
}
