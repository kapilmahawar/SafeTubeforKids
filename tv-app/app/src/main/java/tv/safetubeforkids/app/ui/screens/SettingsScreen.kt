package tv.safetubeforkids.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tv.safetubeforkids.app.BuildConfig
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.auth.ParentOnlyAction
import tv.safetubeforkids.app.ui.components.LogPanel
import tv.safetubeforkids.app.ui.components.ParentPinPrompt
import tv.safetubeforkids.app.ui.theme.KidAccent
import tv.safetubeforkids.app.ui.theme.KidBackground
import tv.safetubeforkids.app.ui.theme.KidSurface
import tv.safetubeforkids.app.ui.theme.KidText
import tv.safetubeforkids.app.ui.theme.KidTextDim
import tv.safetubeforkids.app.ui.theme.OverscanPadding
import tv.safetubeforkids.app.ui.theme.StatusWarning
import tv.safetubeforkids.app.util.AppLogger
import tv.safetubeforkids.app.util.OfflineSimulator

/**
 * Settings, on the television.
 *
 * **W13.1: the destructive actions moved behind the Parent PIN.** Until this change the screen
 * performed them itself, with a plain `onClick` - so `Clear Events` deleted the watch history two
 * presses from a child's hand, which is what the daily limit and bedtime are counted from, and `Sign
 * Out All Sessions` invalidated every parent session the same way. Both now open
 * [ParentPinPrompt], which collects the PIN and hands it to
 * [tv.safetubeforkids.app.auth.ParentGate]: the verification *and* the mutation happen inside the
 * gate, so this screen can no longer do either on its own. The classification of every action on this
 * screen, and the one deliberate exception (`Reset SafeTube`, which keeps the typed-phrase
 * confirmation that is its documented last-resort design), is written out in `ParentOnlyAction`.
 *
 * They are also grouped honestly now. `Clear Watch History`, `Sign Out All Sessions` and
 * `Reset SafeTube` used to sit under the **Debug** heading, which is how they came to look like
 * developer leftovers; the debug tools are the only thing under Debug, and only in a debug build.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onParentAccess: () -> Unit = {},
    onResetSafeTube: () -> Unit = {},
) {
    var showLog by remember { mutableStateOf(false) }
    var sessionCount by remember { mutableStateOf(ServiceLocator.sessionManager.getActiveSessionCount()) }
    val pinConfigured = ServiceLocator.pinManager.isConfigured()

    /**
     * The parent-only action currently asking for a PIN, and what to report once one has run.
     *
     * Neither is a permission. [pending] is only "which prompt is open", and the action has not
     * happened until the gate has verified the PIN - dismissing the prompt leaves nothing behind.
     */
    var pending by remember { mutableStateOf<ParentOnlyAction?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KidBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(OverscanPadding),
        ) {
            // One column of settings. The right-hand column that used to sit beside it held third-party
            // support text inherited from the project SafeTube forked from - it was not SafeTube's, it is
            // not shown anywhere any more, and this comment stays so that nobody reinstates it. The row
            // itself stays because it is what applies the screen's padding.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium, color = KidText)
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Back", color = KidText, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // --- General ---
                Text("General", style = MaterialTheme.typography.titleMedium, color = KidText)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsBtn("Refresh Videos") { onRefresh() }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = KidTextDim)

                Spacer(modifier = Modifier.height(24.dp))

                // --- Connection ---
                Text("Connection", style = MaterialTheme.typography.titleMedium, color = KidText)
                Spacer(modifier = Modifier.height(8.dp))
                // The PIN itself is not shown here, or anywhere. It is stored as a verifier, so there is
                // no PIN to show - which is the W10 change: the old screen printed a secret that anyone
                // in the room could read, and it printed a different one after every app start.
                Text(
                    if (pinConfigured) "Parent PIN: set" else "Parent PIN: not set up yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = KidTextDim,
                )
                Text("Active sessions: $sessionCount", style = MaterialTheme.typography.bodySmall, color = KidTextDim)

                Spacer(modifier = Modifier.height(24.dp))

                // --- Parent access ---
                // Reachable without a PIN, deliberately, and unchanged from before W10: the parent who has
                // forgotten their PIN is exactly the person who needs the recovery code and, failing that,
                // the reset. Neither button grants anything on its own - the recovery screen asks for the
                // current PIN before issuing a new code, and the reset asks for a typed phrase.
                Text("Parent access", style = MaterialTheme.typography.titleMedium, color = KidText)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Recovery Code: " + if (ServiceLocator.pinManager.pendingRecoveryCode() != null) {
                        "waiting to be written down"
                    } else {
                        "not shown (you can make a new one)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = KidTextDim,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsBtn("Recovery Code") { onParentAccess() }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // --- Parents ---
                // The actions that change protected state, and the only ones that do not belong to the
                // child. Two of them ask for the Parent PIN here; Reset SafeTube asks for its own phrase
                // on its own screen, because it is the way back in for a parent who has lost both
                // credentials.
                Text("Parents", style = MaterialTheme.typography.titleMedium, color = KidText)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Clearing the watch history and signing out need the Parent PIN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KidTextDim,
                )
                notice?.let { message ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall, color = KidAccent)
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The label comes from the action itself, so the button and the prompt that explains
                    // it can never drift apart.
                    SettingsBtn(ParentOnlyAction.RESET_WATCH_TIME.label) {
                        notice = null
                        pending = ParentOnlyAction.RESET_WATCH_TIME
                    }
                    SettingsBtn(ParentOnlyAction.SIGN_OUT_SESSIONS.label) {
                        notice = null
                        pending = ParentOnlyAction.SIGN_OUT_SESSIONS
                    }
                    SettingsBtn("Reset SafeTube") { onResetSafeTube() }
                }

                // --- Debug ---
                // Developer tools only, and only where they belong. They are compiled out of a build a
                // family installs, so nothing below this line is reachable on a normal television.
                if (BuildConfig.IS_DEBUG) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("Debug", style = MaterialTheme.typography.titleMedium, color = StatusWarning)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Offline sim: ${OfflineSimulator.isOffline}", style = MaterialTheme.typography.bodySmall, color = KidTextDim)
                    Spacer(modifier = Modifier.height(12.dp))

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsBtn(if (OfflineSimulator.isOffline) "Go Online" else "Simulate Offline") {
                            OfflineSimulator.toggle()
                        }
                        SettingsBtn(if (showLog) "Hide Log" else "Show Log") { showLog = !showLog }
                        SettingsBtn("Clear Log") { AppLogger.clear() }
                    }

                    if (showLog) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LogPanel()
                    }
                }
            }
        }

        pending?.let { action ->
            ParentPinPrompt(
                action = action,
                onAuthorized = { performed ->
                    pending = null
                    notice = when (performed) {
                        ParentOnlyAction.RESET_WATCH_TIME ->
                            "Watch history cleared. Today's watch time counts from zero again."
                        ParentOnlyAction.SIGN_OUT_SESSIONS ->
                            "Every parent session was signed out."
                    }
                    // The count on screen has to agree with what just happened.
                    if (performed == ParentOnlyAction.SIGN_OUT_SESSIONS) {
                        sessionCount = ServiceLocator.sessionManager.getActiveSessionCount()
                    }
                },
                onCancelled = { pending = null },
            )
        }
    }
}

@Composable
private fun SettingsBtn(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(text, color = KidText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
