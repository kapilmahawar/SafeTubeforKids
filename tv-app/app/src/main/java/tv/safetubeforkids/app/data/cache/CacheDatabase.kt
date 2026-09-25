package tv.safetubeforkids.app.data.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import tv.safetubeforkids.app.data.catalog.CatalogMetadataDao
import tv.safetubeforkids.app.data.catalog.CatalogNodeDao
import tv.safetubeforkids.app.data.catalog.CatalogNodeEntity
import tv.safetubeforkids.app.data.catalog.CatalogMetadataEntity
import tv.safetubeforkids.app.data.catalog.CategoryDao
import tv.safetubeforkids.app.data.catalog.CategoryEntity
import tv.safetubeforkids.app.data.catalog.ContentItemDao
import tv.safetubeforkids.app.data.catalog.ContentItemEntity
import tv.safetubeforkids.app.data.events.PlayEventDao
import tv.safetubeforkids.app.data.events.PlayEventEntity

@Database(
    entities = [VideoEntity::class, PlayEventEntity::class, ChannelEntity::class, TimeLimitConfigEntity::class, KioskConfigEntity::class, WhitelistEntity::class, PlaybackPositionEntity::class, CategoryEntity::class, ContentItemEntity::class, CatalogMetadataEntity::class, CatalogNodeEntity::class],
    version = 8,
    // Schema export stays off. Turning it on makes Room's processor serialise the schema bundle,
    // and on this project that crashes a clean `kspDebugKotlin`:
    //   java.lang.AbstractMethodError: Receiver class
    //   androidx.room.migration.bundle.FieldBundle$$serializer ... does not define or inherit
    //   'abstract kotlinx.serialization.KSerializer[] typeParametersSerializers()'
    // because KSP puts the module's compile classpath (kotlinx-serialization 1.8.0, via Ktor) and
    // Room's processor classpath (room-migration 2.8.4 wants 1.8.1) on one classloader. Fixing it
    // means moving the app's serialization version, which is not Phase 2's business. The migration
    // is instead verified by CatalogMigrationTest against Room's own generated DDL.
    exportSchema = false,
)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun videoDao(): PlaylistCacheDao
    abstract fun playEventDao(): PlayEventDao
    abstract fun channelDao(): ChannelDao
    abstract fun timeLimitDao(): TimeLimitDao
    abstract fun kioskDao(): KioskDao
    abstract fun whitelistDao(): WhitelistDao
    abstract fun playbackPositionDao(): PlaybackPositionDao

    /** Local catalog (Phase 2): configuration only - see [CategoryEntity]. */
    abstract fun categoryDao(): CategoryDao
    abstract fun contentItemDao(): ContentItemDao
    abstract fun catalogMetadataDao(): CatalogMetadataDao

    /** The parent's content tree: one table, one ordering rule (parent_id + position). */
    abstract fun catalogNodeDao(): CatalogNodeDao

    companion object {
        @Volatile
        private var INSTANCE: CacheDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create playlists table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS playlists (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        youtube_playlist_id TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        added_at INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'active'
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_playlists_youtube_playlist_id ON playlists (youtube_playlist_id)")

                // Remove flushed column from play_events by recreating table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS play_events_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        videoId TEXT NOT NULL,
                        playlistId TEXT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        durationSec INTEGER NOT NULL DEFAULT 0,
                        completedPct INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("INSERT INTO play_events_new (id, videoId, playlistId, startedAt, durationSec, completedPct) SELECT id, videoId, playlistId, startedAt, durationSec, completedPct FROM play_events")
                db.execSQL("DROP TABLE play_events")
                db.execSQL("ALTER TABLE play_events_new RENAME TO play_events")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create channels table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS channels (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        source_type TEXT NOT NULL,
                        source_id TEXT NOT NULL,
                        source_url TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        video_count INTEGER NOT NULL DEFAULT 0,
                        added_at INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'active'
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_channels_source_id ON channels (source_id)")

                // 2. Migrate playlists → channels
                db.execSQL("""
                    INSERT INTO channels (source_type, source_id, source_url, display_name, added_at, status)
                    SELECT 'yt_playlist', youtube_playlist_id,
                           'https://www.youtube.com/playlist?list=' || youtube_playlist_id,
                           display_name, added_at, status
                    FROM playlists
                """.trimIndent())

                // 3. Update video counts from cached videos
                db.execSQL("""
                    UPDATE channels SET video_count = (
                        SELECT COUNT(*) FROM videos WHERE videos.playlistId = channels.source_id
                    )
                """.trimIndent())

                // 4. Add title column to play_events
                db.execSQL("ALTER TABLE play_events ADD COLUMN title TEXT NOT NULL DEFAULT ''")

                // 5. Backfill titles from videos table
                db.execSQL("""
                    UPDATE play_events SET title = COALESCE(
                        (SELECT videos.title FROM videos WHERE videos.videoId = play_events.videoId LIMIT 1),
                        ''
                    ) WHERE title = ''
                """.trimIndent())

                // 6. Drop old playlists table
                db.execSQL("DROP TABLE IF EXISTS playlists")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS time_limit_config (
                        id INTEGER NOT NULL PRIMARY KEY,
                        mondayLimitMin INTEGER NOT NULL DEFAULT -1,
                        tuesdayLimitMin INTEGER NOT NULL DEFAULT -1,
                        wednesdayLimitMin INTEGER NOT NULL DEFAULT -1,
                        thursdayLimitMin INTEGER NOT NULL DEFAULT -1,
                        fridayLimitMin INTEGER NOT NULL DEFAULT -1,
                        saturdayLimitMin INTEGER NOT NULL DEFAULT -1,
                        sundayLimitMin INTEGER NOT NULL DEFAULT -1,
                        bedtimeStartMin INTEGER NOT NULL DEFAULT -1,
                        bedtimeEndMin INTEGER NOT NULL DEFAULT -1,
                        manuallyLocked INTEGER NOT NULL DEFAULT 0,
                        bonusMinutes INTEGER NOT NULL DEFAULT 0,
                        bonusDate TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS kiosk_config (
                        id INTEGER NOT NULL PRIMARY KEY,
                        kioskEnabled INTEGER NOT NULL DEFAULT 0,
                        enforceTimeLimitsOnAllApps INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS app_whitelist (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        package_name TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        whitelisted INTEGER NOT NULL DEFAULT 0,
                        added_at INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_app_whitelist_package_name ON app_whitelist (package_name)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Resume positions: one row per approved video.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playback_positions (
                        videoId TEXT NOT NULL PRIMARY KEY,
                        positionMs INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Local catalog (Phase 2). Purely additive: no existing table is touched, so every
                // approved source, cached video, playback position, play event and setting survives.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `categories` (
                        `id` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `sort_order` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_sort_order` ON `categories` (`sort_order`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `content_items` (
                        `id` TEXT NOT NULL,
                        `category_id` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `sort_order` INTEGER NOT NULL,
                        `youtube_playlist_id` TEXT,
                        `youtube_video_id` TEXT,
                        `enabled` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_items_category_id_sort_order` ON `content_items` (`category_id`, `sort_order`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_metadata` (
                        `id` INTEGER NOT NULL,
                        `catalog_version` INTEGER NOT NULL,
                        `server_version` INTEGER,
                        `last_successful_sync_at` INTEGER,
                        `last_attempt_at` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Version 7 -> 8: categories + content_items become one tree of catalog nodes.
         *
         * Purely additive to the schema - every pre-existing table is left exactly as it was, so all
         * approved sources, cached videos, resume positions, watch history and settings survive - and
         * the conversion preserves the parent's configured order:
         *
         *  - each `categories` row becomes a CATEGORY node at ROOT, keeping its sort_order as position;
         *  - each VIDEO item becomes a VIDEO node under its category, keeping identity, title, order and
         *    its youtube_video_id;
         *  - each PLAYLIST item becomes a SUBCATEGORY node named as the parent named it (the card they
         *    saw), keeping the playlist id as its import source - which is what a playlist is in the
         *    tree: a way to bring videos in, never a child-facing tile;
         *  - the videos already cached for that playlist become that container's children, in cache
         *    order, each keeping its own youtube_video_id as the deduplication key.
         *
         * Child ids are derived (`<item id>#<video id>`) rather than invented, so running the conversion
         * is deterministic; INSERT OR REPLACE means a pathological id cannot abort the upgrade.
         *
         * Creating VIDEO nodes from the approved cache grants nothing: PlaybackAuthorization still reads
         * only `channels` and `videos`, so a migrated node is configuration, not permission.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_nodes` (
                        `id` TEXT NOT NULL,
                        `parent_id` TEXT,
                        `node_type` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `youtube_video_id` TEXT,
                        `youtube_playlist_id` TEXT,
                        `thumbnail_mode` TEXT NOT NULL,
                        `thumbnail_video_id` TEXT,
                        `thumbnail_url` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`parent_id`) REFERENCES `catalog_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_catalog_nodes_parent_id_position` " +
                        "ON `catalog_nodes` (`parent_id`, `position`)"
                )

                val nodeColumns =
                    "(`id`,`parent_id`,`node_type`,`title`,`position`,`enabled`,`youtube_video_id`," +
                        "`youtube_playlist_id`,`thumbnail_mode`,`thumbnail_video_id`,`thumbnail_url`," +
                        "`created_at`,`updated_at`)"

                // Categories keep their order.
                db.execSQL(
                    "INSERT OR REPLACE INTO `catalog_nodes` $nodeColumns " +
                        "SELECT `id`, NULL, 'CATEGORY', `display_name`, `sort_order`, `enabled`, " +
                        "NULL, NULL, 'AUTO', NULL, NULL, `created_at`, `updated_at` FROM `categories`"
                )

                // Direct videos keep their identity and position inside their category.
                db.execSQL(
                    "INSERT OR REPLACE INTO `catalog_nodes` $nodeColumns " +
                        "SELECT `id`, `category_id`, 'VIDEO', `display_name`, `sort_order`, `enabled`, " +
                        "`youtube_video_id`, NULL, 'AUTO', NULL, NULL, `created_at`, `updated_at` " +
                        "FROM `content_items` WHERE `type` = 'VIDEO'"
                )

                // Playlist entries become containers holding what the playlist brought in.
                db.execSQL(
                    "INSERT OR REPLACE INTO `catalog_nodes` $nodeColumns " +
                        "SELECT `id`, `category_id`, 'SUBCATEGORY', `display_name`, `sort_order`, `enabled`, " +
                        "NULL, `youtube_playlist_id`, 'AUTO', NULL, NULL, `created_at`, `updated_at` " +
                        "FROM `content_items` WHERE `type` = 'PLAYLIST'"
                )

                // The already-cached approved videos of that playlist, in cache order.
                db.execSQL(
                    "INSERT OR REPLACE INTO `catalog_nodes` $nodeColumns " +
                        "SELECT `ci`.`id` || '#' || `v`.`videoId`, `ci`.`id`, 'VIDEO', " +
                        "CASE WHEN `v`.`title` = '' THEN `v`.`videoId` ELSE `v`.`title` END, " +
                        "`v`.`position`, 1, `v`.`videoId`, `ci`.`youtube_playlist_id`, 'AUTO', NULL, NULL, " +
                        "`ci`.`created_at`, `ci`.`updated_at` " +
                        "FROM `content_items` `ci` JOIN `videos` `v` " +
                        "ON `v`.`playlistId` = `ci`.`youtube_playlist_id` " +
                        "WHERE `ci`.`type` = 'PLAYLIST'"
                )
            }
        }
        fun getInstance(context: Context): CacheDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CacheDatabase::class.java,
                    "parentapproved_cache"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun getInMemoryInstance(context: Context): CacheDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                CacheDatabase::class.java,
            ).allowMainThreadQueries().build()
        }

        internal fun setInstance(db: CacheDatabase) {
            INSTANCE = db
        }
    }
}
