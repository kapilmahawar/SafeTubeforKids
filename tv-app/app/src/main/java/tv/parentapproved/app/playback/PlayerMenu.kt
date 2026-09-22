package tv.parentapproved.app.playback

/** TV player menus. Each one opens an overlay list that BACK closes before leaving the player. */
enum class PlayerMenu {
    RESUME,
    CAPTIONS,
    QUALITY,
    AUDIO,
    SPEED,
    ASPECT,
}

/** One row in a player menu. [id] is opaque to the UI; the controller interprets it. */
data class PlayerOption(
    val id: String,
    val label: String,
    val selected: Boolean,
)

/** Aspect-ratio choices, mapped onto Media3's resize modes in order. */
object AspectChoices {
    const val FIT = "fit"
    const val ZOOM = "zoom"
    const val FILL = "fill"

    val ordered = listOf(
        FIT to "Fit screen",
        ZOOM to "Crop to fill",
        FILL to "Stretch",
    )
}
