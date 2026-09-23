package tv.safetubeforkids.app.util

import android.content.Context
import android.content.SharedPreferences
import tv.safetubeforkids.app.BuildConfig

/**
 * Debug-only switch that makes the catalog sync fail as if the parent server were gone.
 *
 * Phase 4's central runtime promise is that the TV runs from its local Room catalog, so a server
 * outage must leave the child's screen untouched. That is hard to demonstrate on this product,
 * because the SafeTube server runs *inside* the app: stopping it stops the app too, and there is no
 * way to unplug one without the other from outside.
 *
 * So the outage is simulated at the transport instead, and - because the scenario that matters is
 * "force-stop the app, relaunch it, the catalog is still there" - the switch is persisted. Release
 * builds ignore it completely: [init] does nothing when `IS_DEBUG` is false, so the property stays
 * false on any build a family installs.
 *
 * This is test scaffolding for real-hardware verification, not a product feature. It affects only
 * the catalog sync's own HTTP call; it cannot influence playback authorization, which never
 * consults it.
 */
object CatalogSyncDebug {

    private const val PREFS = "parentapproved_catalog_debug"
    private const val KEY_FORCE_UNAVAILABLE = "force_server_unavailable"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (!BuildConfig.IS_DEBUG) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** True only in a debug build with the switch turned on. */
    val forceServerUnavailable: Boolean
        get() = prefs?.getBoolean(KEY_FORCE_UNAVAILABLE, false) ?: false

    fun setForceServerUnavailable(unavailable: Boolean) {
        prefs?.edit()?.putBoolean(KEY_FORCE_UNAVAILABLE, unavailable)?.apply()
        AppLogger.log("Catalog sync debug: server unavailable = $unavailable")
    }
}
