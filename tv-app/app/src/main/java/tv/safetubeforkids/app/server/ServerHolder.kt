package tv.safetubeforkids.app.server

import android.content.Context
import tv.safetubeforkids.app.util.AppLogger

/**
 * The one place the dashboard server is started.
 *
 * It used to be started by MainActivity and then by ServerService, and a moment came when neither
 * actually did - the dashboard was silently offline while the app looked perfectly healthy. Going
 * through a single idempotent holder means any starter works, a second caller is a harmless
 * no-op instead of a second bind on port 8080, and "is the dashboard up?" has one answer.
 */
object ServerHolder {

    @Volatile
    private var server: SafeTubeServer? = null

    @Synchronized
    fun start(context: Context) {
        if (server?.isRunning == true) return
        server = SafeTubeServer(context.applicationContext).also { it.start() }
        AppLogger.log("Dashboard server started (port 8080)")
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    fun isRunning(): Boolean = server?.isRunning == true
}
