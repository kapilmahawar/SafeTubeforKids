package tv.safetubeforkids.app.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import tv.safetubeforkids.app.util.AppLogger

/**
 * Keeps the parent dashboard reachable while the TV app's UI is closed.
 *
 * The Ktor server used to live in MainActivity and was stopped in onDestroy, so closing the app
 * took the dashboard offline with it - even though the dashboard is exactly what a parent reaches
 * for when the child is not using the TV. A foreground service owns the server instead, which is
 * also what stops Android from reclaiming the process.
 */
class ServerService : Service() {

    companion object {
        private const val CHANNEL_ID = "safetube_server"
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Must happen within a few seconds of startForegroundService(), or Android kills us.
        startForeground(NOTIFICATION_ID, buildNotification())
        Thread {
            ServerHolder.start(this)
            AppLogger.log("Dashboard server is being kept alive in the background")
        }.start()
    }

    /** Sticky, so the dashboard comes back if Android reclaims the process. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        // Leve the server running: the activity may still be alive, and ServerHolder owns it.
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Parent dashboard",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Keeps the parent dashboard reachable while the TV app is closed"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("SafeTube for Kids")
            .setContentText("Parent dashboard is available on your network")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
