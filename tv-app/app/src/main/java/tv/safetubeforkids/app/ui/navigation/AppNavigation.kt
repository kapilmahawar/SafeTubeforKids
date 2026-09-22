package tv.safetubeforkids.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tv.safetubeforkids.app.debug.DebugReceiver
import tv.safetubeforkids.app.ui.screens.ConnectScreen
import tv.safetubeforkids.app.ui.screens.HomeScreen
import tv.safetubeforkids.app.ui.screens.LockScreen
import tv.safetubeforkids.app.ui.screens.PlaybackScreen
import tv.safetubeforkids.app.ui.screens.SettingsScreen

object Routes {
    const val CONNECT = "connect"
    const val HOME = "home"
    const val PLAYBACK = "playback/{videoId}/{playlistId}/{startIndex}"
    const val SETTINGS = "settings"
    const val LOCK = "lock/{reason}"

    fun playback(videoId: String, playlistId: String, startIndex: Int) = "playback/$videoId/$playlistId/$startIndex"
    fun lock(reason: String) = "lock/${reason.lowercase()}"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    val onLocked: (String) -> Unit = { reason ->
        navController.navigate(Routes.lock(reason)) {
            popUpTo(Routes.HOME) { inclusive = false }
        }
    }

    // Wire the debug play/stop intents (debug builds only - DebugReceiver ignores them in
    // release). Without this the DEBUG_PLAY_VIDEO broadcast reported success but did nothing,
    // which made automated device testing far harder than it needed to be.
    DisposableEffect(navController) {
        DebugReceiver.onPlayVideo = { videoId, playlistId ->
            // Keep exactly one player entry: a stacked player would stay alive behind the new
            // one, keep playing and keep writing the shared now-playing state.
            navController.navigate(Routes.playback(videoId, playlistId.ifBlank { videoId }, 0)) {
                launchSingleTop = true
                popUpTo(Routes.HOME) { inclusive = false }
            }
        }
        DebugReceiver.onStopPlayback = {
            navController.popBackStack(Routes.HOME, inclusive = false)
        }
        onDispose {
            DebugReceiver.onPlayVideo = null
            DebugReceiver.onStopPlayback = null
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.CONNECT) {
            ConnectScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.HOME) {
            HomeScreen(
                onPlayVideo = { videoId, playlistId, videoIndex ->
                    navController.navigate(Routes.playback(videoId, playlistId, videoIndex)) {
                        launchSingleTop = true
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
                },
                onSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                onConnect = {
                    navController.navigate(Routes.CONNECT)
                },
                onLocked = onLocked,
            )
        }

        composable(
            Routes.PLAYBACK,
            arguments = listOf(
                navArgument("videoId") { type = NavType.StringType },
                navArgument("playlistId") { type = NavType.StringType },
                navArgument("startIndex") { type = NavType.IntType },
            )
        ) { backStackEntry ->
            val videoId = backStackEntry.arguments?.getString("videoId") ?: return@composable
            val playlistId = backStackEntry.arguments?.getString("playlistId") ?: return@composable
            val startIndex = backStackEntry.arguments?.getInt("startIndex") ?: 0
            PlaybackScreen(
                videoId = videoId,
                playlistId = playlistId,
                startIndex = startIndex,
                onBack = { navController.popBackStack() },
                onLocked = onLocked,
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onRefresh = {
                    navController.popBackStack()
                },
            )
        }

        composable(
            Routes.LOCK,
            arguments = listOf(
                navArgument("reason") { type = NavType.StringType },
            )
        ) { backStackEntry ->
            val reason = backStackEntry.arguments?.getString("reason") ?: "manual_lock"
            LockScreen(
                reason = reason,
                onUnlocked = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }
    }
}
