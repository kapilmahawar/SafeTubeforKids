package tv.parentapproved.app.ui.player

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import tv.parentapproved.app.playback.PlaybackKeys
import tv.parentapproved.app.ui.theme.KidAccent
import tv.parentapproved.app.ui.theme.KidText
import tv.parentapproved.app.ui.theme.KidTextDim
import tv.parentapproved.app.ui.theme.StatusError
import tv.parentapproved.app.ui.theme.StatusWarning
import tv.parentapproved.app.util.AppLogger

private const val CONTROLS_TIMEOUT_MS = 4_000L
private const val SEEK_STEP_MS = 10_000L
private const val TICK_MS = 300L

/**
 * A remote-first Android TV player.
 *
 * Video and subtitles are rendered by Media3's [PlayerView] with its own controller disabled,
 * so every control on screen is drawn by this composable and sized for TV viewing distance.
 * D-pad keys map straight onto playback actions - no touch interaction is required or used.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TvPlayerScreen(
    player: Player,
    title: String,
    queueLabel: String?,
    errorMessage: String?,
    warningText: String?,
    resizeMode: Int,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onNextApproved: () -> Unit,
    onPreviousApproved: () -> Unit,
) {
    val context = LocalContext.current

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }

    val surfaceFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    // Poll the player for the state the overlay renders.
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            val reported = player.duration
            durationMs = if (reported == C.TIME_UNSET || reported < 0) 0L else reported
            isPlaying = player.isPlaying
            isBuffering = player.playbackState == Player.STATE_BUFFERING
            delay(TICK_MS)
        }
    }

    // Auto-hide, but never while paused, buffering, in error, or showing the time-limit warning.
    LaunchedEffect(controlsVisible, lastInteraction, isPlaying, isBuffering, errorMessage, warningText) {
        if (!controlsVisible || !isPlaying || isBuffering) return@LaunchedEffect
        if (errorMessage != null || warningText != null) return@LaunchedEffect
        delay(CONTROLS_TIMEOUT_MS)
        if (System.currentTimeMillis() - lastInteraction >= CONTROLS_TIMEOUT_MS) {
            controlsVisible = false
        }
    }

    // Seek feedback badge fades on its own.
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(900)
            seekFeedback = null
        }
    }

    // Keep focus sensible: the error actions take focus when shown, the surface otherwise.
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) retryFocus.requestFocus() else surfaceFocus.requestFocus()
    }

    LaunchedEffect(Unit) { surfaceFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(surfaceFocus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                lastInteraction = System.currentTimeMillis()
                controlsVisible = true

                val keyCode = event.nativeKeyEvent.keyCode
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    onBack()
                    return@onKeyEvent true
                }
                // Let the error actions own D-pad movement and clicks.
                if (errorMessage != null) return@onKeyEvent false

                val action = PlaybackKeys.actionOf(keyCode)
                if (action != null) AppLogger.log("Remote key -> ${action.name}")

                when (action) {
                    PlaybackKeys.Action.TogglePlayPause -> {
                        // Never touch the player directly: the controller owns playback state,
                        // watch-time accounting and authorization.
                        onTogglePlayPause()
                        true
                    }
                    PlaybackKeys.Action.SeekBackward -> {
                        onSeekBy(-SEEK_STEP_MS)
                        seekFeedback = "<< 10s"
                        true
                    }
                    PlaybackKeys.Action.SeekForward -> {
                        onSeekBy(SEEK_STEP_MS)
                        seekFeedback = "10s >>"
                        true
                    }
                    PlaybackKeys.Action.NextApproved -> {
                        onNextApproved()
                        true
                    }
                    PlaybackKeys.Action.PreviousApproved -> {
                        onPreviousApproved()
                        true
                    }
                    PlaybackKeys.Action.RevealControls -> true
                    null -> true
                }
            }
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    // All controls are Compose; Media3 only decodes video and subtitles here.
                    useController = false
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    // Never let the surface take focus: this composable owns every remote key.
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    subtitleView?.setFractionalTextSize(0.053f)
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = resizeMode
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        if (isBuffering) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = KidAccent, strokeWidth = 5.dp)
            }
        }

        seekFeedback?.let { badge ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.headlineLarge,
                    color = KidText,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 28.dp, vertical = 14.dp),
                )
            }
        }

        if (controlsVisible && errorMessage == null) {
            TopBar(
                title = title,
                queueLabel = queueLabel,
                modifier = Modifier.align(Alignment.TopStart),
            )
            BottomBar(
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        warningText?.let { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.titleLarge,
                color = KidText,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .background(StatusWarning.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        errorMessage?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.headlineMedium,
                        color = StatusError,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.focusRequester(retryFocus),
                        ) {
                            Text(
                                text = "Retry",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Button(onClick = onBack) {
                            Text(
                                text = "Back",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(title: String, queueLabel: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = KidText,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        queueLabel?.let { label ->
            Spacer(Modifier.width(24.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = KidTextDim,
            )
        }
    }
}

@Composable
private fun BottomBar(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 32.dp, vertical = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Replay10,
                contentDescription = "Rewind 10 seconds",
                tint = KidText,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.width(16.dp))
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = KidText,
                modifier = Modifier.size(42.dp),
            )
            Spacer(Modifier.width(16.dp))
            Icon(
                imageVector = Icons.Rounded.Forward10,
                contentDescription = "Forward 10 seconds",
                tint = KidText,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.width(28.dp))
            Text(
                text = formatTime(positionMs),
                style = MaterialTheme.typography.titleMedium,
                color = KidText,
            )
            Spacer(Modifier.width(20.dp))
            SeekBar(
                positionMs = positionMs,
                durationMs = durationMs,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            Text(
                text = formatTime(durationMs),
                style = MaterialTheme.typography.titleMedium,
                color = KidText,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Left / Right  seek 10s      OK  play or pause      Back  exit",
            style = MaterialTheme.typography.bodyLarge,
            color = KidTextDim,
        )
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    val fraction = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp),
    ) {
        val barHeight = size.height * 0.28f
        val top = (size.height - barHeight) / 2f
        val radius = CornerRadius(barHeight / 2f, barHeight / 2f)

        drawRoundRect(
            color = Color.White.copy(alpha = 0.28f),
            topLeft = Offset(0f, top),
            size = Size(size.width, barHeight),
            cornerRadius = radius,
        )

        val played = size.width * fraction
        if (played > 0f) {
            drawRoundRect(
                color = KidAccent,
                topLeft = Offset(0f, top),
                size = Size(played, barHeight),
                cornerRadius = radius,
            )
        }

        val knobRadius = size.height * 0.34f
        val knobX = played.coerceIn(knobRadius, (size.width - knobRadius).coerceAtLeast(knobRadius))
        drawCircle(
            color = Color.White,
            radius = knobRadius,
            center = Offset(knobX, size.height / 2f),
        )
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
