package tv.parentapproved.app.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.safetubeforkids.app.data.CollectedVideos
import tv.safetubeforkids.app.data.PagedVideoSource
import tv.safetubeforkids.app.data.ResolvedPage
import tv.safetubeforkids.app.data.collectPages
import tv.safetubeforkids.app.data.models.VideoItem

/**
 * Walking every page of a playlist, which is the part of a playlist import that can go wrong quietly.
 *
 * The collector is deliberately free of YouTube: it is handed a [PagedVideoSource] and asks for the
 * next page until there is none. That is what makes "the whole playlist, or nothing" testable here -
 * with a playlist of any length, pages that repeat a video, items that cannot be stored, and a page
 * that fails in the middle - without a network, an API key or a fixture that has to be long enough to
 * have a second page.
 *
 * The failure that matters most is the last one: a page that throws must reach the caller, because the
 * import's whole transaction boundary depends on nothing being written until the *complete* playlist
 * has been resolved.
 */
class PlaylistImportResolutionTest {

    private fun video(id: String, title: String = "Title $id") =
        VideoItem(
            videoId = id,
            title = title,
            thumbnailUrl = "https://img/$id.jpg",
            durationSeconds = 60,
            playlistId = "PLsource",
            position = 0,
        )

    /** A playlist of [pages], each the items of one page, with the tokens a real walk would see. */
    private class FakePlaylist(
        override val title: String,
        private val pages: List<List<VideoItem>>,
        private val failOnPage: Int? = null,
    ) : PagedVideoSource {
        var calls = 0
            private set

        override fun page(pageToken: String?): ResolvedPage {
            val index = pageToken?.toIntOrNull() ?: 0
            if (failOnPage == index) throw IOException("page $index could not be fetched")
            calls++
            val items = pages.getOrElse(index) { emptyList() }
            val next = if (index + 1 < pages.size) (index + 1).toString() else null
            return ResolvedPage(items, next)
        }
    }

    @Test
    fun everyPageIsCollectedInOrder() {
        val playlist = FakePlaylist("Three pages", listOf(
            listOf(video("a"), video("b")),
            listOf(video("c"), video("d")),
            listOf(video("e")),
        ))

        val collected = collectPages(playlist, limit = 100)

        assertEquals("Three pages", collected.title)
        assertEquals(listOf("a", "b", "c", "d", "e"), collected.videos.map { it.videoId })
        assertEquals(listOf(0, 1, 2, 3, 4), collected.videos.map { it.position })
        assertEquals("the last page must not be followed by another request", 3, playlist.calls)
        assertEquals(false, collected.truncated)
    }

    @Test
    fun aPlaylistOfOnePageIsWalkedOnceAndStops() {
        val playlist = FakePlaylist("One page", listOf(listOf(video("a"), video("b"))))

        val collected = collectPages(playlist, limit = 100)

        assertEquals(listOf("a", "b"), collected.videos.map { it.videoId })
        assertEquals(1, playlist.calls)
        assertEquals(false, collected.truncated)
    }

    @Test
    fun anEmptyPlaylistIsNotAFailure() {
        val collected = collectPages(FakePlaylist("Empty", listOf(emptyList())), limit = 100)

        assertTrue(collected.videos.isEmpty())
        assertEquals(false, collected.truncated)
    }

    @Test
    fun aVideoListedTwiceIsCollectedOnce() {
        // Once within one page, and once again on the next page: the same video is one video.
        val playlist = FakePlaylist("Repeats", listOf(
            listOf(video("a"), video("a"), video("b")),
            listOf(video("b"), video("c")),
        ))

        val collected = collectPages(playlist, limit = 100)

        assertEquals(listOf("a", "b", "c"), collected.videos.map { it.videoId })
        assertEquals(listOf(0, 1, 2), collected.videos.map { it.position })
    }

    @Test
    fun anItemThatCannotBeStoredIsSkippedAndCounted() {
        // A playlist can list an unavailable, private or deleted video, and its entry may carry no
        // usable identifier at all. Those must never become catalog nodes - and must not stop the rest.
        val playlist = FakePlaylist("With gaps", listOf(
            listOf(video("goodOne"), video(""), video("   "), video("has space"), video("goodTwo")),
        ))

        val collected = collectPages(playlist, limit = 100)

        assertEquals(listOf("goodOne", "goodTwo"), collected.videos.map { it.videoId })
        assertEquals(3, collected.unusableItems)
        assertEquals(listOf(0, 1), collected.videos.map { it.position })
    }

    @Test
    fun theLimitStopsTheWalkAndSaysSoRatherThanTruncatingQuietly() {
        val playlist = FakePlaylist("Long", listOf(
            listOf(video("a"), video("b"), video("c")),
            listOf(video("d"), video("e")),
        ))

        val collected = collectPages(playlist, limit = 4)

        assertEquals(listOf("a", "b", "c", "d"), collected.videos.map { it.videoId })
        assertEquals("the caller has to be able to say the playlist is longer than this", true, collected.truncated)
        assertEquals("it stopped inside the second page", 2, playlist.calls)
    }

    @Test
    fun aPlaylistThatExactlyFitsTheLimitIsNotReportedAsTruncated() {
        val playlist = FakePlaylist("Exact", listOf(listOf(video("a"), video("b")), listOf(video("c"))))

        val collected = collectPages(playlist, limit = 3)

        assertEquals(listOf("a", "b", "c"), collected.videos.map { it.videoId })
        assertEquals("nothing was dropped, so nothing was truncated", false, collected.truncated)
        assertEquals(2, playlist.calls)
    }

    @Test
    fun skippedItemsDoNotConsumeTheLimit() {
        val playlist = FakePlaylist("Gaps", listOf(
            listOf(video(""), video("a"), video(""), video("b")),
        ))

        val collected = collectPages(playlist, limit = 2)

        // Four entries, two of them unusable, and the limit of two was filled by the two real videos:
        // the limit counts videos to store, never entries to skip.
        assertEquals(listOf("a", "b"), collected.videos.map { it.videoId })
        assertEquals(2, collected.unusableItems)
        assertEquals(false, collected.truncated)
    }

    @Test
    fun aPageThatFailsReachesTheCallerSoNothingIsCommitted() {
        val playlist = FakePlaylist(
            "Fails on the second page",
            listOf(listOf(video("a")), listOf(video("b"))),
            failOnPage = 1,
        )

        val failure = runCatching { collectPages(playlist, limit = 100) }

        assertTrue("a playlist that cannot be fully resolved must fail, not half-succeed", failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IOException)
    }

    @Test
    fun theCollectorDoesNotInventATitleWhenTheSourceHasNone() {
        val collected = collectPages(FakePlaylist("PLabcdef", listOf(listOf(video("a")))), limit = 10)

        assertEquals("PLabcdef", collected.title)
        assertEquals(0, collected.unusableItems)
    }

    @Test
    fun whatTheCollectorReturnsIsWhatTheImportConsumes() {
        // The shape the server hands the editor: ids and the walk's order, nothing else.
        val collected: CollectedVideos = collectPages(
            FakePlaylist("Order", listOf(listOf(video("z"), video("y")))),
            limit = 10,
        )

        assertEquals(listOf("z", "y"), collected.videos.map { it.videoId })
        assertNotNull(collected.videos.first().thumbnailUrl)
    }

    @Test
    fun aUsableVideoIdIsAcceptedOnlyWhenItSurvivesTheRoundTrip() {
        // The same rule the resolver applies before storing an item, the catalog validator applies to
        // a node, and the parser applies to a pasted URL.
        assertNull(tv.safetubeforkids.app.util.ContentSourceParser.videoIdProblem("DuXwFlL8Usk"))
        assertNull(tv.safetubeforkids.app.util.ContentSourceParser.videoIdProblem("short"))

        assertNotNull(tv.safetubeforkids.app.util.ContentSourceParser.videoIdProblem(""))
        assertNotNull(tv.safetubeforkids.app.util.ContentSourceParser.videoIdProblem("has space"))
        assertNotNull(tv.safetubeforkids.app.util.ContentSourceParser.videoIdProblem("@PBSKids"))
        assertNotNull(tv.safetubeforkids.app.util.ContentSourceParser.videoIdProblem("vid&x"))

        assertNull(tv.safetubeforkids.app.util.ContentSourceParser.playlistIdProblem("PLcocomelon123"))
        assertNotNull(tv.safetubeforkids.app.util.ContentSourceParser.playlistIdProblem(""))
        assertNotNull("an auto-generated mix is not a playlist", tv.safetubeforkids.app.util.ContentSourceParser.playlistIdProblem("RDmix"))
    }
}
