package tv.safetubeforkids.app.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.auth.PinCheckResult
import tv.safetubeforkids.app.ui.components.PinKeypad
import tv.safetubeforkids.app.ui.theme.KidAccent
import tv.safetubeforkids.app.ui.theme.KidBackground
import tv.safetubeforkids.app.ui.theme.KidText
import tv.safetubeforkids.app.ui.theme.KidTextDim
import tv.safetubeforkids.app.ui.theme.OverscanPadding
import tv.safetubeforkids.app.ui.theme.StatusError

/**
 * The Recovery Code, on the TV, when the parent asks for one.
 *
 * Two honest limits are printed on the screen rather than hidden in a manual:
 *
 *  - a code is shown **once**, because only a hash of it is kept. A TV that could show it again would
 *    be a TV where anyone standing in the room could read the parent's fallback credential;
 *  - making a new one **invalidates the old**, so replacing a code that was written down is a real
 *    replacement, not a second key that lives forever.
 *
 * A new code can only be made here by someone who knows the current Parent PIN. The dashboard can
 * also rotate the code, but that requires a signed-in session, and a session is not something a child
 * has. Without the PIN check this screen would be a way for anyone in the room to invalidate the
 * parent's paper code.
 */
@Composable
fun RecoveryCodeScreen(onBack: () -> Unit) {
    var pending by remember { mutableStateOf(ServiceLocator.pinManager.pendingRecoveryCode()) }
    var askingForPin by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var acknowledged by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KidBackground)
            .padding(OverscanPadding)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "SafeTube Recovery Code",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = KidText,
        )
        Spacer(modifier = Modifier.height(20.dp))

        if (pending != null && !acknowledged) {
            Box(
                modifier = Modifier
                    .border(2.dp, KidAccent, RoundedCornerShape(12.dp))
                    .padding(horizontal = 32.dp, vertical = 20.dp),
            ) {
                Text(
                    pending!!,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = KidAccent,
                    letterSpacing = 4.sp,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "Write this down and keep it safe.",
                style = MaterialTheme.typography.bodyLarge,
                color = KidText,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "This code can be used to reset your Parent PIN if you forget it. It is shown once.",
                style = MaterialTheme.typography.bodyMedium,
                color = KidTextDim,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("I\u2019ve Saved It") {
                    ServiceLocator.pinManager.acknowledgeRecoveryCode()
                    acknowledged = true
                }
                SecondaryButton("Back") { onBack() }
            }
        } else if (askingForPin) {
            Text(
                "Enter your current Parent PIN to make a new Recovery Code.",
                style = MaterialTheme.typography.bodyMedium,
                color = KidTextDim,
            )
            Spacer(modifier = Modifier.height(20.dp))
            PinKeypad(pin = pin, label = "Parent PIN", onChange = { pin = it; error = null })
            error?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = StatusError)
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(text = if (working) "Making\u2026" else "Make a new code", enabled = pin.length == 6 && !working) {
                    working = true
                    // Off the UI thread for the same reason setup is: the derivation is slow on purpose,
                    // and a slow derivation on the main thread is an ANR on this hardware.
                    scope.launch(Dispatchers.Default) {
                        val check = ServiceLocator.pinManager.checkPin(pin)
                        val rotated = if (check is PinCheckResult.Verified) {
                            // Issuing a code is a credential operation, so the sessions issued under
                            // the old one go with it - the same rule a PIN change follows.
                            ServiceLocator.sessionManager.invalidateAll()
                            ServiceLocator.pinManager.rotateRecoveryCode()
                        } else {
                            null
                        }
                        withContext(Dispatchers.Main) {
                            working = false
                            if (rotated != null) {
                                pending = rotated
                                acknowledged = false
                                askingForPin = false
                                pin = ""
                            } else {
                                error = when (check) {
                                    is PinCheckResult.Wrong -> "That is not the Parent PIN."
                                    is PinCheckResult.RateLimited -> "Too many tries. Wait a few minutes."
                                    is PinCheckResult.NotSetUp -> "This TV has no Parent PIN yet."
                                    else -> "That is not the Parent PIN."
                                }
                            }
                        }
                    }
                }
                SecondaryButton("Cancel") {
                    askingForPin = false
                    pin = ""
                    error = null
                }
            }
        } else {
            Text(
                "No Recovery Code is waiting to be read.",
                style = MaterialTheme.typography.bodyLarge,
                color = KidText,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "A code is shown once, when it is made. If you have lost yours, make a new one - " +
                    "the old one stops working.",
                style = MaterialTheme.typography.bodyMedium,
                color = KidTextDim,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("Make a new code") { askingForPin = true }
                SecondaryButton("Back") { onBack() }
            }
        }
    }
}
