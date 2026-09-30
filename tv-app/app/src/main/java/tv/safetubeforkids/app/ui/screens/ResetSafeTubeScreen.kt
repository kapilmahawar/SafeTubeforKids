package tv.safetubeforkids.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.auth.ResetAuthorization
import tv.safetubeforkids.app.auth.ResetAuthorizationResult
import tv.safetubeforkids.app.reset.SafeTubeReset
import tv.safetubeforkids.app.ui.components.PinKeypad
import tv.safetubeforkids.app.ui.theme.KidAccent
import tv.safetubeforkids.app.ui.theme.KidBackground
import tv.safetubeforkids.app.ui.theme.KidSurface
import tv.safetubeforkids.app.ui.theme.KidText
import tv.safetubeforkids.app.ui.theme.KidTextDim
import tv.safetubeforkids.app.ui.theme.OverscanPadding
import tv.safetubeforkids.app.ui.theme.StatusError

/**
 * The destructive reset, on the TV: a parent credential, then a phrase, then a second question.
 *
 * **Why a credential comes first (W13.1b).** The phrase is printed on this screen, so it proves intent
 * and nothing else: anyone who can read can copy it. On its own that left a child able to erase the
 * family's configuration - and then to take ownership of the TV by choosing the next Parent PIN during
 * onboarding. So the flow now opens with a Parent PIN or a Recovery Code, the two credentials the
 * product already recognises, and only a verified one moves it on to the phrase. The check is not merely
 * this screen's: `SafeTubeReset.wipe` will not accept a caller that has not proved itself.
 * Documentation for the parent is in `docs/W10_PARENT_ACCESS.md`.
 *
 * **Why the phrase and the question stayed.** They are the second safeguard in front of an irreversible
 * operation, and a credential does not make anyone less likely to press through a dialog: the phrase is
 * the only part of this screen a person cannot get through by pressing a button twice.
 * `I UNDERSTAND THIS ERASES EVERYTHING` is five words of intent, and typing it means reading it; an `OK`
 * dialog on a television is something a thumb does while the eyes are elsewhere. Case and stray spaces
 * are forgiven - the *reading* is what matters, not the shift key - but a partial phrase is refused, and
 * there is a second, separate question afterwards, because "are you sure?" should not be the same
 * gesture as "I am sure".
 *
 * **What happens to a parent who has lost both credentials.** Nothing on this television can tell them
 * from anybody else holding the remote, so this screen is not their way back: the app's own state has to
 * be removed from Android's side (Settings → Apps → SafeTube for Kids → Clear storage, or uninstall and
 * reinstall), after which SafeTube starts at first-run setup. That is the documented path, and it is
 * honest about its consequences: everything SafeTube stored goes, exactly as it would here.
 */
@Composable
fun ResetSafeTubeScreen(onBack: () -> Unit, onReset: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current

    /**
     * While the television's keyboard is up, Back closes the keyboard instead of leaving the screen.
     *
     * This is the second half of the same defect. Focusing the field opens the system keyboard, and
     * while it is open the keyboard - not the app - receives the remote: taps on the buttons below are
     * swallowed, directional keys move the keyboard's own selection, and Back closes the keyboard *and*
     * pops the screen, taking the typed phrase with it. A parent could therefore type the phrase and
     * have no way to press Continue, on the screen that erases everything.
     *
     * With this, Back does what a person expects it to: the keyboard goes away and the screen stays.
     * The field's own Down/Enter handling above then hands the remote to Continue.
     */
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    BackHandler(enabled = keyboardVisible) { keyboard?.hide() }

    var phrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    /**
     * The parent's authorization, or null while the screen still needs one.
     *
     * W13.1b put a credential in front of this screen, because the phrase below is printed on it: it is a
     * reading of intent, not a secret, so on its own it left a child able to erase the family's
     * configuration and then take ownership by choosing the next PIN. The screen now walks three stages,
     * and this value is what moves it from the first to the second - and what the wipe requires, since
     * `SafeTubeReset.wipe` will not accept a caller that has not proved itself. The phrase and the second
     * question are deliberately kept: they are the second safeguard in front of an irreversible
     * operation, and a credential does not make a person any less likely to press through a dialog.
     */
    var authorization by remember { mutableStateOf<ResetAuthorization?>(null) }
    var method by remember { mutableStateOf<ResetAuthorization.Method?>(null) }
    var pin by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    /** Ask the gate, off the UI thread: PBKDF2 is slow on purpose, and slow on the main thread is an ANR. */
    fun authorize(submit: suspend () -> ResetAuthorizationResult) {
        checking = true
        authError = null
        scope.launch(Dispatchers.Default) {
            val result = submit()
            withContext(Dispatchers.Main) {
                checking = false
                when (result) {
                    is ResetAuthorizationResult.Granted -> {
                        authorization = result.authorization
                        // The secret has done its job; it does not stay in the composition.
                        pin = ""
                        code = ""
                    }
                    is ResetAuthorizationResult.Refused -> authError = result.message
                }
            }
        }
    }

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
            "Reset SafeTube",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = StatusError,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "This will erase all SafeTube data, including your library, settings, " +
                "and parent access credentials.",
            style = MaterialTheme.typography.bodyLarge,
            color = KidText,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text("This cannot be undone.", style = MaterialTheme.typography.bodyLarge, color = StatusError)

        when {
            authorization == null -> {
                // --- Stage 1: the parent's credential ------------------------------------------
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    "A parent has to approve this.",
                    style = MaterialTheme.typography.titleMedium,
                    color = KidText,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Entering the Parent PIN or the Recovery Code authorizes erasing everything " +
                        "SafeTube stores on this TV. Nothing is erased until you also type the phrase " +
                        "and answer the question after it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KidTextDim,
                )
                Spacer(modifier = Modifier.height(20.dp))

                if (method == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { method = ResetAuthorization.Method.PARENT_PIN; authError = null },
                            colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Use the Parent PIN", color = KidText, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = { method = ResetAuthorization.Method.RECOVERY_CODE; authError = null },
                            colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Use the Recovery Code", color = KidText, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else if (method == ResetAuthorization.Method.PARENT_PIN) {
                    PinKeypad(
                        pin = pin,
                        label = "Parent PIN",
                        onChange = { entered -> pin = entered; authError = null },
                        enabled = !checking,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { authorize { ServiceLocator.resetGate.authorizeWithPin(pin) } },
                            enabled = pin.length == tv.safetubeforkids.app.auth.PinManager.PIN_LENGTH && !checking,
                            colors = ButtonDefaults.buttonColors(containerColor = KidAccent),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                if (checking) "Checking\u2026" else "Continue",
                                color = Color.Black,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Button(
                            onClick = { method = null; pin = ""; authError = null },
                            enabled = !checking,
                            colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Back", color = KidText, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = onBack,
                            enabled = !checking,
                            colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Cancel", color = KidText, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Text(
                        "Recovery Code, in three groups of four.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KidTextDim,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; authError = null },
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            // The same hand-off the phrase field needs, for the same measured reason: a
                            // focused field opens the television's keyboard, which then owns the remote.
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.DirectionDown || event.key == Key.Enter)
                                ) {
                                    keyboard?.hide()
                                    focusManager.moveFocus(FocusDirection.Down)
                                    true
                                } else {
                                    false
                                }
                            },
                        singleLine = true,
                        label = { Text("Recovery Code", color = KidTextDim) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            keyboard?.hide()
                            focusManager.moveFocus(FocusDirection.Down)
                        }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = KidSurface,
                            unfocusedContainerColor = KidSurface,
                            focusedTextColor = KidText,
                            unfocusedTextColor = KidTextDim,
                            focusedIndicatorColor = KidAccent,
                            cursorColor = KidText,
                        ),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                authorize { ServiceLocator.resetGate.authorizeWithRecoveryCode(code) }
                            },
                            enabled = code.isNotBlank() && !checking,
                            colors = ButtonDefaults.buttonColors(containerColor = KidAccent),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                if (checking) "Checking\u2026" else "Verify",
                                color = Color.Black,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Button(
                            onClick = { method = null; code = ""; authError = null },
                            enabled = !checking,
                            colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Back", color = KidText, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = onBack,
                            enabled = !checking,
                            colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Cancel", color = KidText, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                authError?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = StatusError)
                }
            }

            confirming -> {
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                "Erase everything and reset SafeTube?",
                style = MaterialTheme.typography.titleMedium,
                color = KidText,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { confirming = false },
                    enabled = !working,
                    colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Cancel", color = KidText, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = {
                        working = true
                        // The wipe is database and file work: off the UI thread, and only then does the
                        // screen say what happened.
                        //
                        // The authorization is passed rather than implied: the wipe takes a proof of a
                        // verified parent credential, and this screen holds the one it obtained in stage
                        // one. Reaching this button without it is not a state the composition can be in -
                        // the first stage is chosen whenever it is null - and even if it were, the call
                        // below would not have anything to pass.
                        val proof = authorization
                        scope.launch(Dispatchers.IO) {
                            if (proof == null) return@launch
                            SafeTubeReset.wipe(context, proof)
                            withContext(Dispatchers.Main) { onReset() }
                        }
                    },
                    enabled = !working && authorization != null,
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        if (working) "Erasing\u2026" else "ERASE & RESET",
                        color = KidText,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            }

            else -> {
            Spacer(modifier = Modifier.height(28.dp))
            Text("Type:", style = MaterialTheme.typography.bodyMedium, color = KidTextDim)
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                SafeTubeReset.CONFIRMATION_PHRASE,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = KidText,
                modifier = Modifier
                    .border(1.dp, KidSurface, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = phrase,
                onValueChange = { phrase = it; error = null },
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    // A remote has no pointer, and a focused text field on this television opens a
                    // keyboard that swallows both the remote and any tap aimed at the buttons below -
                    // measured on the Mi Box: with the field focused, pressing DPAD_DOWN did nothing,
                    // DPAD_CENTER inserted a character, and a tap on Cancel was consumed by the
                    // keyboard window. A parent could type the phrase and then have no way to press
                    // Continue at all, on the one screen in the product that erases everything.
                    //
                    // So the field hands the remote over deliberately: Down (or Enter) closes the
                    // keyboard and moves focus to Continue, which is the only thing anyone wants to do
                    // after typing.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.DirectionDown || event.key == Key.Enter)
                        ) {
                            keyboard?.hide()
                            focusManager.moveFocus(FocusDirection.Down)
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                label = { Text("Type the phrase exactly", color = KidTextDim) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    // "Done" on the keyboard is how a television parent says "I have finished typing":
                    // the key closes the keyboard and puts focus on Continue, which is the only thing
                    // anyone wants next. Without it the keyboard keeps the remote, and the buttons
                    // below it cannot be reached at all.
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    focusManager.moveFocus(FocusDirection.Down)
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = KidSurface,
                    unfocusedContainerColor = KidSurface,
                    focusedTextColor = KidText,
                    unfocusedTextColor = KidText,
                    focusedIndicatorColor = StatusError,
                    cursorColor = KidText,
                ),
            )

            error?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = StatusError)
            }

            Spacer(modifier = Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (SafeTubeReset.phraseMatches(phrase)) {
                            confirming = true
                            error = null
                        } else {
                            // Deliberately no hint about which part is wrong: the phrase is printed
                            // two lines above, and "close enough" is not a thing this button has.
                            error = "That is not the phrase. Nothing was erased."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Continue", color = KidText, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Cancel", color = KidText, fontWeight = FontWeight.SemiBold)
                }
            }
            }
        }
    }
}
