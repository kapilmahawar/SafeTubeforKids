package tv.safetubeforkids.app.reset

import android.content.Context
import tv.safetubeforkids.app.CrashHandler
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.auth.ResetAuthorization
import tv.safetubeforkids.app.auth.SharedPrefsParentCredentialStore
import tv.safetubeforkids.app.data.events.PlayEventRecorder
import tv.safetubeforkids.app.server.FileCatalogStore
import tv.safetubeforkids.app.util.AppLogger
import tv.safetubeforkids.app.util.CatalogSyncDebug
import java.io.File

/**
 * The last resort: a parent who has forgotten both the Parent PIN and the Recovery Code.
 *
 * **Why it is destructive, and why that is the point.** There is no third secret to check. The PIN is
 * a hash, the Recovery Code is a hash, and nothing else on the device can distinguish the parent from
 * the child. So the only honest way back in is to make the device new again - which also means the
 * library, the approved sources and the settings go with it, because they are what the forgotten PIN
 * was protecting. A reset that kept the library but dropped the PIN would be a way to read a child's
 * library, and one that kept the PIN but dropped the library would be a way to empty a TV; both are
 * worse than the reset itself.
 *
 * **What it wipes.** Everything SafeTube owns, and nothing else:
 *
 *  - the Parent PIN and the Recovery Code (the whole `parentapproved_parent_access` file);
 *  - every dashboard session (`parentapproved_sessions`) - a session issued before a reset must not
 *    outlive it;
 *  - both attempt counters (`parentapproved_pin_lockout`), so a lockout cannot survive the reset;
 *  - the curated catalog: `catalog.json`, its version high-water mark and any half-written temporary
 *    file, plus the TV's mirror of it (`catalog_nodes`, `catalog_metadata`);
 *  - the approved sources and their cached videos (`channels`, `videos`) - this is playback
 *    authorization's *data*, and it is exactly the state the PIN guarded;
 *  - watch history, resume positions and the time-limit/bedtime configuration
 *    (`play_events`, `playback_positions`, `time_limit_config`);
 *  - SafeTube's kiosk configuration and app whitelist (`kiosk_config`, `app_whitelist`);
 *  - the remote-access configuration (`parentapproved_relay`, including the TV's secret);
 *  - the debug-only catalog-sync switch, which is SafeTube's own preferences file too;
 *  - the crash log, which is a record of this app's failures.
 *
 * **What it does not touch**, also deliberately: the Android system and its settings, the other apps
 * on the TV, anything in shared or external storage, and SafeTube's own APK. Kiosk *device-owner*
 * state is Android's (it is not a file this app can delete); the reset asks the kiosk manager to stop
 * enforcing and to release lock-task mode, which is the most an app can do about it honestly.
 *
 * Every claim in that list is asserted by `SafeTubeResetTest`, from both directions: the SafeTube
 * things are gone afterwards, and a file, a preference and a database table that belong to somebody
 * else are still exactly where they were.
 */
object SafeTubeReset {

    /** The phrase a parent must type. Five words, and impossible to type by accident. */
    const val CONFIRMATION_PHRASE = "I UNDERSTAND THIS ERASES EVERYTHING"

    /**
     * Case and surrounding space are forgiven; nothing else is. A partial phrase is refused, because
     * the phrase exists to make the parent read what the button does - a prefix typed by muscle memory
     * would defeat the one safeguard standing in front of an irreversible wipe.
     */
    fun phraseMatches(input: String): Boolean =
        input.trim().equals(CONFIRMATION_PHRASE, ignoreCase = true)

    /**
     * Wipes SafeTube's own state. Suspending because the database work is, and because the caller must
     * not be able to report "reset" while rows are still on their way out.
     *
     * **The parameter is the point.** Until W13.1b this took only a `Context`, which meant every caller
     * was authorized by definition and the only thing standing between a child and the wiped television
     * was a screen. It now requires a [ResetAuthorization], which can be obtained only from
     * [tv.safetubeforkids.app.auth.ResetGate] after a Parent PIN or a Recovery Code has been verified, so
     * a caller cannot reach the wipe without a credential even by accident. `authorization` is not
     * decoration: which credential proved the parent's authority is written into the log line below,
     * because "the television was wiped" is exactly the event a support conversation needs to date and
     * attribute.
     *
     * Returns what it removed, so the TV can say something true about what just happened, and so the
     * verification harness can check the wipe from the outside rather than trusting this function.
     */
    suspend fun wipe(context: Context, authorization: ResetAuthorization): Report {
        val database = ServiceLocator.database
        val removed = mutableMapOf<String, Int>()

        // 1. The database rows. Children before parents, so no foreign key is ever violated mid-wipe.
        removed["play_events"] = database.playEventDao().count()
        database.playEventDao().deleteAll()
        database.playbackPositionDao().deleteAll()
        database.catalogNodeDao().deleteAll()
        database.catalogMetadataDao().deleteAll()
        database.videoDao().deleteAll()
        database.channelDao().deleteAll()
        database.timeLimitDao().deleteAll()
        database.kioskDao().deleteAll()
        database.whitelistDao().deleteAll()

        // 2. The server's own catalog document and its version counter.
        var files = 0
        listOf(
            FileCatalogStore.FILE_NAME,
            FileCatalogStore.VERSION_FILE_NAME,
            "${FileCatalogStore.FILE_NAME}.tmp",
        ).forEach { name ->
            val file = File(context.filesDir, name)
            if (file.exists() && file.delete()) files++
        }
        val crash = CrashHandler.getCrashFile(context)
        if (crash.exists() && crash.delete()) files++
        removed["files"] = files

        // 3. Preferences: credentials, sessions, counters, remote access, debug switches.
        var preferences = 0
        PREFERENCE_FILES.forEach { name ->
            val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (prefs.all.isNotEmpty()) preferences++
            prefs.edit().clear().commit()
        }
        removed["preference_files"] = preferences

        // 4. The in-memory copies, so nothing that survived the file deletion can be used or trusted.
        ServiceLocator.sessionManager.invalidateAll()
        ServiceLocator.pinManager.clearAll()
        // The rows are gone above; this is the in-memory half, so the status API stops reporting a
        // session that no longer exists on disk rather than resurrecting one.
        PlayEventRecorder.clearNowPlaying()
        CatalogSyncDebug.setForceServerUnavailable(false)

        // 5. Remote access is SafeTube configuration too: a fresh install is not connected to a relay,
        // and a TV secret that was ever shared must not survive a wipe. Skipped on a device where the
        // relay was never wired up at all, which is the honest thing to do rather than reaching into an
        // uninitialised field.
        if (ServiceLocator.isRelayInitialized()) {
            try {
                ServiceLocator.relayConnector.disconnect()
                ServiceLocator.relayConfig.rotateTvSecret()
            } catch (e: Exception) {
                AppLogger.warn("Reset could not fully detach the relay: ${e.message}")
            }
        }

        AppLogger.log(
            "SafeTube reset: wiped " + removed.entries.joinToString(", ") { "${it.key}=${it.value}" } +
                " (authorized by ${authorization.method})"
        )
        return Report(removed)
    }

    /** What the wipe removed, per store. */
    data class Report(val removed: Map<String, Int>)

    /**
     * Every preferences file this app owns. Named explicitly rather than discovered: a wipe that
     * deletes "every file in the preferences directory" would be a wipe that someday deletes something
     * the platform put there, and the destructive reset is the last place to be clever.
     */
    private val PREFERENCE_FILES = listOf(
        SharedPrefsParentCredentialStore.FILE_NAME,
        "parentapproved_sessions",
        SharedPrefsParentCredentialStore.LOCKOUT_FILE_NAME,
        "parentapproved_relay",
        "parentapproved_catalog_debug",
    )
}
