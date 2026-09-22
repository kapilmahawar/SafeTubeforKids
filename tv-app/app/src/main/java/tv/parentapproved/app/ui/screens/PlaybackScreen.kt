package tv.parentapproved.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import tv.parentapproved.app.ServiceLocator
import tv.parentapproved.app.playback.PlaybackCommand
import tv.parentapproved.app.playback.PlaybackCommandBus
import tv.parentapproved.app.playback.PlaybackController
import tv.parentapproved.app.ui.player.TvPlayerScreen

/**
 * Thin host for the TV player: it builds the controller, forwards remote-control commands from
 * the dashboard/API, and renders the player. All authority, queue and media decisions live in
 * [PlaybackController].
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlaybackScreen(
    videoId: String,
    playlistId: String,
    startIndex: Int,
    onBack: () -> Unit,
    onLocked: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { ServiceLocator.database }

    val controller = remember {
        PlaybackController(
            context = context.applicationContext,
            db = db,
            scope = scope,
            onExit = onBack,
            onLocked = onLocked,
        )
    }

    LaunchedEffect(videoId, playlistId) {
        controller.start(videoId, playlistId, startIndex)
    }

    LaunchedEffect(controller) {
        PlaybackCommandBus.commands.collect { command ->
            when (command) {
                PlaybackCommand.Stop -> controller.stop()
                PlaybackCommand.SkipNext -> controller.next()
                PlaybackCommand.SkipPrev -> controller.previous()
                PlaybackCommand.TogglePause -> controller.togglePause()
            }
        }
    }

    DisposableEffect(controller) {
        onDispose { controller.release() }
    }

    TvPlayerScreen(
        player = controller.player,
        title = controller.title,
        queueLabel = controller.queueLabel,
        errorMessage = controller.errorMessage,
        warningText = controller.warningText,
        resizeMode = controller.resizeMode,
        onRetry = controller::retry,
        onBack = onBack,
        onTogglePlayPause = controller::togglePause,
        onSeekBy = controller::seekBy,
        onNextApproved = controller::next,
        onPreviousApproved = controller::previous,
    )
}
