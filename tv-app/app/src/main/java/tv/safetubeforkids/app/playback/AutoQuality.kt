package tv.safetubeforkids.app.playback

/**
 * Picks a rendition for the "Auto" quality setting.
 *
 * YouTube hands this app progressive streams, where the rendition is baked into the URL: unlike
 * DASH, the player cannot switch renditions mid-stream. So the choice is made when a video starts,
 * from the bandwidth the player has actually measured, and it is revised downward if playback
 * stalls. That last part is what matters for a child: a smaller picture that keeps playing beats a
 * sharp one that stops.
 */
internal object AutoQuality {

    /** Share of the measured bandwidth a stream may claim; the rest absorbs overhead and jitter. */
    private const val SAFETY_PERCENT = 70

    /**
     * Used when a video starts before anything has been measured, so that Auto never opens with the
     * largest rendition on an unknown connection.
     */
    const val DEFAULT_START_HEIGHT = 720

    /**
     * The lowest rendition Auto picks while the measurement is still unclear. A TV picture below
     * this is not worth watching, and a connection that truly cannot manage it is handled by
     * stepping down after a stall rather than by opening at the smallest rendition - the first
     * measurement of a stream is often low simply because little has been downloaded yet.
     */
    const val MIN_AUTO_HEIGHT = 360

    /** Typical bitrates in kbps, for renditions whose bitrate the extractor does not report. */
    private val TYPICAL_BITRATE_KBPS = mapOf(
        2160 to 24000,
        1440 to 12000,
        1080 to 5000,
        720 to 2500,
        480 to 1200,
        360 to 700,
        240 to 400,
        144 to 200,
    )

    /** The bitrate to budget with: what the extractor reported, else a typical one for the height. */
    fun bitrateOf(quality: QualityOption): Int {
        val reported = normalisedKbps(quality.bitrateKbps)
        return reported.takeIf { it > 0 }
            ?: TYPICAL_BITRATE_KBPS.entries
                .filter { it.key <= quality.height }
                .maxByOrNull { it.key }?.value
            ?: TYPICAL_BITRATE_KBPS.values.min()
    }

    /**
     * The extractor reports video bitrates in bits per second while this policy budgets in kbps;
     * a value far too large to be kbps is therefore a per-second one. Normalising here rather than
     * at the extractor keeps the raw number available to anything that wants it.
     */
    private fun normalisedKbps(raw: Int): Int = when {
        raw <= 0 -> 0
        raw > 100_000 -> raw / 1000
        else -> raw
    }

    /**
     * The rendition to play.
     *
     * @param bandwidthKbps measured throughput, or null when nothing has been measured yet.
     * @param ceilingHeight never pick above this. It is set after a stall so the app stops climbing
     *   back into a rendition the connection has already failed to sustain.
     */
    fun choose(
        qualities: List<QualityOption>,
        bandwidthKbps: Int?,
        ceilingHeight: Int? = null,
    ): QualityOption? {
        if (qualities.isEmpty()) return null

        // A ceiling below every available rendition cannot be honoured; the smallest still plays.
        val allowed = qualities
            .filter { ceilingHeight == null || it.height <= ceilingHeight }
            .ifEmpty { listOfNotNull(qualities.minByOrNull { it.height }) }

        if (bandwidthKbps == null || bandwidthKbps <= 0) {
            return allowed.filter { it.height <= DEFAULT_START_HEIGHT }.maxByOrNull { it.height }
                ?: allowed.minByOrNull { it.height }
        }

        val budget = bandwidthKbps * SAFETY_PERCENT / 100
        allowed.filter { bitrateOf(it) <= budget }.maxByOrNull { it.height }?.let { return it }

        // Nothing fits the budget. Once a stall has set a ceiling, the ceiling wins and the
        // smallest rendition plays; otherwise the floor keeps a warming-up measurement from
        // dropping the child to an unwatchable picture.
        val floor = if (ceilingHeight == null) MIN_AUTO_HEIGHT else 0
        return allowed.filter { it.height >= floor }.minByOrNull { bitrateOf(it) }
            ?: allowed.minByOrNull { bitrateOf(it) }
    }
}
