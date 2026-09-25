package tv.safetubeforkids.app.data.catalog

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
import tv.safetubeforkids.app.data.cache.KioskConfigEntity
import tv.safetubeforkids.app.data.cache.TimeLimitConfigEntity
import tv.safetubeforkids.app.data.cache.VideoEntity
import tv.safetubeforkids.app.data.events.PlayEventEntity
import tv.safetubeforkids.app.playback.PlaybackApproval
import tv.safetubeforkids.app.playback.PlaybackAuthorization

/**
 * CAT-DB-09 / CAT-DB-10: version 6 -> 7 -> 8 -> 9 (the whole upgrade path a real installation takes).
 *
 * The test builds a real version-6 database on real SQLite and then opens it with Room at the current
 * version and the chained migrations, so the migrations are the only thing that can produce a working
 * database. Version 8 -> 9 is the storage swap's last step: it drops the obsolete `categories` /
 * `content_items` tables, so the assertions that used to describe *those* tables now describe
 * `catalog_nodes` - and prove the removed ones are really gone.
 *
 * The version-6 DDL below is not guessed. It is the DDL Room itself generates for the seven
 * pre-existing tables - the `CREATE TABLE` / `CREATE ... INDEX` statements in Room's own
 * `app/build/generated/ksp/debug/kotlin/.../CacheDatabase_Impl.kt`, which are unchanged from
 * version 6 because Phase 2 only adds tables. Schema export is off (see [CacheDatabase]), so there
 * is no `app/schemas` JSON to read it from; the generated implementation is the same source of
 * truth.
 *
 * That fidelity matters because Room validates every table, index and foreign key during
 * `onUpgrade` and throws `Migration didn't properly handle ...` on any mismatch - so this test also
 * proves the migration produced exactly the schema Room expects, for the new tables as well as the
 * untouched ones.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogMigrationTest {

    private val dbName = "catalog-migration-test.db"
    private lateinit var context: Context
    private var room: CacheDatabase? = null

    // Seeded pre-migration values, asserted after the migration.
    private val approvedSourceId = "PLapproved123"
    private val approvedVideoId = "DuXwFlL8Usk"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        room?.close()
        context.deleteDatabase(dbName)
    }

    /** The exact version-6 schema, plus one row of pre-existing SafeTube data per table. */
    private fun createVersion6Database() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // --- the seven tables that exist before Phase 2, exactly as Room describes them
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

                    // --- pre-existing SafeTube data that must survive the migration
                    db.execSQL(
                        "INSERT INTO channels (source_type, source_id, source_url, display_name, video_count, added_at, status) " +
                            "VALUES ('yt_playlist', '$approvedSourceId', " +
                            "'https://www.youtube.com/playlist?list=$approvedSourceId', 'Approved Cartoons', 1, 1700000000000, 'active')"
                    )
                    db.execSQL(
                        "INSERT INTO videos (videoId, playlistId, title, thumbnailUrl, durationSeconds, position) " +
                            "VALUES ('$approvedVideoId', '$approvedSourceId', 'Approved Video', 'thumb', 123, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO play_events (videoId, playlistId, startedAt, durationSec, completedPct, title) " +
                            "VALUES ('$approvedVideoId', '$approvedSourceId', 1700000001000, 60, 50, 'Approved Video')"
                    )
                    db.execSQL(
                        "INSERT INTO playback_positions (videoId, positionMs, durationMs, updatedAt) " +
                            "VALUES ('$approvedVideoId', 30000, 123000, 1700000002000)"
                    )
                    db.execSQL(
                        "INSERT INTO time_limit_config (id, mondayLimitMin, tuesdayLimitMin, wednesdayLimitMin, " +
                            "thursdayLimitMin, fridayLimitMin, saturdayLimitMin, sundayLimitMin, bedtimeStartMin, " +
                            "bedtimeEndMin, manuallyLocked, bonusMinutes, bonusDate) " +
                            "VALUES (1, 60, -1, -1, -1, -1, 120, 120, 1140, 420, 0, 15, '2026-02-18')"
                    )
                    db.execSQL("INSERT INTO kiosk_config (id, kioskEnabled, enforceTimeLimitsOnAllApps) VALUES (1, 1, 1)")
                    db.execSQL(
                        "INSERT INTO app_whitelist (package_name, display_name, whitelisted, added_at) " +
                            "VALUES ('org.example.kidapp', 'Kid App', 1, 1700000003000)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            assertEquals("the fixture must really be a version 6 database", 6, db.version)
            // Mirrors what Room does on open, so the seed is subject to the same constraints.
            db.execSQL("PRAGMA foreign_keys = ON")
        } finally {
            helper.close()
        }
    }

    private fun openWithRoom(): CacheDatabase {
        val opened = Room.databaseBuilder(context, CacheDatabase::class.java, dbName)
            .addMigrations(
                CacheDatabase.MIGRATION_6_7,
                CacheDatabase.MIGRATION_7_8,
                CacheDatabase.MIGRATION_8_9,
            )
            .allowMainThreadQueries()
            .build()
        room = opened
        return opened
    }

    private fun SupportSQLiteDatabase.firstColumnOf(sql: String): List<String> =
        query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    /** `PRAGMA table_info` returns cid/name/type/notnull/dflt_value/pk, so name it explicitly. */
    private fun SupportSQLiteDatabase.tableColumns(table: String): List<String> =
        firstColumnOf("SELECT name FROM pragma_table_info('$table') ORDER BY cid")

    // ------------------------------------------------------------------ CAT-DB-09

    @Test
    fun version6DatabaseMigratesToTheCurrentVersion() {
        createVersion6Database()

        val db = openWithRoom()
        val writable = db.openHelper.writableDatabase

        assertEquals(
            "Room must have run MIGRATION_6_7, MIGRATION_7_8 and then MIGRATION_8_9",
            9,
            writable.version,
        )
    }

    @Test
    fun migrationLeavesTheNodeTreeAsTheOnlyCatalogTable() {
        createVersion6Database()
        val writable = openWithRoom().openHelper.writableDatabase

        val tables = writable.firstColumnOf("SELECT name FROM sqlite_master WHERE type = 'table'")

        assertTrue("catalog_nodes missing, tables: $tables", tables.contains("catalog_nodes"))
        assertTrue("catalog_metadata missing, tables: $tables", tables.contains("catalog_metadata"))
        // MIGRATION_8_9's whole job: the two tables the node tree replaced are gone.
        assertFalse("categories should have been dropped, tables: $tables", tables.contains("categories"))
        assertFalse("content_items should have been dropped, tables: $tables", tables.contains("content_items"))
        // and the pre-existing tables are still there
        listOf(
            "videos", "play_events", "channels", "time_limit_config",
            "kiosk_config", "app_whitelist", "playback_positions",
        ).forEach { assertTrue("$it was lost, tables: $tables", tables.contains(it)) }
    }

    @Test
    fun migratedColumnsIndicesAndForeignKeysMatchTheIntendedSchema() {
        createVersion6Database()
        val writable = openWithRoom().openHelper.writableDatabase

        val nodeColumns = writable.tableColumns("catalog_nodes")
        assertEquals(
            listOf(
                "id", "parent_id", "node_type", "title", "position", "enabled",
                "youtube_video_id", "youtube_playlist_id",
                "thumbnail_mode", "thumbnail_video_id", "thumbnail_url",
                "created_at", "updated_at",
            ),
            nodeColumns,
        )

        val metadataColumns = writable.tableColumns("catalog_metadata")
        assertEquals(
            listOf(
                "id", "catalog_version", "server_version",
                "last_successful_sync_at", "last_attempt_at",
            ),
            metadataColumns,
        )

        // varchar primary key
        val nodePk = writable.firstColumnOf(
            "SELECT name FROM pragma_table_info('catalog_nodes') WHERE pk > 0"
        )
        assertEquals(listOf("id"), nodePk)

        // The one ordering rule: the children of one parent, in position order.
        val nodeIndices = writable.firstColumnOf("SELECT name FROM pragma_index_list('catalog_nodes')")
        assertTrue(
            "index_catalog_nodes_parent_id_position missing, got $nodeIndices",
            nodeIndices.contains("index_catalog_nodes_parent_id_position"),
        )
        assertEquals(
            listOf("parent_id", "position"),
            writable.firstColumnOf(
                "SELECT name FROM pragma_index_info('index_catalog_nodes_parent_id_position') ORDER BY seqno"
            ),
        )

        // The self-referencing foreign key that keeps a child from outliving its parent.
        writable.query("PRAGMA foreign_key_list(`catalog_nodes`)").use { c ->
            assertTrue("catalog_nodes has no foreign key", c.moveToFirst())
            assertEquals("catalog_nodes", c.getString(c.getColumnIndexOrThrow("table")))
            assertEquals("parent_id", c.getString(c.getColumnIndexOrThrow("from")))
            assertEquals("id", c.getString(c.getColumnIndexOrThrow("to")))
            assertEquals("CASCADE", c.getString(c.getColumnIndexOrThrow("on_delete")))
            assertEquals(1, c.count)
        }
    }

    // ------------------------------------------------------------------ CAT-DB-10

    @Test
    fun existingSafeTubeDataSurvivesTheMigration() = runBlocking {
        createVersion6Database()
        val db = openWithRoom()

        // Approved source + approved video, read back through the real DAOs.
        val source = db.channelDao().getBySourceId(approvedSourceId)
        assertNotNull("the approved source was lost", source)
        assertEquals("Approved Cartoons", source!!.displayName)
        assertEquals("active", source.status)
        assertEquals(1, source.videoCount)

        val video = db.videoDao().getByVideoId(approvedVideoId)
        assertNotNull("the approved video was lost", video)
        assertEquals("Approved Video", video!!.title)
        assertEquals(approvedSourceId, video.playlistId)
        assertEquals(123L, video.durationSeconds)
        assertEquals(1, db.videoDao().count())

        // Resume position
        val position = db.playbackPositionDao().get(approvedVideoId)
        assertNotNull("the resume position was lost", position)
        assertEquals(30_000L, position!!.positionMs)
        assertEquals(123_000L, position.durationMs)

        // Playback history
        val events = db.playEventDao().let { dao ->
            db.openHelper.readableDatabase.query("SELECT videoId, title FROM play_events").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
            }
        }
        assertEquals(listOf(approvedVideoId to "Approved Video"), events)

        // Settings
        val limits = db.timeLimitDao().getConfig()
        assertNotNull("time limits were lost", limits)
        assertEquals(60, limits!!.mondayLimitMin)
        assertEquals(1140, limits.bedtimeStartMin)
        assertEquals(15, limits.bonusMinutes)
        assertEquals("2026-02-18", limits.bonusDate)

        val kiosk = db.kioskDao().getConfig()
        assertNotNull("kiosk config was lost", kiosk)
        assertTrue(kiosk!!.kioskEnabled)
        assertTrue(kiosk.enforceTimeLimitsOnAllApps)

        val whitelist = db.whitelistDao().getAll()
        assertEquals(1, whitelist.size)
        assertEquals("org.example.kidapp", whitelist[0].packageName)
        assertTrue(whitelist[0].whitelisted)
    }

    @Test
    fun theSecurityBoundaryStillApprovesTheMigratedApprovedVideo() = runBlocking {
        createVersion6Database()
        val db = openWithRoom()

        // PlaybackAuthorization is untouched by Phase 2; this proves the data it depends on still
        // resolves after the migration.
        val approval = PlaybackAuthorization.authorize(db, approvedVideoId)
        assertTrue("expected Approved, got $approval", approval is PlaybackApproval.Approved)
        assertEquals(approvedVideoId, (approval as PlaybackApproval.Approved).video.videoId)
        assertEquals(approvedSourceId, approval.sourceId)

        assertEquals(
            listOf(approvedVideoId),
            PlaybackAuthorization.approvedQueue(db, approvedSourceId).map { it.videoId },
        )

        // and an unapproved video is still refused
        assertTrue(PlaybackAuthorization.authorize(db, "notApproved") is PlaybackApproval.Rejected)
    }

    // ------------------------------------------- the new schema works after a migration

    @Test
    fun catalogRowsCanBeWrittenAndReadBackAfterTheMigration() = runBlocking {
        createVersion6Database()
        val db = openWithRoom()
        val repo = CatalogRepository(db)

        assertEquals(
            CatalogWriteResult.Written,
            repo.upsertCategories(
                listOf(
                    CategoryEntity("cat-music", "Music", 1),
                    CategoryEntity("cat-cartoon", "Cartoon", 0),
                )
            ),
        )
        assertEquals(
            CatalogWriteResult.Written,
            repo.upsertItems(
                listOf(
                    ContentItemEntity("i-abc", "cat-music", ContentItemType.PLAYLIST, "ABC Songs", 1, youtubePlaylistId = "PLabc"),
                    ContentItemEntity("i-nursery", "cat-music", ContentItemType.PLAYLIST, "Nursery Songs", 0, youtubePlaylistId = "PLnursery"),
                    ContentItemEntity("i-twinkle", "cat-music", ContentItemType.VIDEO, "Twinkle Twinkle", 2, youtubeVideoId = approvedVideoId),
                )
            ),
        )

        assertEquals(listOf("Cartoon", "Music"), repo.getCategories().map { it.displayName })
        assertEquals(
            listOf("Nursery Songs", "ABC Songs", "Twinkle Twinkle"),
            repo.getItems("cat-music").map { it.displayName },
        )
        assertEquals(
            listOf(ContentItemType.PLAYLIST, ContentItemType.PLAYLIST, ContentItemType.VIDEO),
            repo.getItems("cat-music").map { it.type },
        )

        repo.markSyncSucceeded(catalogVersion = 3L, syncedAt = 1_700_000_500_000L)
        assertEquals(3L, repo.getMetadata()!!.catalogVersion)
        assertEquals(1_700_000_500_000L, repo.getMetadata()!!.lastSuccessfulSyncAt)

        // The migrated database still enforces the foreign key on the new table.
        assertEquals(
            listOf("Nursery Songs", "ABC Songs", "Twinkle Twinkle"),
            repo.getItems("cat-music").map { it.displayName },
        )
        repo.deleteCategory("cat-music")
        assertEquals(0, repo.getCategories().sumOf { repo.getItems(it.id).size })
        assertEquals("Stories-style empty shelf is unaffected", 0, repo.getItems("cat-cartoon").size)
    }

    @Test
    fun reOpeningTheMigratedDatabaseDoesNotMigrateAgain() {
        createVersion6Database()
        openWithRoom().openHelper.writableDatabase.version
        room?.close()

        // A second open is a plain open at the current version: Room re-validates the schema identity.
        val reopened = openWithRoom()
        assertEquals(9, reopened.openHelper.writableDatabase.version)

        // The reopen is also where a dropped table would come back if the entity list still declared
        // it, so this is the guard that the swap is permanent rather than a one-off DROP.
        val tables = reopened.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue("catalog_nodes is the catalog storage, tables: $tables", tables.contains("catalog_nodes"))
        assertFalse("categories came back on reopen, tables: $tables", tables.contains("categories"))
        assertFalse("content_items came back on reopen, tables: $tables", tables.contains("content_items"))

        // And a reopened, migrated database is a working catalog database.
        runBlocking {
            val repo = CatalogRepository(reopened)
            repo.upsertCategory(CategoryEntity("cat-reopened", "Reopened", 0))
            assertEquals(listOf("Reopened"), repo.getCategories().map { it.displayName })
        }
    }

    @Test
    fun kioskAndTimeLimitEntitiesStillRoundTripAfterMigration() = runBlocking {
        createVersion6Database()
        val db = openWithRoom()

        db.timeLimitDao().insertOrUpdate(TimeLimitConfigEntity(mondayLimitMin = 90, manuallyLocked = true))
        assertEquals(90, db.timeLimitDao().getConfig()!!.mondayLimitMin)
        assertTrue(db.timeLimitDao().getConfig()!!.manuallyLocked)

        db.kioskDao().insertOrUpdate(KioskConfigEntity(kioskEnabled = false))
        assertEquals(false, db.kioskDao().getConfig()!!.kioskEnabled)

        db.videoDao().insertAll(listOf(VideoEntity("extra", approvedSourceId, "Extra", "t", 5, 1)))
        assertEquals(2, db.videoDao().count())

        db.playbackPositionDao().upsert(
            tv.safetubeforkids.app.data.cache.PlaybackPositionEntity("extra", 1, 2, 3)
        )
        assertNotNull(db.playbackPositionDao().get("extra"))

        db.playEventDao().insert(PlayEventEntity(videoId = "extra", playlistId = approvedSourceId, startedAt = 1))
        assertEquals(2, db.playEventDao().count())
    }
}
