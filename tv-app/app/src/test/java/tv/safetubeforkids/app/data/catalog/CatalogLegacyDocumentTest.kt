package tv.safetubeforkids.app.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored-document upgrade: reading the version-1 `catalog.json` an installation that has not
 * published since W2 still has on disk.
 *
 * The fixture is the **real document** a device's server had in its `filesDir`, copied verbatim from
 * the running TV rather than invented, because the whole value of this code path is that it reads
 * what is actually out there. The document is not a wire payload: a version-1 `PUT` is refused (see
 * [CatalogContractTest.aVersion1DocumentIsNotReadableAsAVersion2Catalog]), while a version-1
 * *stored* document is this server's own committed catalog and must not be thrown away.
 */
class CatalogLegacyDocumentTest {

    /** Exactly what a W1b device's `filesDir/catalog.json` contained. */
    private val storedVersion1Document = """
        {
            "schemaVersion": 1,
            "catalogVersion": 1,
            "categories": [
                {
                    "id": "cat-cartoon",
                    "displayName": "Cartoon",
                    "sortOrder": 0,
                    "enabled": true,
                    "items": [
                        {
                            "id": "i-cocomelon",
                            "type": "PLAYLIST",
                            "displayName": "CoComelon",
                            "sortOrder": 0,
                            "youtubePlaylistId": "PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT",
                            "youtubeVideoId": null,
                            "enabled": true
                        }
                    ]
                }
            ]
        }
    """.trimIndent()

    @Test
    fun theDocumentARealServerHasOnDiskIsUpgradedToTheNodeTree() {
        val snapshot = CatalogLegacyDocument.upgrade(storedVersion1Document)!!

        assertEquals(CATALOG_SCHEMA_VERSION, snapshot.schemaVersion)
        // The version is kept: the TV already holds this version, and a reset to 0 would look like a
        // regression to every installation showing it.
        assertEquals(1L, snapshot.catalogVersion)

        assertEquals(2, snapshot.nodes.size)
        val shelf = snapshot.nodes.single { it.id == "cat-cartoon" }
        assertEquals(CATALOG_NODE_TYPE_CATEGORY, shelf.nodeType)
        assertEquals("Cartoon", shelf.title)
        assertNull(shelf.parentId)
        assertEquals(0, shelf.position)
        assertTrue(shelf.enabled)

        val container = snapshot.nodes.single { it.id == "i-cocomelon" }
        assertEquals(CATALOG_NODE_TYPE_SUBCATEGORY, container.nodeType)
        assertEquals("CoComelon", container.title)
        assertEquals("cat-cartoon", container.parentId)
        assertEquals(0, container.position)
        assertEquals("PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT", container.youtubePlaylistId)
        assertNull("a container is not a video", container.youtubeVideoId)

        // A version-1 document carried no timestamps. Inventing "now" would make the same stored
        // document decode differently every time it is read, so they stay 0 until a publish stamps them.
        assertEquals(0L, shelf.createdAt)
        assertEquals(0L, shelf.updatedAt)
    }

    @Test
    fun theUpgradedTreeIsOneTheValidatorAccepts() {
        val snapshot = CatalogLegacyDocument.upgrade(storedVersion1Document)!!

        val outcome = CatalogPayloadValidator.validate(snapshot.nodes)

        assertTrue(
            "an upgraded stored document must be a valid version-2 tree, got: " +
                (outcome as? CatalogPayloadValidator.Outcome.Invalid)?.problems,
            outcome is CatalogPayloadValidator.Outcome.Valid,
        )
    }

    @Test
    fun siblingPositionsAreRenumberedToTheCanonicalContiguousOnes() {
        // A version-1 document could hold any sort_order values, including 0/10/20 and duplicates.
        val legacy = """
            {"schemaVersion":1,"catalogVersion":9,"categories":[
              {"id":"cat-cartoon","displayName":"Cartoon","sortOrder":10,"enabled":true,"items":[
                {"id":"i-cocomelon","type":"PLAYLIST","displayName":"Cocomelon","sortOrder":20,
                 "youtubePlaylistId":"PLcocomelon","youtubeVideoId":null,"enabled":true},
                {"id":"i-halloween","type":"VIDEO","displayName":"Halloween","sortOrder":5,
                 "youtubePlaylistId":null,"youtubeVideoId":"vidhalloween","enabled":true},
                {"id":"i-hidden","type":"VIDEO","displayName":"Hidden","sortOrder":5,
                 "youtubePlaylistId":null,"youtubeVideoId":"vidhidden","enabled":false}]},
              {"id":"cat-music","displayName":"Music","sortOrder":0,"enabled":true,"items":[]}]}
        """.trimIndent()

        val snapshot = CatalogLegacyDocument.upgrade(legacy)!!
        assertEquals(9L, snapshot.catalogVersion)

        // Shelves: Music (0) before Cartoon (10), renumbered 0 and 1.
        assertEquals(
            listOf("cat-music", "cat-cartoon"),
            snapshot.nodes.filter { it.parentId == null }.sortedBy { it.position }.map { it.id },
        )
        assertEquals(
            listOf(0, 1),
            snapshot.nodes.filter { it.parentId == null }.sortedBy { it.position }.map { it.position },
        )

        // Children: the two sharing sort_order 5 are ordered by id, which is the tree's tie-break, and
        // the container that asked for 20 comes last. 5/5/20 became 0/1/2.
        assertEquals(
            listOf("i-halloween", "i-hidden", "i-cocomelon"),
            snapshot.nodes.filter { it.parentId == "cat-cartoon" }.sortedBy { it.position }.map { it.id },
        )
        assertEquals(
            listOf(0, 1, 2),
            snapshot.nodes.filter { it.parentId == "cat-cartoon" }.sortedBy { it.position }.map { it.position },
        )
        assertEquals(false, snapshot.nodes.single { it.id == "i-hidden" }.enabled)
    }

    @Test
    fun aMixedShelfUpgradesIntoContainersAndVideos() {
        val snapshot = CatalogLegacyDocument.upgrade(storedVersion1Document)!!
        val container = snapshot.nodes.single { it.id == "i-cocomelon" }

        // A playlist is a way videos arrive, never a child-facing video: it becomes a container.
        assertEquals(CATALOG_NODE_TYPE_SUBCATEGORY, container.nodeType)
        // And the episodes it imported are not invented here: they live in the TV's approved cache,
        // which the server does not have, so the server's tree simply has no children under it.
        assertEquals(emptyList<String>(), snapshot.nodes.filter { it.parentId == container.id }.map { it.id })
    }

    @Test
    fun anEmptyVersion1DocumentUpgradesToAnEmptyTree() {
        val snapshot = CatalogLegacyDocument.upgrade(
            """{"schemaVersion":1,"catalogVersion":4,"categories":[]}"""
        )!!

        assertTrue(snapshot.nodes.isEmpty())
        assertEquals(4L, snapshot.catalogVersion)
    }

    @Test
    fun anUnusableVersion1DocumentIsNotUpgraded() {
        listOf(
            // not a version-1 document at all
            "{}",
            """{"schemaVersion":2,"catalogVersion":1,"nodes":[]}""",
            """{"catalogVersion":1,"categories":[]}""",
            "not json",
            // a category with no usable identity
            """{"schemaVersion":1,"catalogVersion":1,"categories":[{"displayName":"Cartoon","sortOrder":0}]}""",
            """{"schemaVersion":1,"catalogVersion":1,"categories":[{"id":"c1","displayName":" ","sortOrder":0}]}""",
            // an item type this build does not know
            """{"schemaVersion":1,"catalogVersion":1,"categories":[{"id":"c1","displayName":"Cartoon","sortOrder":0,
                "items":[{"id":"i1","type":"CHANNEL","displayName":"x","sortOrder":0}]}]}""".trimIndent(),
            // a playlist with no playlist id, and a video with no video id
            """{"schemaVersion":1,"catalogVersion":1,"categories":[{"id":"c1","displayName":"Cartoon","sortOrder":0,
                "items":[{"id":"i1","type":"PLAYLIST","displayName":"x","sortOrder":0,"youtubePlaylistId":null}]}]}""".trimIndent(),
            """{"schemaVersion":1,"catalogVersion":1,"categories":[{"id":"c1","displayName":"Cartoon","sortOrder":0,
                "items":[{"id":"i1","type":"VIDEO","displayName":"x","sortOrder":0,"youtubeVideoId":null}]}]}""".trimIndent(),
        ).forEach { document ->
            assertNull("should not upgrade: $document", CatalogLegacyDocument.upgrade(document))
        }
    }

    @Test
    fun aVersion2DocumentIsReadAsVersion2AndNotAsLegacy() {
        val stored = CatalogJson.encode(emptyCatalogSnapshot().copy(catalogVersion = 3L))

        val document = CatalogJson.decodeStoredDocument(stored)

        assertTrue(document is CatalogJson.StoredDocument.Version2)
        assertEquals(3L, (document as CatalogJson.StoredDocument.Version2).snapshot.catalogVersion)
    }

    @Test
    fun aDocumentFromANewerBuildIsNotSilentlyRead() {
        // A schema this build does not speak must not be decoded into part of a tree: reading it as
        // "nothing configured" leaves every TV holding its own catalog, which is the safe direction.
        val fromTheFuture = """{"schemaVersion":3,"catalogVersion":40,"tree":{"anything":true}}"""

        assertEquals(CatalogJson.StoredDocument.Unreadable, CatalogJson.decodeStoredDocument(fromTheFuture))
        // The same document also fails the version-1 reader, so it is never guessed at.
        assertNull(CatalogLegacyDocument.upgrade(fromTheFuture))
    }

    @Test
    fun garbageIsUnreadableRatherThanEmptyButValid() {
        listOf("", "   ", "not json", "[1,2,3]", "<html>502</html>").forEach { text ->
            assertEquals(
                "should be unreadable: '$text'",
                CatalogJson.StoredDocument.Unreadable,
                CatalogJson.decodeStoredDocument(text),
            )
        }
    }
}
