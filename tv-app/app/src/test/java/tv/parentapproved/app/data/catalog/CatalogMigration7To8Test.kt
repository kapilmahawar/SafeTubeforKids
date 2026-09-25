package tv.parentapproved.app.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tv.safetubeforkids.app.data.cache.CacheDatabase
import tv.safetubeforkids.app.data.catalog.CatalogNodeType
import tv.safetubeforkids.app.data.catalog.CatalogOrderingService
import tv.safetubeforkids.app.data.catalog.CatalogRepository
import tv.safetubeforkids.app.playback.PlaybackApproval
import tv.safetubeforkids.app.playback.PlaybackAuthorization

/**
 * Version 7 -> 8 -> 9: categories + content_items become one ordered tree of catalog nodes, and the
 * two tables they were stored in are then dropped.
 *
 * The fixture is a real version-7 database built from Room's own DDL for those tables (the statements
 * in MIGRATION_6_7, which are what Room generates and validates), seeded with the shape a parent would
 * actually have: a category holding a playlist import, a direct video and a hidden video, a second
 * category whose playlist has brought nothing in yet, plus approved playback data.
 *
 * Room validates every table, index and foreign key during `onUpgrade`, so opening this database also
 * proves the new table is exactly the schema Room expects and that nothing else moved.
 *
 * The two steps are also exercised *separately* against a raw SQLite handle
 * ([migration8To9DropsTheReplacedTablesAndLeavesEveryNodeUntouched]): Room only ever runs the whole
 * chain, and the acceptance condition for 8 -> 9 is specifically that it changes no node - which is
 * only observable if the version-8 tree can be read before the drop.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogMigration7To8Test {

    private val dbName = "catalog-migration-7-8-test.db"
    private lateinit var context: Context
    private var room: CacheDatabase? = null
    private var rawHelper: SupportSQLiteOpenHelper? = null

    private val approvedPlaylist = "PLcocomelon"
    private val approvedVideos = listOf("vidB", "vidA", "vidC") // deliberately not alphabetical

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        room?.close()
        rawHelper?.close()
        context.deleteDatabase(dbName)
    }

    private fun createVersion7Database() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // --- the seven pre-existing tables, exactly as Room describes them
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `videos` (`videoId` TEXT NOT NULL, `playlistId` TEXT NOT NULL, " +
                            "`title` TEXT NOT NULL, `thumbnailUrl` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, " +
                            "`position` INTEGER NOT NULL, PRIMARY KEY(`videoId`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `play_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`videoId` TEXT NOT NULL, `playlistId` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                            "`durationSec` INTEGER NOT NULL, `completedPct` INTEGER NOT NULL, `title` TEXT NOT NULL DEFAULT '')"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `channels` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`source_type` TEXT NOT NULL, `source_id` TEXT NOT NULL, `source_url` TEXT NOT NULL, " +
                            "`display_name` TEXT NOT NULL, `video_count` INTEGER NOT NULL, `added_at` INTEGER NOT NULL, " +
                            "`status` TEXT NOT NULL)"
                    )
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_channels_source_id` ON `channels` (`source_id`)")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `time_limit_config` (`id` INTEGER NOT NULL, " +
                            "`mondayLimitMin` INTEGER NOT NULL, `tuesdayLimitMin` INTEGER NOT NULL, " +
                            "`wednesdayLimitMin` INTEGER NOT NULL, `thursdayLimitMin` INTEGER NOT NULL, " +
                            "`fridayLimitMin` INTEGER NOT NULL, `saturdayLimitMin` INTEGER NOT NULL, " +
                            "`sundayLimitMin` INTEGER NOT NULL, `bedtimeStartMin` INTEGER NOT NULL, " +
                            "`bedtimeEndMin` INTEGER NOT NULL, `manuallyLocked` INTEGER NOT NULL, " +
                            "`bonusMinutes` INTEGER NOT NULL, `bonusDate` TEXT NOT NULL, PRIMARY KEY(`id`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `kiosk_config` (`id` INTEGER NOT NULL, `kioskEnabled` INTEGER NOT NULL, " +
                            "`enforceTimeLimitsOnAllApps` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `app_whitelist` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`package_name` TEXT NOT NULL, `display_name` TEXT NOT NULL, `whitelisted` INTEGER NOT NULL, " +
                            "`added_at` INTEGER NOT NULL)"
                    )
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_app_whitelist_package_name` ON `app_whitelist` (`package_name`)")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `playback_positions` (`videoId` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, " +
                            "`durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`))"
                    )

                    // --- the version-7 catalog tables
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `display_name` TEXT NOT NULL, " +
                            "`sort_order` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                            "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_sort_order` ON `categories` (`sort_order`)")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `content_items` (`id` TEXT NOT NULL, `category_id` TEXT NOT NULL, " +
                            "`type` TEXT NOT NULL, `display_name` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, " +
                            "`youtube_playlist_id` TEXT, `youtube_video_id` TEXT, `enabled` INTEGER NOT NULL, " +
                            "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                            "FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_content_items_category_id_sort_order` " +
                            "ON `content_items` (`category_id`, `sort_order`)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `catalog_metadata` (`id` INTEGER NOT NULL, `catalog_version` INTEGER NOT NULL, " +
                            "`server_version` INTEGER, `last_successful_sync_at` INTEGER, `last_attempt_at` INTEGER, " +
                            "PRIMARY KEY(`id`))"
                    )

                    // --- approved playback data (what PlaybackAuthorization reads)
                    db.execSQL(
                        "INSERT INTO channels (source_type, source_id, source_url, display_name, video_count, added_at, status) " +
                            "VALUES ('yt_playlist', '$approvedPlaylist', " +
                            "'https://www.youtube.com/playlist?list=$approvedPlaylist', 'CoComelon', 3, 1700000000000, 'active')"
                    )
                    // Cache order is vidB, vidA, vidC - NOT alphabetical, so the migration's use of
                    // `position` is observable.
                    approvedVideos.forEachIndexed { index, videoId ->
                        db.execSQL(
                            "INSERT INTO videos (videoId, playlistId, title, thumbnailUrl, durationSeconds, position) " +
                                "VALUES ('$videoId', '$approvedPlaylist', 'Episode $videoId', 'thumb-$videoId', 60, $index)"
                        )
                    }

                    // --- the parent's configuration
                    db.execSQL(
                        "INSERT INTO categories (id, display_name, sort_order, enabled, created_at, updated_at) " +
                            "VALUES ('cat-cartoon', 'Cartoon', 0, 1, 1700000000000, 1700000000000)"
                    )
                    db.execSQL(
                        "INSERT INTO categories (id, display_name, sort_order, enabled, created_at, updated_at) " +
                            "VALUES ('cat-music', 'Music', 1, 1, 1700000000000, 1700000000000)"
                    )
                    db.execSQL(
                        "INSERT INTO content_items (id, category_id, type, display_name, sort_order, " +
                            "youtube_playlist_id, youtube_video_id, enabled, created_at, updated_at) " +
                            "VALUES ('i-cocomelon', 'cat-cartoon', 'PLAYLIST', 'Cocomelon', 0, '$approvedPlaylist', NULL, 1, " +
                            "1700000000000, 1700000000000)"
                    )
                    db.execSQL(
                        "INSERT INTO content_items (id, category_id, type, display_name, sort_order, " +
                            "youtube_playlist_id, youtube_video_id, enabled, created_at, updated_at) " +
                            "VALUES ('i-halloween', 'cat-cartoon', 'VIDEO', 'Halloween Special', 1, NULL, 'vid-halloween', 1, " +
                            "1700000000000, 1700000000000)"
                    )
                    db.execSQL(
                        "INSERT INTO content_items (id, category_id, type, display_name, sort_order, " +
                            "youtube_playlist_id, youtube_video_id, enabled, created_at, updated_at) " +
                            "VALUES ('i-hidden', 'cat-cartoon', 'VIDEO', 'Hidden Episode', 2, NULL, 'vid-hidden', 0, " +
                            "1700000000000, 1700000000000)"
                    )
                    db.execSQL(
                        "INSERT INTO content_items (id, category_id, type, display_name, sort_order, " +
                            "youtube_playlist_id, youtube_video_id, enabled, created_at, updated_at) " +
                            "VALUES ('i-nursery', 'cat-music', 'PLAYLIST', 'Nursery Songs', 0, 'PLnursery', NULL, 1, " +
                            "1700000000000, 1700000000000)"
                    )
                    db.execSQL(
                        "INSERT INTO catalog_metadata (id, catalog_version, server_version, last_successful_sync_at, last_attempt_at) " +
                            "VALUES (1, 4, 4, 1700000005000, 1700000005000)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            assertEquals("the fixture must really be a version 7 database", 7, db.version)
            db.execSQL("PRAGMA foreign_keys = ON")
        } finally {
            helper.close()
        }
    }

    private fun openWithRoom(): CacheDatabase {
        val opened = Room.databaseBuilder(context, CacheDatabase::class.java, dbName)
            .addMigrations(CacheDatabase.MIGRATION_7_8, CacheDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()
        room = opened
        return opened
    }

    /** A raw handle on the version-7 file, so one migration step can be applied at a time. */
    private fun rawDatabase(): SupportSQLiteDatabase {
        rawHelper?.let { return it.writableDatabase }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        rawHelper = helper
        return helper.writableDatabase
    }

    /** Hands the file back to Room, which must open it with no connection of ours left on it. */
    private fun releaseRawDatabase() {
        rawHelper?.close()
        rawHelper = null
    }

    /**
     * The positions a real upgraded installation can hold: `sort_order` was whatever the parent's
     * configuration source sent, so the v7 fixture is given numbers that are neither contiguous nor in
     * id order, plus one pair that shares a number.
     *
     *  - roots: Music 0, Cartoon 10 - Cartoon's id sorts first, so only the numbers can decide.
     *  - Cartoon's entries: Cocomelon 20, Halloween 5, Hidden 5 - a duplicate, broken by id.
     *  - the imported episodes: vidB 30, vidA 10, vidC 20 - the reverse of their cache order.
     */
    private fun applyLegacyPositions() {
        val db = rawDatabase()
        db.execSQL("UPDATE `categories` SET `sort_order` = 0 WHERE `id` = 'cat-music'")
        db.execSQL("UPDATE `categories` SET `sort_order` = 10 WHERE `id` = 'cat-cartoon'")
        db.execSQL("UPDATE `content_items` SET `sort_order` = 20 WHERE `id` = 'i-cocomelon'")
        db.execSQL("UPDATE `content_items` SET `sort_order` = 5 WHERE `id` = 'i-halloween'")
        db.execSQL("UPDATE `content_items` SET `sort_order` = 5 WHERE `id` = 'i-hidden'")
        db.execSQL("UPDATE `videos` SET `position` = 30 WHERE `videoId` = 'vidB'")
        db.execSQL("UPDATE `videos` SET `position` = 10 WHERE `videoId` = 'vidA'")
        db.execSQL("UPDATE `videos` SET `position` = 20 WHERE `videoId` = 'vidC'")
    }

    private fun SupportSQLiteDatabase.tableNames(): List<String> =
        firstColumnOf("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")

    /**
     * One parent's children as the tree renders them - `position ASC, id ASC` - each with its stored
     * position. `parent_id IS :parentId` is the null-safe comparison the DAO itself uses, so ROOT is
     * the same query with a null parent.
     */
    private fun SupportSQLiteDatabase.childrenUnder(parentId: String?): List<Pair<String, Int>> =
        query(
            "SELECT `id`, `position` FROM `catalog_nodes` " +
                "WHERE `parent_id` IS ${parentId?.let { "'$it'" } ?: "NULL"} " +
                "ORDER BY `position` ASC, `id` ASC"
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }

    private fun SupportSQLiteDatabase.childIdsUnder(parentId: String?): List<String> =
        childrenUnder(parentId).map { it.first }

    private fun SupportSQLiteDatabase.positionsUnder(parentId: String?): List<Int> =
        childrenUnder(parentId).map { it.second }

    /** The invariant itself: every sibling list is numbered 0..n-1. */
    private fun SupportSQLiteDatabase.assertEverySiblingListIsCanonical() {
        val parents = firstColumnOf("SELECT DISTINCT COALESCE(`parent_id`, '<root>') FROM `catalog_nodes`")
        assertTrue("the tree must not be empty", parents.isNotEmpty())
        parents.forEach { parent ->
            val rows = childrenUnder(parent.takeIf { it != "<root>" })
            assertEquals(
                "positions under $parent must be 0..n-1",
                rows.indices.toList(),
                rows.map { it.second },
            )
        }
    }

    /**
     * Every column of every node, as text, in a stable order. Equality of two snapshots is what "the
     * replacement changed no node" means: ids, parents, positions, types, titles, visibility, YouTube
     * ids, playlist provenance, thumbnail configuration and both timestamps.
     *
     * [includePosition] is false when the only thing allowed to change is the numbering - a migration
     * that renumbers siblings must leave every other column of every row exactly as it was.
     */
    private fun SupportSQLiteDatabase.nodeSnapshot(includePosition: Boolean = true): List<String> =
        firstColumnOf(
            "SELECT `id` || '|' || COALESCE(`parent_id`, '-') || '|' || `node_type` || '|' || `title` || '|' || " +
                (if (includePosition) "`position` || '|' || " else "") +
                "`enabled` || '|' || COALESCE(`youtube_video_id`, '-') || '|' || " +
                "COALESCE(`youtube_playlist_id`, '-') || '|' || `thumbnail_mode` || '|' || " +
                "COALESCE(`thumbnail_video_id`, '-') || '|' || COALESCE(`thumbnail_url`, '-') || '|' || " +
                "`created_at` || '|' || `updated_at` " +
                "FROM `catalog_nodes` ORDER BY `parent_id` IS NOT NULL, `parent_id`, `id`"
        )

    private fun CacheDatabase.catalogNodeCount(): Int =
        openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM catalog_nodes")
            .use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
    private fun SupportSQLiteDatabase.firstColumnOf(sql: String): List<String> =
        query(sql).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    // ------------------------------------------------------------------ structure

    @Test
    fun version7DatabaseMigratesToTheCurrentVersion() {
        createVersion7Database()
        assertEquals(9, openWithRoom().openHelper.writableDatabase.version)
    }

    @Test
    fun thePreExistingTablesSurviveAndTheReplacedCatalogTablesAreDropped() {
        createVersion7Database()
        val writable = openWithRoom().openHelper.writableDatabase

        val tables = writable.firstColumnOf("SELECT name FROM sqlite_master WHERE type = 'table'")
        listOf(
            "videos", "play_events", "channels", "time_limit_config", "kiosk_config", "app_whitelist",
            "playback_positions", "catalog_metadata", "catalog_nodes",
        ).forEach { assertTrue("$it missing, tables: $tables", tables.contains(it)) }

        // The whole point of 8 -> 9: Room always migrates to the current version, so a database that
        // opened at version 7 must come out of the chain with the replaced tables gone.
        assertFalse("categories should not exist at version 9, tables: $tables", tables.contains("categories"))
        assertFalse("content_items should not exist at version 9, tables: $tables", tables.contains("content_items"))
    }

    @Test
    fun migration8To9DropsTheReplacedTablesAndLeavesEveryNodeUntouched() {
        createVersion7Database()
        val db = rawDatabase()

        // Step 1, applied exactly as Room would: 7 -> 8 leaves both the tree and the tables it replaced.
        CacheDatabase.MIGRATION_7_8.migrate(db)
        db.version = 8

        val version8Tables = db.tableNames()
        listOf("categories", "content_items", "catalog_nodes", "catalog_metadata").forEach {
            assertTrue("$it missing after 7 -> 8, tables: $version8Tables", version8Tables.contains(it))
        }
        val before = db.nodeSnapshot()
        assertTrue("the 7 -> 8 conversion must have produced nodes", before.isNotEmpty())

        // This fixture's legacy sort_order values are already 0..n-1, so step 2 has nothing to renumber
        // and must leave every single column of every node alone. The renumbering case is covered by
        // migration8To9CanonicalizesLegacyPositionsAndKeepsTheSiblingOrder.
        CacheDatabase.MIGRATION_8_9.migrate(db)
        db.version = 9

        val after = db.tableNames()
        assertFalse("categories should have been dropped, tables: $after", after.contains("categories"))
        assertFalse("content_items should have been dropped, tables: $after", after.contains("content_items"))
        assertTrue("catalog_nodes must survive, tables: $after", after.contains("catalog_nodes"))
        assertTrue("catalog_metadata must survive, tables: $after", after.contains("catalog_metadata"))

        // Nothing was copied or rewritten: every node is byte-for-byte what 7 -> 8 produced.
        assertEquals("8 -> 9 must not touch a canonical tree", before, db.nodeSnapshot())

        // And the dropped tables' indices went with them rather than lingering as orphans.
        val indices = db.firstColumnOf("SELECT name FROM sqlite_master WHERE type = 'index'")
        assertFalse(
            "index_categories_sort_order outlived its table: $indices",
            indices.contains("index_categories_sort_order"),
        )
        assertFalse(
            "index_content_items_category_id_sort_order outlived its table: $indices",
            indices.contains("index_content_items_category_id_sort_order"),
        )
    }

    @Test
    fun migration8To9CanonicalizesLegacyPositionsAndKeepsTheSiblingOrder() {
        createVersion7Database()
        applyLegacyPositions()
        val db = rawDatabase()

        CacheDatabase.MIGRATION_7_8.migrate(db)
        db.version = 8

        // Version 8 holds the legacy numbers verbatim: 7 -> 8 moves data and changes nothing else.
        assertEquals(listOf(0, 10), db.positionsUnder(null))
        assertEquals(listOf(5, 5, 20), db.positionsUnder("cat-cartoon"))
        assertEquals(listOf(10, 20, 30), db.positionsUnder("i-cocomelon"))
        val before = db.nodeSnapshot(includePosition = false)

        CacheDatabase.MIGRATION_8_9.migrate(db)
        db.version = 9

        // 0/10/20 becomes 0/1/2 - here 0/10 -> 0/1 and 5/5/20 -> 0/1/2 - for every parent, including
        // the container's own children.
        assertEquals(listOf(0, 1), db.positionsUnder(null))
        assertEquals(listOf(0, 1, 2), db.positionsUnder("cat-cartoon"))
        assertEquals(listOf(0, 1, 2), db.positionsUnder("i-cocomelon"))
        assertEquals(listOf(0), db.positionsUnder("cat-music"))

        // ...and the relative order the legacy numbers described is exactly what is left, duplicates
        // included: two children sharing 5 are ordered by id, which is the tree's documented tie-break.
        assertEquals(listOf("cat-music", "cat-cartoon"), db.childIdsUnder(null))
        assertEquals(listOf("i-halloween", "i-hidden", "i-cocomelon"), db.childIdsUnder("cat-cartoon"))
        assertEquals(
            // The imported episodes are the container's children, so their ids are derived
            // (`<item id>#<video id>`); the order is what the legacy 10/20/30 described.
            listOf("i-cocomelon#vidA", "i-cocomelon#vidC", "i-cocomelon#vidB"),
            db.childIdsUnder("i-cocomelon"),
        )

        // Only the numbering moved: id, parent, type, title, visibility, YouTube identity, playlist
        // provenance, thumbnail configuration and both timestamps are untouched.
        assertEquals("only position may change", before, db.nodeSnapshot(includePosition = false))

        db.assertEverySiblingListIsCanonical()
    }

    @Test
    fun legacyPositionsReachTheTvAsContiguousPositionsInTheSameOrder() = runBlocking {
        createVersion7Database()
        applyLegacyPositions()
        releaseRawDatabase()

        // The real upgrade path: Room opens the version-7 file and runs 7 -> 8 -> 9 itself.
        val repo = CatalogRepository(openWithRoom())

        // The shelves are in the order the legacy numbers described - Music (0) before Cartoon (10),
        // even though the ids sort the other way - and each reports a canonical position.
        assertEquals(listOf("Music", "Cartoon"), repo.getCategories().map { it.displayName })
        assertEquals(listOf(0, 1), repo.getCategories().map { it.sortOrder })

        assertEquals(
            listOf("Halloween Special", "Hidden Episode", "Cocomelon"),
            repo.getItems("cat-cartoon").map { it.displayName },
        )
        assertEquals(listOf(0, 1, 2), repo.getItems("cat-cartoon").map { it.sortOrder })
    }

    @Test
    fun theNewTableHasTheColumnsIndicesAndForeignKeyRoomExpects() {
        createVersion7Database()
        val writable = openWithRoom().openHelper.writableDatabase

        assertEquals(
            listOf(
                "id", "parent_id", "node_type", "title", "position", "enabled", "youtube_video_id",
                "youtube_playlist_id", "thumbnail_mode", "thumbnail_video_id", "thumbnail_url",
                "created_at", "updated_at",
            ),
            writable.firstColumnOf("SELECT name FROM pragma_table_info('catalog_nodes') ORDER BY cid"),
        )
        assertTrue(
            writable.firstColumnOf("SELECT name FROM pragma_index_list('catalog_nodes')")
                .contains("index_catalog_nodes_parent_id_position")
        )
        assertEquals(
            listOf("parent_id", "position"),
            writable.firstColumnOf(
                "SELECT name FROM pragma_index_info('index_catalog_nodes_parent_id_position') ORDER BY seqno"
            ),
        )
        writable.query("PRAGMA foreign_key_list(`catalog_nodes`)").use { c ->
            assertTrue("catalog_nodes has no self-referencing foreign key", c.moveToFirst())
            assertEquals("catalog_nodes", c.getString(c.getColumnIndexOrThrow("table")))
            assertEquals("parent_id", c.getString(c.getColumnIndexOrThrow("from")))
            assertEquals("id", c.getString(c.getColumnIndexOrThrow("to")))
            assertEquals("CASCADE", c.getString(c.getColumnIndexOrThrow("on_delete")))
        }
    }

    // ------------------------------------------------------------------ the conversion

    @Test
    fun categoriesBecomeRootNodesInTheirConfiguredOrder() = runBlocking {
        createVersion7Database()
        val dao = openWithRoom().catalogNodeDao()

        val root = dao.childrenOf(null)
        assertEquals(listOf("cat-cartoon", "cat-music"), root.map { it.id })
        assertEquals(listOf("Cartoon", "Music"), root.map { it.title })
        assertEquals(listOf(0, 1), root.map { it.position })
        root.forEach { assertEquals(CatalogNodeType.CATEGORY, it.nodeType) }
    }

    @Test
    fun aCategorysMixedChildrenKeepIdentityOrderAndVisibility() = runBlocking {
        createVersion7Database()
        val dao = openWithRoom().catalogNodeDao()

        val children = dao.childrenOf("cat-cartoon")
        assertEquals(listOf("i-cocomelon", "i-halloween", "i-hidden"), children.map { it.id })
        assertEquals(listOf(0, 1, 2), children.map { it.position })

        // The playlist entry became the container the parent had named.
        assertEquals(CatalogNodeType.SUBCATEGORY, children[0].nodeType)
        assertEquals("Cocomelon", children[0].title)
        assertEquals(approvedPlaylist, children[0].youtubePlaylistId)

        // Direct videos stayed videos, with their YouTube identity and their hidden flag.
        assertEquals(CatalogNodeType.VIDEO, children[1].nodeType)
        assertEquals("vid-halloween", children[1].youtubeVideoId)
        assertTrue(children[1].enabled)
        assertEquals(CatalogNodeType.VIDEO, children[2].nodeType)
        assertEquals(false, children[2].enabled)
    }

    @Test
    fun anImportedPlaylistsCachedVideosBecomeItsChildrenInCacheOrder() = runBlocking {
        createVersion7Database()
        val dao = openWithRoom().catalogNodeDao()

        val episodes = dao.childrenOf("i-cocomelon")
        // Cache order, not alphabetical and not by primary key.
        assertEquals(approvedVideos, episodes.map { it.youtubeVideoId })
        assertEquals(listOf(0, 1, 2), episodes.map { it.position })
        assertEquals(listOf("Episode vidB", "Episode vidA", "Episode vidC"), episodes.map { it.title })
        episodes.forEach {
            assertEquals(CatalogNodeType.VIDEO, it.nodeType)
            assertEquals(approvedPlaylist, it.youtubePlaylistId) // provenance survives
            assertTrue(it.enabled)
        }
        // No playlist tile: the playlist exists only as the container above them.
        assertTrue(
            "no node may represent a playlist as playable content",
            dao.all().none { it.nodeType == CatalogNodeType.VIDEO && it.youtubeVideoId == approvedPlaylist },
        )
    }

    @Test
    fun aPlaylistThatHasBroughtNothingInYetBecomesAnEmptyContainer() = runBlocking {
        createVersion7Database()
        val dao = openWithRoom().catalogNodeDao()

        val music = dao.childrenOf("cat-music")
        assertEquals(listOf("i-nursery"), music.map { it.id })
        assertEquals(CatalogNodeType.SUBCATEGORY, music[0].nodeType)
        assertEquals("PLnursery", music[0].youtubePlaylistId)
        assertEquals(0, dao.countChildrenOf("i-nursery"))
    }

    @Test
    fun everySiblingListIsContiguousAndDeterministicAfterTheMigration() = runBlocking {
        createVersion7Database()
        val dao = openWithRoom().catalogNodeDao()

        listOf(null, "cat-cartoon", "i-cocomelon", "cat-music").forEach { parentId ->
            assertEquals(
                "positions under $parentId must be 0..n-1",
                dao.childrenOf(parentId).indices.toList(),
                dao.childrenOf(parentId).map { it.position },
            )
        }
    }

    // ------------------------------------------------------------------ nothing else moved

    @Test
    fun theSecurityBoundaryStillReadsOnlyItsApprovalSources() = runBlocking {
        createVersion7Database()
        val db = openWithRoom()

        // Unchanged by the migration: authorization reads channels + videos, never catalog_nodes.
        val approval = PlaybackAuthorization.authorize(db, "vidA")
        assertTrue("expected Approved, got $approval", approval is PlaybackApproval.Approved)
        assertEquals("vidA", (approval as PlaybackApproval.Approved).video.videoId)
        assertEquals(approvedPlaylist, approval.sourceId)
        assertEquals(
            approvedVideos,
            PlaybackAuthorization.approvedQueue(db, approvedPlaylist).map { it.videoId },
        )
        assertTrue(PlaybackAuthorization.authorize(db, "vid-hidden") is PlaybackApproval.Rejected)

        // And the approved cache is exactly as it was.
        assertEquals(3, db.videoDao().count())
        assertNotNull(db.channelDao().getBySourceId(approvedPlaylist))
        assertEquals(4L, db.catalogMetadataDao().get()!!.catalogVersion)
    }

    @Test
    fun reOpeningTheMigratedDatabaseDoesNotMigrateAgain() {
        createVersion7Database()
        val first = openWithRoom()
        assertEquals(9, first.openHelper.writableDatabase.version)
        val nodesAfterMigration = first.catalogNodeCount()
        assertTrue("the conversion must have produced nodes", nodesAfterMigration > 0)
        room?.close()

        val reopened = openWithRoom()
        assertEquals(9, reopened.openHelper.writableDatabase.version)
        assertEquals(
            "reopening must not convert anything a second time",
            nodesAfterMigration,
            reopened.catalogNodeCount(),
        )
    }

    @Test
    fun theTreeCanBeReorderedAfterTheMigration() = runBlocking {
        createVersion7Database()
        val db = openWithRoom()
        val ordering = CatalogOrderingService(db)

        // A parent drags the imported container below the direct video.
        assertTrue(ordering.move("i-cocomelon", "cat-cartoon", 1))

        assertEquals(
            listOf("i-halloween", "i-cocomelon", "i-hidden"),
            ordering.childrenOf("cat-cartoon").map { it.id },
        )
        assertEquals(listOf(0, 1, 2), ordering.childrenOf("cat-cartoon").map { it.position })
        // and the tree is still readable through the node DAO's own ordering
        assertEquals(
            listOf("i-halloween", "i-cocomelon", "i-hidden"),
            db.catalogNodeDao().childrenOf("cat-cartoon").map { it.id },
        )
    }
}
