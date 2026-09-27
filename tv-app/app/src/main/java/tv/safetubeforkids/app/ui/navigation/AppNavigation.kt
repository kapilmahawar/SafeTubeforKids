package tv.safetubeforkids.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.debug.DebugReceiver
import tv.safetubeforkids.app.ui.screens.CatalogContainerScreen
import tv.safetubeforkids.app.ui.screens.ConnectScreen
import tv.safetubeforkids.app.ui.screens.HomeScreen
import tv.safetubeforkids.app.ui.screens.LockScreen
import tv.safetubeforkids.app.ui.screens.OnboardingScreen
import tv.safetubeforkids.app.ui.screens.PlaybackScreen
import tv.safetubeforkids.app.ui.screens.RecoveryCodeScreen
import tv.safetubeforkids.app.ui.screens.ResetSafeTubeScreen
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

    /** W10: first-run setup, and the two parent-access screens reachable from Settings. */
    const val ONBOARDING = "onboarding"
    const val RECOVERY = "recovery"
    const val RESET = "reset"

    fun container(containerId: String) = "container/$containerId"
    fun playback(videoId: String, playlistId: String, startIndex: Int) = "playback/$videoId/$playlistId/$startIndex"
    fun lock(reason: String) = "lock/${reason.lowercase()}"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    /**
     * Where a launch begins: the child's library, or first-run setup.
     *
     * Read once, at the start of the composition, which is what makes onboarding a genuine first-run
     * door rather than a screen that can be wandered back into. "Does a Parent PIN exist" is the one
     * persistent fact that decides it - an existing installation being upgraded has a library and
     * approved sources but no persistent credential yet, so it gets the one-time setup and keeps
     * everything else (see `ParentAccessTest.migration_*`).
     */
    val startDestination = if (ServiceLocator.pinManager.isConfigured()) Routes.HOME else Routes.ONBOARDING

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

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onFinished = {
                navController.navigate(Routes.HOME) {
                    // Onboarding is a one-time door: leaving it must not leave it on the back stack,
                    // or Back from the child's home screen would land the child in parent setup.
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            })
        }

        composable(Routes.RECOVERY) {
            RecoveryCodeScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.RESET) {
            ResetSafeTubeScreen(
                onBack = { navController.popBackStack() },
                // After a wipe this is a device with no Parent PIN, no library and no settings, so the
                // only honest place to be is the doorway a new install opens on - with the whole back
                // stack gone, because every entry in it belonged to the SafeTube that no longer exists.
                onReset = {
                    navController.navigate(Routes.ONBOARDING) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.CONNECT) {
            // Ungated on purpose: this is where a parent reads the dashboard address and the QR code,
            // and where they are told whether a Parent PIN exists. Nothing secret is on it since W10 -
            // the PIN is never displayed and the QR carries only the address - so the old trade-off
            // ("the PIN is visible, but the child cannot use a web page") no longer has to be made.
            ConnectScreen(
                onBack = { navController.popBackStack() },
                onParentAccess = { navController.navigate(Routes.RECOVERY) },
            )
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
            // Ungated by product decision, after weighing it against a PIN gate. The child-facing app
            // has no URL entry, no search and no way to approve content - so the reachable surface
            // here cannot add unapproved videos, and the two parent-access screens behind it ask for
            // the PIN (to issue a Recovery Code) or a typed phrase (to erase everything). Read
            // HANDOVER before adding a gate: one was tried and it locked parents out of their own TV.
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onRefresh = {
                    navController.popBackStack()
                },
                onParentAccess = { navController.navigate(Routes.RECOVERY) },
                onResetSafeTube = { navController.navigate(Routes.RESET) },
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
