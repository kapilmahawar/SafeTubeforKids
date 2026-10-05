package tv.safetubeforkids.app.diag

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * W14.14 - debug-only bootstrap for [LayoutWedgeProbe].
 *
 * A ContentProvider is used purely because it runs at process start without editing a single production
 * file: no line of `MainActivity` or `SafeTubeApp` changes, so the code path under investigation stays
 * exactly as it ships. The provider is declared only in `src/debug/AndroidManifest.xml`, so it cannot
 * exist in a release build.
 *
 * It registers lifecycle callbacks and nothing else - it holds no data, answers no queries, and never
 * influences the activity, the window or the view tree.
 */
class LayoutWedgeInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val application = context?.applicationContext as? Application ?: return true
        LayoutWedgeProbe.install(application)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                LayoutWedgeProbe.lifecycle(activity, "onActivityPreCreated")
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                LayoutWedgeProbe.lifecycle(activity, "onActivityCreated")
                LayoutWedgeProbe.observe(activity)
            }

            /**
             * This is the callback that runs AFTER `onCreate` has returned, which is when setContent's
             * ComposeView exists. `observe` is idempotent and also watches the DecorView, so every
             * lifecycle step re-checks rather than assuming one particular callback is the right moment.
             */
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                LayoutWedgeProbe.lifecycle(activity, "onActivityPostCreated")
                LayoutWedgeProbe.observe(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                LayoutWedgeProbe.lifecycle(activity, "onActivityStarted")
                LayoutWedgeProbe.observe(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                LayoutWedgeProbe.lifecycle(activity, "onActivityResumed")
                LayoutWedgeProbe.observe(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                LayoutWedgeProbe.lifecycle(activity, "onActivityPaused")
            }

            override fun onActivityStopped(activity: Activity) {
                LayoutWedgeProbe.lifecycle(activity, "onActivityStopped")
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                LayoutWedgeProbe.lifecycle(activity, "onActivitySaveInstanceState")
            }

            override fun onActivityDestroyed(activity: Activity) {
                LayoutWedgeProbe.lifecycle(activity, "onActivityDestroyed")
            }
        })
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
