package tv.safetubeforkids.app.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The JSON boundary itself, for contract version 2 - the node tree.
 *
 * The server and the TV only ever meet through this text, so these tests assert against real
 * serialized JSON - the exact keys, the exact null handling - rather than against Kotlin objects
 * that happen to be equal.
 *
 * The fixture is deliberately a *recursive* tree rather than the two levels version 1 could
 * describe: a shelf, a container inside it with its own videos, and a direct video beside the
 * container. If the document could carry only one of those shapes, the TV could not reconstruct what
 * the parent configured.
 */
class CatalogContractTest {

    private val fullCatalog = CatalogSnapshot(
        schemaVersion = CATALOG_SCHEMA_VERSION,
        catalogVersion = 12L,
        nodes = listOf(
            node("category-cartoon", nodeType = CATALOG_NODE_TYPE_CATEGORY, title = "Cartoon", position = 0),
            node(
                "item-cocomelon",
                parentId = "category-cartoon",
                nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
                title = "Cocomelon",
                position = 0,
                youtubePlaylistId = "PLcocomelon123",
            ),
            node(
                "item-halloween",
                parentId = "category-cartoon",
                nodeType = CATALOG_NODE_TYPE_VIDEO,
                title = "Halloween Special",
                position = 1,
                youtubeVideoId = "vidhalloween",
            ),
            // The container's own children: the third level, which version 1 had no way to express.
            node(
                "item-cocomelon#vidA",
                parentId = "item-cocomelon",
                nodeType = CATALOG_NODE_TYPE_VIDEO,
                title = "Episode A",
                position = 0,
                youtubeVideoId = "vidA",
                youtubePlaylistId = "PLcocomelon123",
                thumbnailMode = CATALOG_THUMBNAIL_MODE_VIDEO,
                thumbnailVideoId = "vidA",
            ),
            node(
                "item-cocomelon#vidB",
                parentId = "item-cocomelon",
                nodeType = CATALOG_NODE_TYPE_VIDEO,
                title = "Episode B",
                position = 1,
                youtubeVideoId = "vidB",
                youtubePlaylistId = "PLcocomelon123",
            ),
            node("category-music", nodeType = CATALOG_NODE_TYPE_CATEGORY, title = "Music", position = 1),
            node(
                "item-nursery",
                parentId = "category-music",
                nodeType = CATALOG_NODE_TYPE_SUBCATEGORY,
                title = "Nursery Songs",
                position = 0,
                youtubePlaylistId = "PLsuperFunEducationalSongs2026Official",
            ),
            node(
                "item-twinkle",
                parentId = "category-music",
                nodeType = CATALOG_NODE_TYPE_VIDEO,
                title = "Twinkle Twinkle",
                position = 1,
                youtubeVideoId = "DuXwFlL8Usk",
            ),
            node(
                "category-stories",
                nodeType = CATALOG_NODE_TYPE_CATEGORY,
                title = "Stories",
                position = 2,
                enabled = false,
            ),
        ),
    )

    private fun node(
        id: String,
        nodeType: String,
        title: String,
        position: Int,
        parentId: String? = null,
        enabled: Boolean = true,
        youtubeVideoId: String? = null,
        youtubePlaylistId: String? = null,
        thumbnailMode: String = CATALOG_THUMBNAIL_MODE_AUTO,
        thumbnailVideoId: String? = null,
    ) = CatalogNodeDto(
        id = id,
        parentId = parentId,
        nodeType = nodeType,
        title = title,
        position = position,
        enabled = enabled,
        youtubeVideoId = youtubeVideoId,
        youtubePlaylistId = youtubePlaylistId,
        thumbnailMode = thumbnailMode,
        thumbnailVideoId = thumbnailVideoId,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_500L,
    )

    @Test
    fun aFullCatalogSurvivesTheJsonRoundTrip() {
        val text = CatalogJson.encode(fullCatalog)

        val decoded = CatalogJson.decodeSnapshot(text)

        assertEquals(fullCatalog, decoded)
    }

    @Test
    fun theEncodedDocumentCarriesEveryFieldTheContractPromises() {
        val text = CatalogJson.encode(fullCatalog)

        listOf(
            "\"schemaVersion\": 2",
            "\"catalogVersion\": 12",
            "\"nodes\": [",
            "\"id\": \"category-cartoon\"",
            "\"nodeType\": \"CATEGORY\"",
            "\"nodeType\": \"SUBCATEGORY\"",
            "\"nodeType\": \"VIDEO\"",
            "\"title\": \"Cartoon\"",
            "\"position\": 0",
            "\"position\": 2",
            "\"parentId\": \"item-cocomelon\"",
            "\"youtubePlaylistId\": \"PLcocomelon123\"",
            "\"youtubeVideoId\": \"DuXwFlL8Usk\"",
            "\"thumbnailMode\": \"VIDEO\"",
            "\"thumbnailVideoId\": \"vidA\"",
            "\"createdAt\": 1700000000000",
            "\"updatedAt\": 1700000000500",
            "\"enabled\": true",
            "\"enabled\": false",
        ).forEach { fragment ->
            assertTrue("JSON is missing $fragment in:\n$text", text.contains(fragment))
        }
    }

    @Test
    fun theIdentifierANodeDoesNotUseIsExplicitlyNullOnTheWire() {
        val text = CatalogJson.encode(fullCatalog)

        // Asserted on the parsed document rather than on formatting, but on the *raw* element tree: a
        // container is visibly not a video, a video is visibly not a container, and a shelf is visibly
        // at ROOT rather than merely missing its parent. Without an explicit null the two sides could
        // disagree about which field is absent rather than null.
        val root = Json.parseToJsonElement(text).jsonObject
        val nodes = root["nodes"]!!.jsonArray.map { it.jsonObject }
        val byId = nodes.associateBy { it["id"]!!.jsonPrimitive.content }

        val container = byId.getValue("item-cocomelon")
        assertEquals("PLcocomelon123", container["youtubePlaylistId"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, container["youtubeVideoId"])

        val video = byId.getValue("item-twinkle")
        assertEquals("DuXwFlL8Usk", video["youtubeVideoId"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, video["youtubePlaylistId"])

        val shelf = byId.getValue("category-cartoon")
        assertEquals(JsonNull, shelf["parentId"])

        // And the nulls really are written out, not omitted.
        assertTrue(text.contains("\"youtubeVideoId\": null"))
        assertTrue(text.contains("\"youtubePlaylistId\": null"))
        assertTrue(text.contains("\"parentId\": null"))
        assertTrue(text.contains("\"thumbnailVideoId\": null"))
    }

    @Test
    fun theRecursiveTreeSurvivesTheRoundTrip() {
        val decoded = CatalogJson.decodeSnapshot(CatalogJson.encode(fullCatalog))!!

        // The three levels the tree has to be able to describe, read back through parent links.
        val byId = decoded.nodes.associateBy { it.id }

        val container = byId.getValue("item-cocomelon")
        assertEquals(CATALOG_NODE_TYPE_SUBCATEGORY, container.nodeType)
        assertEquals("category-cartoon", container.parentId)

        val episode = byId.getValue("item-cocomelon#vidA")
        assertEquals(CATALOG_NODE_TYPE_VIDEO, episode.nodeType)
        assertEquals("item-cocomelon", episode.parentId)
        // Provenance survives: an imported episode remembers the playlist it came from.
        assertEquals("PLcocomelon123", episode.youtubePlaylistId)

        // Mixed children: the container and the direct video are siblings of the same shelf.
        assertEquals(
            listOf("item-cocomelon", "item-halloween"),
            decoded.nodes.filter { it.parentId == "category-cartoon" }.sortedBy { it.position }.map { it.id },
        )

        // Every non-root node names a parent that is in the same document.
        val ids = decoded.nodes.map { it.id }.toSet()
        decoded.nodes.filter { it.parentId != null }.forEach {
            assertTrue("'${it.id}' names missing parent '${it.parentId}'", ids.contains(it.parentId))
        }
    }

    @Test
    fun anEmptyCatalogRoundTripsAsAnEmptyListNotAsAMissingKey() {
        val text = CatalogJson.encode(emptyCatalogSnapshot())

        assertTrue(text.contains("\"nodes\": []"))
        val decoded = CatalogJson.decodeSnapshot(text)!!
        assertEquals(CATALOG_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(0L, decoded.catalogVersion)
        assertTrue(decoded.nodes.isEmpty())
    }

    @Test
    fun aContainerWithNoChildrenDecodesAsAContainerWithoutChildren() {
        // A configured playlist that has brought nothing in yet is legitimate: the container exists,
        // and no node names it as its parent.
        val text =
            """{"schemaVersion":2,"catalogVersion":4,"nodes":[""" +
                """{"id":"cat-music","parentId":null,"nodeType":"CATEGORY","title":"Music","position":0},""" +
                """{"id":"i-nursery","parentId":"cat-music","nodeType":"SUBCATEGORY","title":"Nursery","position":0,"youtubePlaylistId":"PLnursery"}]}"""

        val decoded = CatalogJson.decodeSnapshot(text)!!

        assertEquals(2, decoded.nodes.size)
        assertEquals(
            "nothing is imported into the container yet",
            emptyList<String>(),
            decoded.nodes.filter { it.parentId == "i-nursery" }.map { it.id },
        )
    }

    @Test
    fun anEmptyJsonObjectIsNotReadableAsACatalog() {
        // This is the malformed payload that must never be mistaken for "the parent wants nothing".
        assertNull(CatalogJson.decodeSnapshot("{}"))
    }

    @Test
    fun aMissingSchemaVersionOrNodesKeyIsNotReadableAsACatalog() {
        assertNull(CatalogJson.decodeSnapshot("""{"catalogVersion":20,"nodes":[]}"""))
        assertNull(CatalogJson.decodeSnapshot("""{"schemaVersion":2,"catalogVersion":20}"""))
    }

    @Test
    fun aNodeMissingARequiredFieldIsNotReadableAsACatalog() {
        // A node without an id, a type, a title or a position is not a node, and completing it with a
        // default would be silently repairing the parent's document.
        listOf(
            """{"parentId":null,"nodeType":"CATEGORY","title":"Cartoon","position":0}""",
            """{"id":"c1","parentId":null,"title":"Cartoon","position":0}""",
            """{"id":"c1","parentId":null,"nodeType":"CATEGORY","position":0}""",
            """{"id":"c1","parentId":null,"nodeType":"CATEGORY","title":"Cartoon"}""",
        ).forEach { incomplete ->
            val text = """{"schemaVersion":2,"catalogVersion":1,"nodes":[$incomplete]}"""
            assertNull("should not decode: $text", CatalogJson.decodeSnapshot(text))
        }
    }

    @Test
    fun unreadableBodiesAreRejectedRatherThanGuessedAt() {
        listOf(
            "",
            "   ",
            "not json",
            "[]",
            "null",
            "42",
            "<html><body>502 Bad Gateway</body></html>",
            """{"schemaVersion":"two","catalogVersion":1,"nodes":[]}""",
        ).forEach { body ->
            assertNull("should not decode: '$body'", CatalogJson.decodeSnapshot(body))
        }
    }

    @Test
    fun unknownExtraKeysAreIgnoredSoTheContractCanGrow() {
        val text = """{"schemaVersion":2,"catalogVersion":3,"nodes":[],"somethingNew":true}"""

        val decoded = CatalogJson.decodeSnapshot(text)!!

        assertEquals(3L, decoded.catalogVersion)
    }

    @Test
    fun aVersion1DocumentIsNotReadableAsAVersion2Catalog() {
        // The wire shapes are different documents. A version-1 body has no `nodes`, so it fails to
        // parse rather than being interpreted - which is what stops a stale client's publish from
        // silently becoming a tree it never described.
        val version1 = """
            {"schemaVersion":1,"catalogVersion":7,"categories":[
              {"id":"cat-cartoon","displayName":"Cartoon","sortOrder":0,"enabled":true,"items":[
                {"id":"i-cocomelon","type":"PLAYLIST","displayName":"Cocomelon","sortOrder":0,
                 "youtubePlaylistId":"PLcocomelon123","youtubeVideoId":null,"enabled":true}]}]}
        """.trimIndent()

        assertNull(CatalogJson.decodeSnapshot(version1))
        // It does still decode as a *stored* document, which is a different question with a different
        // answer - see CatalogLegacyDocumentTest.
        assertTrue(CatalogJson.decodeStoredDocument(version1) is CatalogJson.StoredDocument.UpgradedFromVersion1)
    }

    @Test
    fun aPutRequestCarriesNoAuthoritativeVersion() {
        val request = CatalogPutRequest(
            schemaVersion = CATALOG_SCHEMA_VERSION,
            expectedCatalogVersion = 10L,
            nodes = fullCatalog.nodes,
        )

        val text = CatalogJson.encodePutRequest(request)

        assertTrue(text.contains("\"expectedCatalogVersion\": 10"))
        assertTrue(
            "the request must not offer a field the server would have to ignore",
            !text.contains("\"catalogVersion\""),
        )
        assertEquals(request, CatalogJson.decodePutRequest(text))
    }

    @Test
    fun aPutRequestBodyThatTriesToSetAVersionStillCarriesNoVersion() {
        // A client attempting to dictate the version has nowhere to put it: the field is not part of
        // the request type, and unknown keys are ignored rather than honoured.
        val body = """{"schemaVersion":2,"catalogVersion":999999,"nodes":[]}"""

        val decoded = CatalogJson.decodePutRequest(body)

        assertNotNull(decoded)
        assertEquals(CATALOG_SCHEMA_VERSION, decoded!!.schemaVersion)
        assertNull(decoded.expectedCatalogVersion)
        assertTrue(decoded.nodes.isEmpty())
    }

    @Test
    fun aPutRequestWithoutNodesIsNotReadable() {
        assertNull(CatalogJson.decodePutRequest("{}"))
        assertNull(CatalogJson.decodePutRequest("""{"schemaVersion":2}"""))
        assertNull(CatalogJson.decodePutRequest("""{"nodes":[]}"""))
    }

    @Test
    fun theEncoderAndDecoderAreTheSameConfigurationBothWays() {
        // Round-tripping through the shared serializer twice must be stable, which is what stops the
        // server and the TV drifting apart on defaults or nulls.
        val once = CatalogJson.encode(fullCatalog)
        val twice = CatalogJson.encode(CatalogJson.decodeSnapshot(once)!!)

        assertEquals(once, twice)
    }

    @Test
    fun theSchemaVersionIsTwoAndTheWireEnumsAreTheStoredOnes() {
        // The version is a number on the wire, and the values the tree stores are the values the wire
        // carries - one spelling, decided in one place.
        assertEquals(2, CATALOG_SCHEMA_VERSION)
        assertEquals(CATALOG_SCHEMA_VERSION, fullCatalog.schemaVersion)
        assertEquals(
            listOf("CATEGORY", "SUBCATEGORY", "VIDEO"),
            CatalogNodeType.entries.map { CatalogNodeWire.wireNameOf(it) },
        )
        assertEquals(
            listOf("AUTO", "VIDEO", "CUSTOM"),
            ThumbnailMode.entries.map { CatalogNodeWire.wireNameOf(it) },
        )
        CatalogNodeType.entries.forEach {
            assertEquals(it, CatalogNodeWire.nodeTypeOf(CatalogNodeWire.wireNameOf(it)))
        }
        ThumbnailMode.entries.forEach {
            assertEquals(it, CatalogNodeWire.thumbnailModeOf(CatalogNodeWire.wireNameOf(it)))
        }
        assertNull(CatalogNodeWire.nodeTypeOf("CHANNEL"))
        assertNull(CatalogNodeWire.thumbnailModeOf("PROVIDED"))

        // The encoded document really says 2, so an old client cannot mistake it for version 1.
        val root = Json.parseToJsonElement(CatalogJson.encode(fullCatalog)).jsonObject
        assertEquals(2, root["schemaVersion"]!!.jsonPrimitive.int)
    }
}
