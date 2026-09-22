package tv.safetubeforkids.app.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tv.safetubeforkids.app.util.AppLogger

/**
 * Brings the parent dashboard back without anyone opening the app.
 *
 * The dashboard is what a parent reaches for while the child is not using the TV, so it cannot
 * depend on somebody having opened the app since the TV last restarted. Android allows a
 * BOOT_COMPLETED receiver to start a foreground service, which [ServerService] is, and that service
 * is what keeps port 8080 answering while the TV UI is closed.
 *
 * A package update is handled the same way, because replacing the APK kills the process that was
 * serving. [tv.safetubeforkids.app.SafeTubeApp] has already initialised the service locator by the
 * time this runs, so the server can answer as soon as it is started.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ServerService.start(context)
                AppLogger.log("${intent.action}: dashboard service started")
            }
        }
    }
}
