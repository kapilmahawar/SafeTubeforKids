package tv.safetubeforkids.app.util

/**
 * Debug-only stand-in for the player's bandwidth measurement.
 *
 * A real stall cannot be produced on demand, so the on-device check sets a number here through the
 * debug receiver and Auto quality must then pick the rendition that number deserves. Only the debug
 * manifest declares that receiver, so a release build has no way to reach this.
 */
object BandwidthOverride {

    @Volatile
    var kbps: Int? = null
        private set

    fun set(kbps: Int?) {
        this.kbps = kbps?.takeIf { it > 0 }
    }
}
