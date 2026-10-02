package tv.safetubeforkids.app.ui.player

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
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
import tv.safetubeforkids.app.playback.AspectChoices
import tv.safetubeforkids.app.playback.PlaybackController
import tv.safetubeforkids.app.playback.PlaybackKeys
import tv.safetubeforkids.app.playback.PlayerMenu
import tv.safetubeforkids.app.playback.PlayerOption
import tv.safetubeforkids.app.ui.theme.KidAccent
import tv.safetubeforkids.app.ui.theme.KidFocusRing
import tv.safetubeforkids.app.ui.theme.KidText
import tv.safetubeforkids.app.ui.theme.KidTextDim
import tv.safetubeforkids.app.ui.theme.StatusError
import tv.safetubeforkids.app.ui.theme.StatusWarning
import tv.safetubeforkids.app.util.AppLogger

private const val CONTROLS_TIMEOUT_MS = 4_000L
private const val SEEK_STEP_MS = 10_000L
private const val TICK_MS = 300L

/**
 * The transport controls, in the order the D-pad walks them.
 *
 * One list rather than five literals: the order is the contract - LEFT/RIGHT traverses them in this order
 * and the harness reads these labels off the television - so a test pins it instead of a reader. "Play"
 * becomes "Pause" while the video is playing.
 */
internal val TRANSPORT_CONTROL_LABELS = listOf(
    "Previous",
    "Rewind 10 seconds",
    "Play",
    "Forward 10 seconds",
    "Next",
)

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
    controller: PlaybackController,
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
    var buttonRowActive by remember { mutableStateOf(false) }
    var buttonIndex by remember { mutableIntStateOf(0) }
    var optionIndex by remember { mutableIntStateOf(0) }

    /**
     * Whether a transport control holds the focus.
     *
     * W13.2 made the five controls real focus targets. Two things depend on knowing when one of them has
     * the remote: the screen must stop treating LEFT/RIGHT as seeking (or two mechanisms fight over the
     * key), and the overlay must stop auto-hiding - hiding it removes the focused control from the
     * composition, and the focus falls back to the video surface, which is what made the row lose the
     * remote a few seconds after it was reached.
     */
    var transportFocused by remember { mutableStateOf(false) }

    /** "The child asked for the transport row" - satisfied by the effect below, once the row exists. */
    var wantsTransportFocus by remember { mutableStateOf(false) }

    val surfaceFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    /**
     * One requester per transport control, in [TRANSPORT_CONTROL_LABELS] order.
     *
     * They are linked to each other by `focusProperties` rather than merely requested: with explicit
     * neighbours, the horizontal walk inside the row never consults the geometric search - and the
     * geometric search is what handed LEFT/RIGHT to the full-screen surface, a focus target whose bounds
     * contain every control, so it lies "to the right" of all of them.
     */
    val transportRequesters = remember { List(TRANSPORT_CONTROL_LABELS.size) { FocusRequester() } }

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

    // Auto-hide, but never while paused, buffering, in error, showing the time-limit warning, or - since
    // W13.2 - while a transport control holds the focus. The last of those is not cosmetic: hiding the
    // overlay removes the focused control from the composition, so the focus falls back to the video
    // surface and the row loses the remote a few seconds after the child reached it.
    LaunchedEffect(
        controlsVisible, lastInteraction, isPlaying, isBuffering, errorMessage, warningText, transportFocused,
    ) {
        if (!controlsVisible || !isPlaying || isBuffering) return@LaunchedEffect
        if (errorMessage != null || warningText != null) return@LaunchedEffect
        if (transportFocused) return@LaunchedEffect
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

    // Asks for the row from a composition effect, not from the key handler: the handler runs before the
    // row has been attached, and a request made then is dropped without an error. It keeps asking until
    // the row reports the focus.
    LaunchedEffect(wantsTransportFocus, controlsVisible, buttonRowActive) {
        // One-shot, and never while the settings row is active: the flag is cleared wherever the row is
        // left, so a stale request cannot pull focus into the row on the next reveal. Left stale it did:
        // DOWN from the video landed on Previous instead of opening the settings row, and the speed menu
        // then never opened because RIGHT and OK went to the transport controls.
        if (!wantsTransportFocus || buttonRowActive) return@LaunchedEffect
        runCatching { transportRequesters.first().requestFocus() }
    }

    // A menu does not always open from the settings row - the resume prompt is raised by the
    // controller - so the highlight must follow whichever menu is open. Left stale, it pointed OK
    // at a row that does not exist in a two-option prompt, and pressing OK did nothing at all.
    LaunchedEffect(controller.activeMenu) {
        if (controller.activeMenu != null) {
            optionIndex = controller.menuOptions.indexOfFirst { it.selected }.coerceAtLeast(0)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(surfaceFocus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                lastInteraction = System.currentTimeMillis()
                // Remembered so that a second UP can reach the settings row: the first press reveals
                // the controls, the next focuses the buttons.
                val controlsWereVisible = controlsVisible
                controlsVisible = true

                val keyCode = event.nativeKeyEvent.keyCode

                // An open menu owns the remote: UP/DOWN move, OK selects, BACK just closes it.
                // An open menu owns the D-pad; transport keys must keep working underneath.
                if (controller.activeMenu != null) {
                    val consumed = when (keyCode) {
                        KeyEvent.KEYCODE_BACK -> {
                            controller.closeMenu()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            optionIndex = (optionIndex - 1).coerceAtLeast(0)
                            controller.noteMenuInteraction()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            optionIndex = (optionIndex + 1).coerceAtMost(controller.menuOptions.size - 1)
                            controller.noteMenuInteraction()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
                        -> {
                            controller.menuOptions.getOrNull(optionIndex)?.let { option ->
                                controller.selectMenuOption(option.id)
                            }
                            true
                        }
                        else -> false
                    }
                    if (consumed) return@onKeyEvent true
                }

                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    onBack()
                    return@onKeyEvent true
                }
                // Let the error actions own D-pad movement and clicks.
                if (errorMessage != null) return@onKeyEvent false

                // The settings row owns the D-pad while it is active, but only the D-pad:
                // play/pause, next, previous and seeking must stay live.
                if (buttonRowActive) {
                    val consumed = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            buttonRowActive = false
                            wantsTransportFocus = false
                            true
                        }
                        // DOWN from the settings row hands the remote to the transport row, which is the
                        // row *below* it on screen. The settings row stays the first stop, because the
                        // harness and a child both expect DOWN to open the menus.
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            buttonRowActive = false
                            transportFocused = true
                            wantsTransportFocus = true
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            buttonIndex = (buttonIndex - 1).coerceAtLeast(0)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            buttonIndex = (buttonIndex + 1).coerceAtMost(menuButtons.size - 1)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
                        -> {
                            controller.openMenu(menuButtons[buttonIndex])
                            // Open on the current choice so the child sees where they are.
                            optionIndex = controller.menuOptions
                                .indexOfFirst { it.selected }
                                .coerceAtLeast(0)
                            true
                        }
                        else -> false
                    }
                    if (consumed) return@onKeyEvent true
                }

                // The row owns the D-pad while a transport control has focus. UP and DOWN leave it and
                // never re-open the settings row, so entering and leaving stay unambiguous; LEFT and
                // RIGHT are returned *unconsumed* on purpose, so the focus graph - with the explicit
                // neighbours above - moves between the five controls instead of the seek handler seeing
                // them.
                if (transportFocused) {
                    val consumed = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                            transportFocused = false
                            buttonRowActive = false
                            // The request is a one-shot: leaving the row must drop it, or it re-fires on
                            // the next reveal and pulls focus back into the row.
                            wantsTransportFocus = false
                            runCatching { surfaceFocus.requestFocus() }
                            true
                        }
                        // The focused control activates itself on key *up*, so the surface must not act on
                        // OK as well: the key-down phase used to bubble up here, where OK also means
                        // play/pause, and one press did both. Pressing Pause paused and instantly resumed,
                        // and every other control toggled playback on top of its own action (the app log
                        // showed TogglePause twice around a single press). Consuming OK here - the one
                        // place all five controls pass through - leaves the press to the control alone.
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
                        -> true
                        else -> false
                    }
                    if (consumed) return@onKeyEvent true
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        return@onKeyEvent false
                    }
                }

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
                        val step = seekStepFor(event.nativeKeyEvent.repeatCount)
                        onSeekBy(-step)
                        seekFeedback = "<< ${step / 1000}s"
                        true
                    }
                    PlaybackKeys.Action.SeekForward -> {
                        val step = seekStepFor(event.nativeKeyEvent.repeatCount)
                        onSeekBy(step)
                        seekFeedback = "${step / 1000}s >>"
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
                    PlaybackKeys.Action.RevealControls -> {
                        // DOWN always hands the remote to the settings row. UP does too, but only when
                        // the controls were already on screen, so the first press reveals them and the
                        // second focuses the buttons - a child should not have to press DOWN to reach a
                        // button they can already see.
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                            (keyCode == KeyEvent.KEYCODE_DPAD_UP && controlsWereVisible)
                        ) {
                            buttonRowActive = true
                            buttonIndex = 0
                            // The settings row is taking the remote, so any pending transport request is
                            // void. The transport row is reached from this row's DOWN, never from here.
                            wantsTransportFocus = false
                        }
                        true
                    }
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
                // A soft disc keeps the spinner legible over bright video.
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = KidAccent, strokeWidth = 5.dp)
                }
            }
        }

        seekFeedback?.let { badge ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(KidAccent, CircleShape)
                        .padding(horizontal = 34.dp, vertical = 16.dp),
                )
            }
        }

        if (controlsVisible && errorMessage == null) {
            TopBar(
                title = title,
                queueLabel = queueLabel,
                modifier = Modifier.align(Alignment.TopStart),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
            ) {
                MenuBar(
                    controller = controller,
                    active = buttonRowActive,
                    selectedIndex = buttonIndex,
                )
                BottomBar(
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    transportRequesters = transportRequesters,
                    onPrevious = onPreviousApproved,
                    // The buttons ask for the same 10s step the arrow keys use on a single press; the
                    // hold-to-accelerate ladder belongs to a repeating key, which a click is not.
                    onRewind = { onSeekBy(-SEEK_STEP_MS); seekFeedback = "<< ${SEEK_STEP_MS / 1000}s" },
                    onTogglePlayPause = onTogglePlayPause,
                    onForward = { onSeekBy(SEEK_STEP_MS); seekFeedback = "${SEEK_STEP_MS / 1000}s >>" },
                    onNext = onNextApproved,
                    onTransportFocusChange = { focused ->
                        transportFocused = focused
                        if (focused) wantsTransportFocus = false
                    },
                )
            }
        }

        controller.activeMenu?.let { menu ->
            PlayerMenuOverlay(
                title = controller.menuTitle,
                options = controller.menuOptions,
                selectedIndex = optionIndex,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 48.dp),
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
    // A gradient scrim instead of a solid slab: the video stays the focus, the title stays legible.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                ),
            )
            .padding(start = 40.dp, end = 40.dp, top = 28.dp, bottom = 48.dp),
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
                style = MaterialTheme.typography.labelLarge,
                color = KidText,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.16f), CircleShape)
                    .padding(horizontal = 20.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun BottomBar(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    transportRequesters: List<FocusRequester>,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    onTransportFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One rounded deck instead of a full-width slab, so the video keeps its edges. Kept shallow
    // on purpose: a taller deck climbs into the middle of the screen and collides with the menus.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 10.dp)
            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(20.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        SeekBar(positionMs = positionMs, durationMs = durationMs)
        Spacer(Modifier.height(10.dp))
        TransportRow(
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            requesters = transportRequesters,
            onPrevious = onPrevious,
            onRewind = onRewind,
            onTogglePlayPause = onTogglePlayPause,
            onForward = onForward,
            onNext = onNext,
            onFocusChange = onTransportFocusChange,
        )
    }
}

/**
 * The transport controls: Previous, Rewind, Play/Pause, Forward, Next - then the times.
 *
 * W13.2 turned these from pictures of controls into controls. Before it, the play/pause disc and the
 * rewind/forward icons were plain `Icon`s with a tint: they looked like buttons and could not be focused
 * or pressed, so a child holding a remote with no media keys - which is the remote this product actually
 * runs on - saw five things that did nothing and had to discover that the arrow keys are the transport.
 *
 * **Why the neighbours are written out.** Making them focusable was not enough. Compose's directional
 * search is geometric, and the player's full-screen surface is itself a focus target whose bounds contain
 * every control, so it lies in every direction from all of them; the first LEFT/RIGHT press from
 * Previous therefore moved focus to the *surface* instead of to Rewind. Each control now names its own
 * left and right neighbour and the two ends name themselves, which closes the row into a loop the surface
 * cannot win. UP and DOWN are left to the screen's key handler, which consumes them to leave the row.
 *
 * These are not a second playback implementation: every control calls the same callback the remote's keys
 * call, and those go to `PlaybackController`, where playback authorization, the approved queue and
 * watch-time accounting live. A button here can do nothing the remote could not already do.
 */
@Composable
private fun TransportRow(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    requesters: List<FocusRequester>,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    var focusedIndex by remember { mutableIntStateOf(-1) }

    // Reported from the row rather than from each control, so "the transport has focus" is one decision
    // instead of five racing ones: a control losing focus to its neighbour must not read as leaving.
    LaunchedEffect(focusedIndex) {
        onFocusChange(focusedIndex >= 0)
    }

    Row(
        // One focus group, so returning to the row restores the control that was left.
        modifier = Modifier
            .focusGroup()
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TRANSPORT_CONTROL_LABELS.forEachIndexed { index, label ->
            if (index > 0) Spacer(Modifier.width(10.dp))
            val primary = index == 2
            TransportButton(
                description = if (primary && isPlaying) "Pause" else label,
                icon = when (index) {
                    0 -> Icons.Rounded.SkipPrevious
                    1 -> Icons.Rounded.Replay10
                    2 -> if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
                    3 -> Icons.Rounded.Forward10
                    else -> Icons.Rounded.SkipNext
                },
                primary = primary,
                focusRequester = requesters[index],
                leftRequester = requesters[if (index == 0) 0 else index - 1],
                rightRequester = requesters[if (index == TRANSPORT_CONTROL_LABELS.lastIndex) {
                    TRANSPORT_CONTROL_LABELS.lastIndex
                } else {
                    index + 1
                }],
                onFocused = { focused ->
                    focusedIndex = if (focused) index else if (focusedIndex == index) -1 else focusedIndex
                },
                onClick = when (index) {
                    0 -> onPrevious
                    1 -> onRewind
                    2 -> onTogglePlayPause
                    3 -> onForward
                    else -> onNext
                },
            )
        }
        Spacer(Modifier.width(22.dp))
        Text(
            text = formatTime(positionMs),
            style = MaterialTheme.typography.titleMedium,
            color = KidText,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = formatTime(durationMs),
            style = MaterialTheme.typography.titleMedium,
            color = KidTextDim,
        )
    }
}

/**
 * One transport control: focusable, visibly focused, and activated by CENTER/ENTER.
 *
 * The focused state is a filled disc *and* a ring, the pair the library's cards use, because colour alone
 * is not a focus indicator across a living room. Activation is on key *up* for CENTER, as the cards do, so
 * a held key does not fire twice.
 */
@Composable
private fun TransportButton(
    description: String,
    icon: ImageVector,
    primary: Boolean,
    focusRequester: FocusRequester,
    leftRequester: FocusRequester,
    rightRequester: FocusRequester,
    onClick: () -> Unit,
    onFocused: (Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val size = if (primary) 56.dp else 48.dp
    val iconSize = if (primary) 30.dp else 26.dp

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    primary -> KidAccent
                    focused -> KidAccent.copy(alpha = 0.30f)
                    else -> Color.White.copy(alpha = 0.12f)
                }
            )
            .then(if (focused) Modifier.border(3.dp, KidFocusRing, CircleShape) else Modifier)
            .focusRequester(focusRequester)
            .focusProperties {
                left = leftRequester
                right = rightRequester
            }
            .onFocusChanged {
                focused = it.isFocused
                onFocused(it.isFocused)
            }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.DirectionCenter) {
                    onClick()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (primary) Color.Black else KidText,
            modifier = Modifier.size(iconSize),
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
            .height(20.dp),
    ) {
        val barHeight = size.height * 0.32f
        val top = (size.height - barHeight) / 2f
        val radius = CornerRadius(barHeight / 2f, barHeight / 2f)

        drawRoundRect(
            color = Color.White.copy(alpha = 0.26f),
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

        val knobRadius = size.height * 0.30f
        val knobX = played.coerceIn(knobRadius, (size.width - knobRadius).coerceAtLeast(knobRadius))
        drawCircle(
            color = Color.White,
            radius = knobRadius,
            center = Offset(knobX, size.height / 2f),
        )
    }
}

private val menuButtons = listOf(
    PlayerMenu.CAPTIONS,
    PlayerMenu.QUALITY,
    PlayerMenu.AUDIO,
    PlayerMenu.SPEED,
    PlayerMenu.ASPECT,
)

private fun labelFor(menu: PlayerMenu, controller: PlaybackController): String = when (menu) {
    // Not in the settings row: the resume prompt is shown automatically when relevant.
    PlayerMenu.RESUME -> "Continue"
    PlayerMenu.CAPTIONS -> "Subtitles: ${controller.captionsLabel}"
    PlayerMenu.QUALITY -> "Quality: ${controller.qualityLabel}"
    PlayerMenu.AUDIO -> "Audio: ${controller.audioLabel}"
    PlayerMenu.SPEED -> "Speed: ${controller.speed}x"
    PlayerMenu.ASPECT -> when (controller.aspectId) {
        AspectChoices.ZOOM -> "Fit: Crop"
        AspectChoices.FILL -> "Fit: Stretch"
        else -> "Fit: Fit"
    }
}

@Composable
private fun MenuBar(controller: PlaybackController, active: Boolean, selectedIndex: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        menuButtons.forEachIndexed { index, menu ->
            val highlighted = active && index == selectedIndex
            Text(
                text = labelFor(menu, controller),
                style = MaterialTheme.typography.titleMedium,
                color = if (highlighted) Color.Black else KidText,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .background(
                        color = if (highlighted) KidAccent else Color.Black.copy(alpha = 0.55f),
                        shape = CircleShape,
                    )
                    .border(
                        width = 1.dp,
                        color = if (highlighted) Color.Transparent else Color.White.copy(alpha = 0.16f),
                        shape = CircleShape,
                    )
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PlayerMenuOverlay(
    title: String,
    options: List<PlayerOption>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(460.dp)
            .background(Color.Black.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .padding(vertical = 20.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = KidText,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(6.dp))
        options.forEachIndexed { index, option ->
            val highlighted = index == selectedIndex
            Text(
                text = if (option.selected) "\u2713  ${option.label}" else "     ${option.label}",
                style = MaterialTheme.typography.titleMedium,
                color = if (highlighted) Color.Black else KidText,
                fontWeight = if (option.selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (highlighted) KidAccent else Color.Transparent)
                    .padding(horizontal = 26.dp, vertical = 14.dp),
            )
        }
    }
}

/**
 * Hold-to-accelerate seeking: one press moves 10s, and a held key escalates through 20s, 30s,
 * 60s and 120s as the remote repeats, so long videos are not a key-mashing exercise.
 */
internal fun seekStepFor(repeatCount: Int): Long = when {
    repeatCount >= 12 -> 120_000L
    repeatCount >= 8 -> 60_000L
    repeatCount >= 4 -> 30_000L
    repeatCount >= 2 -> 20_000L
    else -> 10_000L
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
