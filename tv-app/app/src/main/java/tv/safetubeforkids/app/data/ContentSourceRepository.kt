package tv.safetubeforkids.app.data

import androidx.room.withTransaction
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.cache.ChannelEntity
import tv.safetubeforkids.app.data.cache.VideoEntity
import tv.safetubeforkids.app.data.models.VideoItem
import tv.safetubeforkids.app.util.AppLogger
import tv.safetubeforkids.app.util.ContentSourceParser
import tv.safetubeforkids.app.util.OfflineSimulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem

private const val MAX_VIDEOS_PER_SOURCE = 200

/**
 * How many videos one **catalog import** may pull from a single playlist.
 *
 * Higher than the approved-source cache's cap because an import is a curation step rather than a
 * playback cache, and lower than unbounded because the result becomes one catalog document that the
 * TV has to store. It is never applied silently: the caller is told when it stopped the walk, so the
 * editor can say "the playlist has more videos than this import took".
 */
const val MAX_VIDEOS_PER_IMPORT = 500

data class ChannelMeta(
    val id: Long,
    val sourceType: String,
    val sourceId: String,
    val sourceUrl: String,
    val displayName: String,
)

data class ResolvedSource(
    val title: String,
    val videos: List<VideoItem>,
    /** True when a cap stopped the walk before the source ran out. */
    val truncated: Boolean = false,
    /** Items the source listed that cannot become anything (unavailable, private, deleted). */
    val unusableItems: Int = 0,
)

/**
 * One page of a paged YouTube source, in the shape the collector needs and nothing more.
 *
 * `nextPageToken` is opaque: the collector only asks "is there more?", which is what makes the walk
 * testable without a network, a YouTube client or a playlist that happens to be long enough to have a
 * second page.
 */
data class ResolvedPage(
    val videos: List<VideoItem>,
    val nextPageToken: String?,
)

/** A paged source the collector can walk. One implementation wraps the NewPipe extractor. */
interface PagedVideoSource {
    val title: String

    /** The page after [pageToken] (null = the first page), with the token of the one after it. */
    fun page(pageToken: String?): ResolvedPage
}

/** What walking every page produced. */
data class CollectedVideos(
    val title: String,
    val videos: List<VideoItem>,
    /** The cap stopped the walk; the source may have more. */
    val truncated: Boolean,
    /** Items the source listed but whose video id is not usable, so nothing may store them. */
    val unusableItems: Int,
)

/**
 * Walks every page of [source] until it has no next page, collecting the items in source order.
 *
 * This is the whole of the pagination rule, in one place and free of YouTube: keep asking for the next
 * page until there is not one, and never truncate silently - a cap stops the walk *and* says so. A
 * page that fails (network, API, malformed) propagates to the caller, which is what lets a playlist
 * import resolve completely before it changes anything, and change nothing at all when it cannot.
 *
 * Items are collected in source order and de-duplicated by video id *within this walk*: the same video
 * listed twice on one page, or once on each of two pages, is one video. Nothing else about an item is
 * used as identity - not its title, its position, its thumbnail or its URL.
 */
fun collectPages(source: PagedVideoSource, limit: Int): CollectedVideos {
    val videos = mutableListOf<VideoItem>()
    val seen = mutableSetOf<String>()
    var unusable = 0

    var token: String? = null
    var truncated = false

    while (true) {
        val page = source.page(token)
        for (item in page.videos) {
            if (videos.size >= limit) {
                truncated = true
                break
            }
            if (ContentSourceParser.videoIdProblem(item.videoId) != null) {
                // An unavailable, private or deleted video: skip it and keep going, rather than
                // storing a node that names a video nothing can play.
                unusable++
                AppLogger.warn("Skipping playlist item with unusable video id '${item.videoId}'")
                continue
            }
            if (!seen.add(item.videoId)) continue
            videos += item.copy(position = videos.size)
        }
        if (truncated) break
        token = page.nextPageToken ?: break
    }

    return CollectedVideos(title = source.title, videos = videos, truncated = truncated, unusableItems = unusable)
}

object ContentSourceRepository {

    /**
     * Resolve a content source by type, returning its title and video list.
     *
     * @param limit how many videos the walk may collect before it stops and reports truncation.
     */
    suspend fun resolve(
        sourceType: String,
        sourceId: String,
        limit: Int = MAX_VIDEOS_PER_SOURCE,
    ): ResolvedSource = withContext(Dispatchers.IO) {
        if (OfflineSimulator.isOffline) throw java.io.IOException("Simulated offline")

        when (sourceType) {
            "yt_playlist" -> resolvePlaylist(sourceId, limit)
            "yt_video" -> resolveVideo(sourceId)
            "yt_channel" -> resolveChannel(sourceId)
            else -> throw IllegalArgumentException("Unknown source type: $sourceType")
        }
    }

    /**
     * Resolves a playlist for a **catalog import**: every page, in source order, with unusable items
     * skipped and truncation reported rather than hidden.
     */
    suspend fun resolvePlaylistForImport(
        playlistId: String,
        limit: Int = MAX_VIDEOS_PER_IMPORT,
    ): ResolvedSource = withContext(Dispatchers.IO) {
        if (OfflineSimulator.isOffline) throw java.io.IOException("Simulated offline")
        resolvePlaylist(playlistId, limit)
    }

    private fun resolvePlaylist(playlistId: String, limit: Int): ResolvedSource {
        val source = NewPipePlaylistSource(playlistId)
        val collected = collectPages(source, limit)

        AppLogger.success(
            "Resolved playlist $playlistId: ${collected.videos.size} videos, title: ${collected.title}" +
                (if (collected.truncated) " (truncated at $limit)" else "") +
                (if (collected.unusableItems > 0) ", ${collected.unusableItems} unusable items skipped" else "")
        )
        return ResolvedSource(
            title = collected.title,
            videos = collected.videos,
            truncated = collected.truncated,
            unusableItems = collected.unusableItems,
        )
    }

    /**
     * The NewPipe playlist extractor, presented as a [PagedVideoSource].
     *
     * The extractor hands out `Page` handles rather than tokens, so this adapter remembers the handle
     * the last page pointed at and feeds it back to `getPage` on the next call. The walk is strictly
     * sequential, so one remembered handle is exactly enough.
     */
    private class NewPipePlaylistSource(private val playlistId: String) : PagedVideoSource {
        private val extractor = ServiceList.YouTube.getPlaylistExtractor(
            "https://www.youtube.com/playlist?list=$playlistId"
        )
        private var pendingPage: Page? = null

        override val title: String
            get() = try {
                extractor.name?.takeIf { it.isNotBlank() } ?: playlistId
            } catch (_: Exception) {
                playlistId
            }

        override fun page(pageToken: String?): ResolvedPage {
            val items = if (pageToken == null) {
                extractor.fetchPage()
                extractor.initialPage
            } else {
                val next = pendingPage ?: throw IllegalStateException("playlist page walk lost its place")
                extractor.getPage(next)
            }
            pendingPage = items.nextPage
            return ResolvedPage(videosOf(items), items.nextPage?.let { "next" })
        }

        private fun videosOf(page: InfoItemsPage<StreamInfoItem>): List<VideoItem> =
            page.items.map { item ->
                VideoItem(
                    videoId = extractVideoId(item.url),
                    title = item.name ?: "(no title)",
                    thumbnailUrl = item.thumbnails?.firstOrNull()?.url ?: "",
                    durationSeconds = item.duration,
                    playlistId = playlistId,
                    position = 0, // the collector assigns the position of the walk
                )
            }
    }

    private fun resolveVideo(videoId: String): ResolvedSource {
        val url = "https://www.youtube.com/watch?v=$videoId"
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()

        val title = try {
            extractor.name?.takeIf { it.isNotBlank() } ?: videoId
        } catch (_: Exception) { videoId }

        val thumbnail = try {
            extractor.thumbnails?.firstOrNull()?.url ?: ""
        } catch (_: Exception) { "" }

        val duration = try {
            extractor.length
        } catch (_: Exception) { 0L }

        val video = VideoItem(
            videoId = videoId,
            title = title,
            thumbnailUrl = thumbnail,
            durationSeconds = duration,
            playlistId = videoId, // single video uses videoId as "playlist"
            position = 0,
        )

        AppLogger.success("Resolved video $videoId: $title")
        return ResolvedSource(title = title, videos = listOf(video))
    }

    private fun resolveChannel(channelId: String): ResolvedSource {
        val url = buildCanonicalUrl("yt_channel", channelId)
        val extractor = ServiceList.YouTube.getChannelExtractor(url)
        extractor.fetchPage()

        val channelTitle = try {
            extractor.name?.takeIf { it.isNotBlank() } ?: channelId
        } catch (_: Exception) { channelId }

        val videos = mutableListOf<VideoItem>()

        // Channel extractors use tabs — find the Videos tab
        try {
            val tabs = extractor.tabs
            val videosTab = tabs.firstOrNull { tab ->
                tab.contentFilters.any { it.contains("videos", ignoreCase = true) }
            } ?: tabs.firstOrNull() // Fall back to first tab

            if (videosTab != null) {
                val tabExtractor = ServiceList.YouTube.getChannelTabExtractor(videosTab)
                tabExtractor.fetchPage()

                val initialPage = tabExtractor.initialPage
                initialPage.items.forEach { item ->
                    if (videos.size >= MAX_VIDEOS_PER_SOURCE) return@forEach
                    videos.add(VideoItem(
                        videoId = extractVideoId(item.url),
                        title = item.name ?: "(no title)",
                        thumbnailUrl = item.thumbnails?.firstOrNull()?.url ?: "",
                        durationSeconds = if (item is org.schabi.newpipe.extractor.stream.StreamInfoItem) item.duration else 0L,
                        playlistId = channelId,
                        position = videos.size,
                    ))
                }

                var nextPage = initialPage.nextPage
                while (nextPage != null && videos.size < MAX_VIDEOS_PER_SOURCE) {
                    val page = tabExtractor.getPage(nextPage)
                    page.items.forEach { item ->
                        if (videos.size >= MAX_VIDEOS_PER_SOURCE) return@forEach
                        videos.add(VideoItem(
                            videoId = extractVideoId(item.url),
                            title = item.name ?: "(no title)",
                            thumbnailUrl = item.thumbnails?.firstOrNull()?.url ?: "",
                            durationSeconds = if (item is org.schabi.newpipe.extractor.stream.StreamInfoItem) item.duration else 0L,
                            playlistId = channelId,
                            position = videos.size,
                        ))
                    }
                    nextPage = page.nextPage
                }
            }
        } catch (e: Exception) {
            AppLogger.error("Channel tab extraction failed for $channelId: ${e.message}")
        }

        AppLogger.success("Resolved channel $channelId: ${videos.size} videos, title: $channelTitle")
        return ResolvedSource(title = channelTitle, videos = videos)
    }

    /**
     * Resolve all channels sequentially (not parallel — avoids rate limiting).
     */
    suspend fun resolveAllChannels(
        channels: List<ChannelMeta>,
        db: CacheDatabase,
    ): Map<String, SourceResult> {
        val results = mutableMapOf<String, SourceResult>()
        for (meta in channels) {
            results[meta.sourceId] = try {
                val resolved = resolve(meta.sourceType, meta.sourceId)
                cacheVideos(db, meta.sourceId, resolved.videos)

                // Update display name and video count
                val entity = db.channelDao().getBySourceId(meta.sourceId)
                if (entity != null) {
                    val newName = if (resolved.title.isNotBlank() && resolved.title != meta.sourceId) resolved.title else entity.displayName
                    db.channelDao().updateMeta(entity.id, newName, resolved.videos.size)
                }

                SourceResult.Success(resolved.videos)
            } catch (e: Exception) {
                AppLogger.error("Resolve ${meta.sourceId} failed: ${e.message}")
                val cached = getCachedVideos(db, meta.sourceId)
                if (cached.isNotEmpty()) {
                    SourceResult.CachedFallback(cached)
                } else {
                    SourceResult.Error(e.message ?: "Unknown error")
                }
            }
        }
        return results
    }

    /**
     * Replaces one source's cached videos with a freshly resolved list, as one transaction.
     *
     * The delete and the insert are a single replacement, so an interrupted refresh - the resolver
     * throwing, the process being killed between the two statements - leaves the previous rows exactly
     * as they were. Without the transaction the source was left with no cached videos at all, and
     * because `PlaybackAuthorization` approves a video only while it is in this cache, a failed refresh
     * silently turned into "nothing from this source can play any more" until the next good resolve.
     *
     * A refresh that legitimately resolves nothing still clears the cache: this makes the replacement
     * atomic, not conditional.
     */
    suspend fun cacheVideos(db: CacheDatabase, sourceId: String, videos: List<VideoItem>) {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.videoDao().deleteByPlaylist(sourceId)
                db.videoDao().insertAll(videos.map { v ->
                    VideoEntity(
                        videoId = v.videoId,
                        playlistId = v.playlistId,
                        title = v.title,
                        thumbnailUrl = v.thumbnailUrl,
                        durationSeconds = v.durationSeconds,
                        position = v.position,
                    )
                })
            }
        }
    }

    suspend fun getCachedVideos(db: CacheDatabase, sourceId: String): List<VideoItem> {
        return withContext(Dispatchers.IO) {
            db.videoDao().getByPlaylist(sourceId).map { e ->
                VideoItem(
                    videoId = e.videoId,
                    title = e.title,
                    thumbnailUrl = e.thumbnailUrl,
                    durationSeconds = e.durationSeconds,
                    playlistId = e.playlistId,
                    position = e.position,
                )
            }
        }
    }

    fun buildCanonicalUrl(sourceType: String, sourceId: String): String {
        return when (sourceType) {
            "yt_playlist" -> "https://www.youtube.com/playlist?list=$sourceId"
            "yt_video" -> "https://www.youtube.com/watch?v=$sourceId"
            "yt_channel" -> {
                when {
                    sourceId.startsWith("UC") -> "https://www.youtube.com/channel/$sourceId"
                    sourceId.startsWith("@") -> "https://www.youtube.com/$sourceId"
                    sourceId.startsWith("c/") || sourceId.startsWith("user/") -> "https://www.youtube.com/$sourceId"
                    else -> "https://www.youtube.com/$sourceId"
                }
            }
            else -> sourceId
        }
    }

    private fun extractVideoId(url: String): String {
        val match = Regex("[?&]v=([a-zA-Z0-9_-]+)").find(url)
        return match?.groupValues?.get(1) ?: url.substringAfterLast("/")
    }
}

sealed class SourceResult {
    data class Success(val videos: List<VideoItem>) : SourceResult()
    data class CachedFallback(val videos: List<VideoItem>) : SourceResult()
    data class Error(val message: String) : SourceResult()
}
