package tv.safetubeforkids.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.timelimits.LockReason
import tv.safetubeforkids.app.timelimits.LoopbackTimeLimitRequestSender
import tv.safetubeforkids.app.timelimits.TimeLimitRequestState
import tv.safetubeforkids.app.timelimits.TimeLimitStatus
import tv.safetubeforkids.app.timelimits.toRequestState
import tv.safetubeforkids.app.ui.theme.KidBackground
import tv.safetubeforkids.app.ui.theme.KidText
import tv.safetubeforkids.app.ui.theme.KidTextDim
import tv.safetubeforkids.app.ui.theme.ParentAccent

@Composable
fun LockScreen(
    reason: String,
    onUnlocked: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val requestButtonFocus = remember { FocusRequester() }
    val requestSender = remember { LoopbackTimeLimitRequestSender() }
    var requestState by remember { mutableStateOf(TimeLimitRequestState.IDLE) }
    var requestInFlight by remember { mutableStateOf(false) }

    val lockReason = try {
        LockReason.valueOf(reason.uppercase())
    } catch (_: Exception) {
        LockReason.MANUAL_LOCK
    }

    val icon = when (lockReason) {
        LockReason.DAILY_LIMIT -> Icons.Rounded.Timer
        LockReason.BEDTIME -> Icons.Rounded.Bedtime
        LockReason.MANUAL_LOCK -> Icons.Rounded.Lock
    }

    val title = when (lockReason) {
        LockReason.DAILY_LIMIT -> "All done for today!"
        LockReason.BEDTIME -> "Time for bed!"
        LockReason.MANUAL_LOCK -> "Taking a break!"
    }

    val subtitle = when (lockReason) {
        LockReason.DAILY_LIMIT -> "See you tomorrow!"
        LockReason.BEDTIME -> "TV time starts again in the morning."
        LockReason.MANUAL_LOCK -> "Ask your parent to unlock."
    }

    // Poll canPlay() every 5 seconds — navigate away when Allowed
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            val status = ServiceLocator.timeLimitManager.canPlay()
            if (status is TimeLimitStatus.Allowed) {
                onUnlocked()
                return@LaunchedEffect
            }
        }
    }

    // Request focus
    LaunchedEffect(Unit) {
        // The only actionable control owns focus, so the remote can reach it. runCatching for the same
        // reason the catalog cards use it: a request against a node that is not focusable yet (or is
        // disabled, once a request has been sent) must not throw.
        runCatching { requestButtonFocus.requestFocus() }
    }

    // BACK must not leave the lock. It used to be swallowed by the root, which meant every D-pad key
    // was swallowed with it and the child could never reach the button below. Consuming only BACK here
    // keeps the lock exactly as strict while leaving D-pad movement to Compose's focus system.
    BackHandler(enabled = true) { }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KidBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = KidTextDim,
                modifier = Modifier.size(72.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = KidText,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = KidTextDim,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    // The server's own two-minute throttle stays authoritative; this only stops a second
                    // tap while a request is in flight.
                    if (!requestInFlight && requestState != TimeLimitRequestState.SENT) {
                        scope.launch {
                            requestInFlight = true
                            requestState = requestSender.requestMoreTime().toRequestState()
                            requestInFlight = false
                        }
                    }
                },
                // Disabled only once the server has actually taken the request: a failure leaves the
                // button usable so the child can try again, and keeps focus where it was.
                enabled = requestState != TimeLimitRequestState.SENT,
                colors = ButtonDefaults.buttonColors(containerColor = ParentAccent),
                modifier = Modifier.focusRequester(requestButtonFocus),
            ) {
                Text(
                    text = when (requestState) {
                        TimeLimitRequestState.IDLE -> "Request More Time"
                        TimeLimitRequestState.SENT -> "Request sent!"
                        TimeLimitRequestState.FAILED -> "Request failed. Try again."
                    },
                    color = KidText,
                )
            }
        }
    }
}
