package tv.safetubeforkids.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.auth.PinSetupResult
import tv.safetubeforkids.app.ui.components.PinKeypad
import tv.safetubeforkids.app.ui.theme.KidAccent
import tv.safetubeforkids.app.ui.theme.KidBackground
import tv.safetubeforkids.app.ui.theme.KidSurface
import tv.safetubeforkids.app.ui.theme.KidText
import tv.safetubeforkids.app.ui.theme.KidTextDim
import tv.safetubeforkids.app.ui.theme.OverscanPadding
import tv.safetubeforkids.app.ui.theme.StatusError
import tv.safetubeforkids.app.util.NetworkUtils
import tv.safetubeforkids.app.util.QrCodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First run: the five things a parent has to do before the TV is theirs.
 *
 * Welcome -> Connect phone -> Create Parent PIN -> Recovery Code -> Ready.
 *
 * Two decisions are worth stating, because both are about *not* doing something:
 *
 * 1. **The QR code no longer contains the PIN.** It used to, because the PIN was regenerated on every
 *    app start and therefore had to be handed to the phone somehow. That made the pairing code and the
 *    credential the same secret, and put the credential in a scannable image. The QR is now the
 *    dashboard address and nothing else; the parent types the PIN they chose, which is the one thing
 *    an image cannot leak.
 *
 * 2. **The Parent PIN is chosen here, on the TV, and nowhere else.** The phone is where the library is
 *    edited; the television is the thing in the house, with a screen and a remote, and first-run setup
 *    is the one moment where "type it on the TV" is both reasonable and the safest place for the
 *    secret to be created.
 *
 * The whole flow is skippable in one direction only: forward. Going back is offered where it is
 * harmless (the connection screen) and not where it would discard a PIN the parent has just set.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var step by remember { mutableStateOf(Step.WELCOME) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var recoveryCode by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    var localUrl by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(Unit) {
        val ip = NetworkUtils.getDeviceIp(context)
        if (ip != null) {
            val url = NetworkUtils.buildConnectUrl(ip)
            localUrl = url
            qrBitmap = QrCodeGenerator.generate(url)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KidBackground)
            .padding(OverscanPadding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (step) {
                Step.WELCOME -> {
                    Text(
                        "Welcome to SafeTube",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = KidText,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Set up SafeTube from your phone and create a Parent PIN.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = KidTextDim,
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    PrimaryButton("Continue") { step = Step.CONNECT }
                }

                Step.CONNECT -> {
                    Text(
                        "Connect your phone",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = KidText,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Scan this with your phone\u2019s camera to open the parent page.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KidTextDim,
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "QR code for the SafeTube parent page on this network",
                            modifier = Modifier.size(220.dp),
                        )
                    } else {
                        Text(
                            "This TV is not on a network yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StatusError,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    localUrl?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            color = KidAccent,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Or carry on here and set the PIN up on the TV.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KidTextDim,
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton("Back") { step = Step.WELCOME }
                        PrimaryButton("Continue") { step = Step.CREATE_PIN }
                    }
                }

                Step.CREATE_PIN -> {
                    Text(
                        "Create Parent PIN",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = KidText,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Six digits. You will type this on your phone to change what your child can watch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KidTextDim,
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    // The two keypads sit side by side rather than one above the other. Stacked, this
                    // screen is taller than a 1080p television, and the confirmation keypad ends up
                    // below the visible area with no way to scroll to it - a parent simply cannot
                    // finish setup. Found on the Mi Box during W10 verification, which is the only
                    // place that could have found it.
                    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        PinKeypad(pin = pin, label = "Parent PIN", onChange = { pin = it; error = null })
                        PinKeypad(pin = confirm, label = "Confirm Parent PIN", onChange = { confirm = it; error = null })
                    }

                    ErrorLine(error)

                    Spacer(modifier = Modifier.height(20.dp))
                    PrimaryButton(
                        text = if (creating) "Creating\u2026" else "Continue",
                        enabled = pin.length == 6 && confirm.length == 6 && !creating,
                    ) {
                        creating = true
                        // The derivation is deliberately slow - that is what makes a stolen preferences
                        // file expensive to attack - so it must not run on the thread that draws the
                        // screen. Doing it inline here is what produced an ANR on the Mi Box: eleven
                        // seconds of blocked input, and Android force-finishing the activity in the
                        // middle of a parent's first setup.
                        scope.launch(Dispatchers.Default) {
                            val result = ServiceLocator.pinManager.setup(pin, confirm)
                            withContext(Dispatchers.Main) {
                                creating = false
                                when (result) {
                                    is PinSetupResult.Created -> {
                                        recoveryCode = result.recoveryCode
                                        step = Step.RECOVERY
                                    }
                                    is PinSetupResult.Invalid -> error = result.reason
                                    // Already set up: the credential is never replaced silently, so
                                    // this can only mean the state moved under us. Carry on rather
                                    // than overwrite.
                                    PinSetupResult.AlreadyConfigured -> step = Step.READY
                                }
                            }
                        }
                    }
                }

                Step.RECOVERY -> {
                    Text(
                        "SafeTube Recovery Code",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = KidText,
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    Box(
                        modifier = Modifier
                            .border(2.dp, KidAccent, RoundedCornerShape(12.dp))
                            .padding(horizontal = 32.dp, vertical = 20.dp),
                    ) {
                        Text(
                            recoveryCode,
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "This code can be used to reset your Parent PIN if you forget it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KidTextDim,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "It is shown once. SafeTube cannot show it again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusError,
                    )

                    Spacer(modifier = Modifier.height(28.dp))
                    PrimaryButton("I\u2019ve Saved It") {
                        ServiceLocator.pinManager.acknowledgeRecoveryCode()
                        step = Step.READY
                    }
                }

                Step.READY -> {
                    Text(
                        "SafeTube is ready",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = KidText,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Open the parent page on your phone to choose what your child can watch.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = KidTextDim,
                    )
                    if (ServiceLocator.pinManager.pendingRecoveryCode() != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "A Recovery Code is still waiting on the TV settings screen, if you have not written it down.",
                            style = MaterialTheme.typography.bodySmall,
                            color = KidTextDim,
                        )
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    PrimaryButton("Start watching") { onFinished() }
                }
            }
        }
    }
}

private enum class Step { WELCOME, CONNECT, CREATE_PIN, RECOVERY, READY }

@Composable
private fun ErrorLine(error: String?) {
    if (error == null) return
    Spacer(modifier = Modifier.height(12.dp))
    Text(error, style = MaterialTheme.typography.bodyMedium, color = StatusError)
}

@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = KidAccent, contentColor = KidBackground),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(text, color = KidText, fontWeight = FontWeight.SemiBold)
    }
}
