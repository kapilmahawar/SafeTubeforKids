package tv.safetubeforkids.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tv.safetubeforkids.app.debug.DebugReceiver
import tv.safetubeforkids.app.ui.screens.CatalogContainerScreen
import tv.safetubeforkids.app.ui.screens.ConnectScreen
import tv.safetubeforkids.app.ui.screens.HomeScreen
import tv.safetubeforkids.app.ui.screens.LockScreen
import tv.safetubeforkids.app.ui.screens.PlaybackScreen
import tv.safetubeforkids.app.ui.screens.SettingsScreen

object Routes {
    const val CONNECT = "connect"
    const val HOME = "home"

    /**
     * One open sub-category.
     *
     * A container is a destination of its own rather than a mode of the home screen, so the navigation
     * back stack *is* the hierarchy: entering pushes, Back pops, and a container inside a container
     * (if a catalog ever holds one) nests the same way without any extra bookkeeping.
     */
    const val CONTAINER = "container/{containerId}"
    const val PLAYBACK = "playback/{videoId}/{playlistId}/{startIndex}"
    const val SETTINGS = "settings"
    const val LOCK = "lock/{reason}"

    fun container(containerId: String) = "container/$containerId"
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

    /**
     * Opens the player over whatever the child was browsing.
     *
     * The entry *below* the player is the screen the video came from, so Back from the player returns to
     * that shelf or container rather than jumping to the top. From the home screen a stale player left
     * on the stack is dropped first, which is what keeps exactly one player alive; from a container the
     * player is simply pushed, because popping to HOME there would delete the container the child is
     * standing in - and then Back would land somewhere they never were.
     */
    val openPlayer: (String, String, Int) -> Unit = { videoId, playlistId, startIndex ->
        val fromHome = navController.currentBackStackEntry?.destination?.route == Routes.HOME
        navController.navigate(Routes.playback(videoId, playlistId, startIndex)) {
            launchSingleTop = true
            if (fromHome) popUpTo(Routes.HOME) { inclusive = false }
        }
    }

    val openContainer: (String) -> Unit = { containerId ->
        navController.navigate(Routes.container(containerId)) {
            // Entering the same container twice (a double press of Enter) must not stack two copies of
            // it, and nothing is popped: the shelf stays underneath so Back returns to it.
            launchSingleTop = true
        }
    }

    // Wire the debug play/stop intents (debug builds only - DebugReceiver ignores them in
    // release). Without this the DEBUG_PLAY_VIDEO broadcast reported success but did nothing,
    // which made automated device testing far harder than it needed to be.
    DisposableEffect(navController) {
        DebugReceiver.onPlayVideo = { videoId, playlistId ->
            // Keep exactly one player entry: a stacked player would stay alive behind the new
            // one, keep playing and keep writing the shared now-playing state. The debug entry point
            // is a parent/tool action rather than a child's, so it always starts from the library.
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
            // Ungated on purpose: this is where a parent reads the current PIN and QR code, and the
            // PIN is regenerated on every app start, so gating it would strand a parent who cannot
            // recall the code. Small children are unlikely to open a web dashboard, and the TV app
            // offers no URL or search entry, so the PIN being visible is an accepted trade-off.
            ConnectScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.HOME) {
            HomeScreen(
                onPlayVideo = { videoId, playlistId, videoIndex ->
                    openPlayer(videoId, playlistId, videoIndex)
                },
                onOpenContainer = openContainer,
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
            Routes.CONTAINER,
            arguments = listOf(navArgument("containerId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val containerId = backStackEntry.arguments?.getString("containerId") ?: return@composable
            CatalogContainerScreen(
                containerId = containerId,
                onPlayVideo = { videoId, playlistId, videoIndex ->
                    openPlayer(videoId, playlistId, videoIndex)
                },
                onOpenContainer = openContainer,
                // Remote Back pops this entry; this button is the same destination, for a parent using
                // the on-screen controls.
                onBack = { navController.popBackStack() },
                onSettings = { navController.navigate(Routes.SETTINGS) },
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
            // Ungated by product decision, after weighing it against a PIN gate. The PIN is shown on
            // the Connect Phone screen anyway (it rotates on every app start, so it has to be
            // retrievable), and the child-facing app has no URL entry, no search and no way to
            // approve content - so the reachable surface here cannot add unapproved videos. Read
            // HANDOVER before adding a gate: one was tried and it locked parents out of their own TV.
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
