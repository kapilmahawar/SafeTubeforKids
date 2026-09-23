package tv.safetubeforkids.app.data.catalog

/**
 * What a catalog entry points at on YouTube.
 *
 * A category is a parent-defined shelf ("Cartoon", "Music", ...); each entry inside it is either
 * a whole YouTube playlist or one individual video. Keeping the two apart means an individual
 * video is never smuggled in as a one-item playlist, and an individual item never forces a
 * visible category of its own.
 */
enum class ContentItemType {
    PLAYLIST,
    VIDEO,
}
