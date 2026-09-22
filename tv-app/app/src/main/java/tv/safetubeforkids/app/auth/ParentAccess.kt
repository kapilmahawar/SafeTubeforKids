package tv.safetubeforkids.app.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * Whether a parent has ever paired with this TV.
 *
 * The pairing code has to be readable before anyone knows it - that is how the first phone gets in.
 * Afterwards it stops being public: the pairing screen and the parent settings both demand the PIN,
 * so a child holding the remote cannot read the code that controls the dashboard, reset the PIN, or
 * erase the watch history a parent relies on. Resetting the PIN puts the TV back into pairing mode,
 * because the new PIN has not been used by anyone yet.
 */
class ParentAccess(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("safetube_parent_access", Context.MODE_PRIVATE)

    /** True once any PIN check has succeeded, whether on the TV or from the phone. */
    var hasPaired: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PAIRED, value).apply()
        }

    private companion object {
        const val KEY_PAIRED = "paired_once"
    }
}
