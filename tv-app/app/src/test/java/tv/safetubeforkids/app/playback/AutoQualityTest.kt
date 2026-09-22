package tv.safetubeforkids.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Auto quality is what stands between a weak connection and a video that keeps stopping, so the
 * policy is pinned here rather than only observed on a TV.
 */
class AutoQualityTest {

    private fun rendition(height: Int, bitrateKbps: Int = 0) = QualityOption(
        height = height,
        label = "${height}p",
        videoUrl = "https://example.invalid/$height",
        audioUrl = null,
        isMerged = false,
        bitrateKbps = bitrateKbps,
    )

    /** The usual YouTube set, with the bitrates the extractor reports for them. */
    private val typical = listOf(
        rendition(720, 2000),
        rendition(480, 900),
        rendition(360, 600),
        rendition(240, 300),
    )

    @Test
    fun `reported bitrate wins over the height table`() {
        assertEquals(2000, AutoQuality.bitrateOf(rendition(720, 2000)))
    }

    @Test
    fun `a bitrate reported in bits per second is normalised to kbps`() {
        // The extractor hands out bits per second for video; budgeting on that raw number would
        // make every rendition look unaffordable, which is how Auto ended up pinned at 360p.
        assertEquals(2500, AutoQuality.bitrateOf(rendition(720, 2_500_000)))
        assertEquals(5000, AutoQuality.bitrateOf(rendition(1080, 5_000_000)))
    }

    @Test
    fun `a per-second bitrate still selects the right rendition`() {
        val rendered = listOf(
            rendition(1080, 5_000_000),
            rendition(720, 2_500_000),
            rendition(480, 1_200_000),
        )
        assertEquals(1080, AutoQuality.choose(rendered, bandwidthKbps = 20_000)?.height)
        assertEquals(720, AutoQuality.choose(rendered, bandwidthKbps = 5000)?.height)
        assertEquals(480, AutoQuality.choose(rendered, bandwidthKbps = 2000)?.height)
    }

    @Test
    fun `unknown bitrate falls back to a typical one for that height`() {
        assertEquals(5000, AutoQuality.bitrateOf(rendition(1080)))
        assertEquals(700, AutoQuality.bitrateOf(rendition(360)))
    }

    @Test
    fun `a height below the table still gets a bitrate`() {
        assertEquals(200, AutoQuality.bitrateOf(rendition(100)))
    }

    @Test
    fun `nothing measured starts at 720p rather than at the largest rendition`() {
        val chosen = AutoQuality.choose(
            qualities = listOf(rendition(1080, 5000)) + typical,
            bandwidthKbps = null,
        )
        assertEquals(720, chosen?.height)
    }

    @Test
    fun `nothing measured and nothing at or below 720p picks the smallest available`() {
        // A 1080p-only video must still play rather than be refused.
        val chosen = AutoQuality.choose(listOf(rendition(1440, 9000), rendition(1080, 5000)), null)
        assertEquals(1080, chosen?.height)
    }

    @Test
    fun `a fast connection is allowed the largest rendition`() {
        val chosen = AutoQuality.choose(typical + rendition(1080, 5000), bandwidthKbps = 25_000)
        assertEquals(1080, chosen?.height)
    }

    @Test
    fun `a slow connection avoids the renditions that would stall`() {
        // 1400 kbps of measured throughput may spend 980 kbps: 480p fits, 720p does not.
        val chosen = AutoQuality.choose(typical, bandwidthKbps = 1400)
        assertEquals(480, chosen?.height)
    }

    @Test
    fun `the budget leaves headroom instead of spending the whole measurement`() {
        // Exactly 720p's bitrate measured: the safety margin must still push the choice down.
        val chosen = AutoQuality.choose(typical, bandwidthKbps = 2000)
        assertEquals(480, chosen?.height)
    }

    @Test
    fun `a measurement too low for anything keeps the picture watchable`() {
        // Nothing fits 100 kbps, but Auto must not open a child's video at 240p on the strength of
        // a measurement taken before much has been downloaded.
        val chosen = AutoQuality.choose(typical, bandwidthKbps = 100)
        assertEquals(360, chosen?.height)
    }

    @Test
    fun `a ceiling set by a stall may go below the floor`() {
        // Here the connection has already proven it cannot cope, so the smallest rendition wins.
        val chosen = AutoQuality.choose(typical, bandwidthKbps = 100, ceilingHeight = 240)
        assertEquals(240, chosen?.height)
    }

    @Test
    fun `when nothing reaches the floor the smallest available still plays`() {
        val tinyOnly = listOf(rendition(240, 300), rendition(144, 150))
        assertEquals(144, AutoQuality.choose(tinyOnly, bandwidthKbps = 50)?.height)
    }

    @Test
    fun `a ceiling set after a stall is never exceeded`() {
        val chosen = AutoQuality.choose(typical, bandwidthKbps = 25_000, ceilingHeight = 360)
        assertEquals(360, chosen?.height)
    }

    @Test
    fun `a ceiling below every rendition cannot be honoured, so the smallest plays`() {
        val chosen = AutoQuality.choose(typical, bandwidthKbps = 25_000, ceilingHeight = 144)
        assertEquals(240, chosen?.height)
    }

    @Test
    fun `no renditions at all is null, not a crash`() {
        assertNull(AutoQuality.choose(emptyList(), bandwidthKbps = 5000))
    }

    @Test
    fun `an extractor that reported no bitrates still respects the measurement`() {
        // Every rendition reports 0, so the height table decides: 1080p is 5000 kbps.
        val unknown = listOf(rendition(1080), rendition(720), rendition(360))
        assertEquals(1080, AutoQuality.choose(unknown, bandwidthKbps = 20_000)?.height)
        assertEquals(720, AutoQuality.choose(unknown, bandwidthKbps = 4000)?.height)
        assertEquals(360, AutoQuality.choose(unknown, bandwidthKbps = 900)?.height)
    }
}
