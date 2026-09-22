package tv.safetubeforkids.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tv.safetubeforkids.app.data.events.PlayEventRecorder
import tv.safetubeforkids.app.kiosk.HomeWatcherService
import tv.safetubeforkids.app.server.ServerService
import tv.safetubeforkids.app.server.SafeTubeServer
import tv.safetubeforkids.app.ui.navigation.AppNavigation
import tv.safetubeforkids.app.ui.theme.SafeTubeTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var server: SafeTubeServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start Ktor server on background thread to avoid ANR on slow devices
        Thread {
            server = SafeTubeServer(this).also { it.start() }
        }.start()

        setContent {
            SafeTubeTheme {
                AppNavigation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enterLockTaskIfEnabled()
        startHomeWatcherIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // HOME button pressed — singleTask redelivers intent here.
        // The NavController stays on whatever screen the user was on.
        Log.d(TAG, "onNewIntent: ${intent.action}")
    }

    override fun onStop() {
        super.onStop()
        PlayEventRecorder.flushCurrentEvent()
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }

    fun exitLockTaskIfNeeded() {
        if (ServiceLocator.kioskManager.isInLockTaskMode()) {
            stopLockTask()
            Log.i(TAG, "Exited lock task mode")
        }
    }

    private fun startHomeWatcherIfNeeded() {
        if (!ServiceLocator.isInitialized()) return
        lifecycleScope.launch {
            val config = ServiceLocator.database.kioskDao().getConfig()
            if (config?.kioskEnabled == true) {
                HomeWatcherService.start(this@MainActivity)
            }
        }
    }

    private fun enterLockTaskIfEnabled() {
        if (!ServiceLocator.isInitialized()) return
        val kiosk = ServiceLocator.kioskManager
        if (!kiosk.isDeviceOwner()) return

        lifecycleScope.launch {
            val config = ServiceLocator.database.kioskDao().getConfig()
            if (config?.kioskEnabled == true && kiosk.isLockTaskPermitted()) {
                if (!kiosk.isInLockTaskMode()) {
                    startLockTask()
                    Log.i(TAG, "Entered lock task mode")
                }
            }
        }
    }
}
