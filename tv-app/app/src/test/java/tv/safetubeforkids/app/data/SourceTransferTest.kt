package tv.safetubeforkids.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTransferTest {

    private val playlist = ExportedSource("yt_playlist", "PL123", "https://www.youtube.com/playlist?list=PL123", "Season 2", 50)
    private val video = ExportedSource("yt_video", "vid1", "https://www.youtube.com/watch?v=vid1", "A video", 1)

    @Test
    fun `export and import round trip`() {
        val payload = SourceTransfer.export(listOf(playlist, video), now = 1234L)
        val parsed = SourceTransfer.parse(payload)
        assertNotNull(parsed)
        assertEquals(listOf(playlist, video), parsed)
    }

    @Test
    fun `a bare array is accepted`() {
        val parsed = SourceTransfer.parse("""[{"sourceType":"yt_video","sourceId":"a","sourceUrl":"https://youtu.be/a"}]""")
        assertEquals(1, parsed?.size)
    }

    @Test
    fun `unreadable payloads are refused rather than half applied`() {
        assertNull(SourceTransfer.parse(""))
        assertNull(SourceTransfer.parse("not json at all"))
        assertNull(SourceTransfer.parse("""{"version":1,"sources":"wrong type"}"""))
    }

    @Test
    fun `validation reports why an entry was refused`() {
        val (usable, failures) = SourceTransfer.validate(
            listOf(
                playlist,
                ExportedSource("yt_video", "", "https://youtu.be/x"),
                ExportedSource("yt_video", "b", ""),
                ExportedSource("vimeo", "c", "https://vimeo.com/1"),
            )
        )
        assertEquals(listOf(playlist), usable)
        assertEquals(3, failures.size)
        assertTrue(failures.any { it.reason.contains("missing source id") })
        assertTrue(failures.any { it.reason.contains("missing source url") })
        assertTrue(failures.any { it.reason.contains("unsupported source type") })
    }

    @Test
    fun `duplicates inside one payload collapse to the first occurrence`() {
        val result = SourceTransfer.distinctBySourceId(listOf(playlist, playlist.copy(displayName = "later"), video))
        assertEquals(listOf("PL123", "vid1"), result.map { it.sourceId })
        assertEquals("Season 2", result.first().displayName)
    }
}
