package tv.safetubeforkids.app.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The JSON boundary itself.
 *
 * The server and the TV only ever meet through this text, so these tests assert against real
 * serialized JSON - the exact keys, the exact null handling - rather than against Kotlin objects
 * that happen to be equal.
 */
class CatalogContractTest {

    private val fullCatalog = CatalogSnapshot(
        schemaVersion = CATALOG_SCHEMA_VERSION,
        catalogVersion = 12L,
        categories = listOf(
            CatalogCategoryDto(
                id = "category-cartoon",
                displayName = "Cartoon",
                sortOrder = 0,
                items = listOf(
                    CatalogItemDto(
                        id = "item-cocomelon",
                        type = CATALOG_TYPE_PLAYLIST,
                        displayName = "Cocomelon",
                        sortOrder = 0,
                        youtubePlaylistId = "PLcocomelon123",
                    ),
                ),
            ),
            CatalogCategoryDto(
                id = "category-music",
                displayName = "Music",
                sortOrder = 1,
                items = listOf(
                    CatalogItemDto(
                        id = "item-nursery",
                        type = CATALOG_TYPE_PLAYLIST,
                        displayName = "Nursery Songs",
                        sortOrder = 0,
                        youtubePlaylistId = "PLsuperFunEducationalSongs2026Official",
                    ),
                    CatalogItemDto(
                        id = "item-twinkle",
                        type = CATALOG_TYPE_VIDEO,
                        displayName = "Twinkle Twinkle",
                        sortOrder = 2,
                        youtubeVideoId = "DuXwFlL8Usk",
                    ),
                ),
            ),
            CatalogCategoryDto(
                id = "category-stories",
                displayName = "Stories",
                sortOrder = 3,
                enabled = false,
            ),
        ),
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
            "\"schemaVersion\": 1",
            "\"catalogVersion\": 12",
            "\"id\": \"category-cartoon\"",
            "\"displayName\": \"Cartoon\"",
            "\"sortOrder\": 0",
            "\"sortOrder\": 2",
            "\"type\": \"PLAYLIST\"",
            "\"type\": \"VIDEO\"",
            "\"id\": \"item-twinkle\"",
            "\"youtubePlaylistId\": \"PLcocomelon123\"",
            "\"youtubeVideoId\": \"DuXwFlL8Usk\"",
            "\"enabled\": true",
            "\"enabled\": false",
        ).forEach { fragment ->
            assertTrue("JSON is missing $fragment in:\n$text", text.contains(fragment))
        }
    }

    @Test
    fun theIdentifierAnItemDoesNotUseIsExplicitlyNullOnTheWire() {
        val text = CatalogJson.encode(fullCatalog)

        // Asserted on the parsed document rather than on formatting, but on the *raw* element tree:
        // a playlist item is visibly not a video item, and the other way round. Without an explicit
        // null the two sides could disagree about which field is absent rather than null.
        val root = Json.parseToJsonElement(text).jsonObject
        val categories = root["categories"]!!.jsonArray

        val cocomelon = categories[0].jsonObject["items"]!!.jsonArray[0].jsonObject
        assertEquals("PLcocomelon123", cocomelon["youtubePlaylistId"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, cocomelon["youtubeVideoId"])

        val twinkle = categories[1].jsonObject["items"]!!.jsonArray[1].jsonObject
        assertEquals("DuXwFlL8Usk", twinkle["youtubeVideoId"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, twinkle["youtubePlaylistId"])

        // And the nulls really are written out, not omitted.
        assertTrue(text.contains("\"youtubeVideoId\": null"))
        assertTrue(text.contains("\"youtubePlaylistId\": null"))
    }

    @Test
    fun anEmptyCatalogRoundTripsAsAnEmptyListNotAsAMissingKey() {
        val text = CatalogJson.encode(emptyCatalogSnapshot())

        assertTrue(text.contains("\"categories\": []"))
        val decoded = CatalogJson.decodeSnapshot(text)!!
        assertEquals(CATALOG_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(0L, decoded.catalogVersion)
        assertTrue(decoded.categories.isEmpty())
    }

    @Test
    fun aCategoryWithNoItemsDecodesWithAnEmptyItemList() {
        val text = """{"schemaVersion":1,"catalogVersion":4,"categories":[{"id":"cat-stories","displayName":"Stories","sortOrder":3,"enabled":true}]}"""

        val decoded = CatalogJson.decodeSnapshot(text)!!

        assertEquals(1, decoded.categories.size)
        assertTrue("a configured but empty shelf is legitimate", decoded.categories[0].items.isEmpty())
        assertTrue(decoded.categories[0].enabled)
    }

    @Test
    fun anEmptyJsonObjectIsNotReadableAsACatalog() {
        // This is the malformed payload that must never be mistaken for "the parent wants nothing".
        assertNull(CatalogJson.decodeSnapshot("{}"))
    }

    @Test
    fun aMissingSchemaVersionOrCategoriesKeyIsNotReadableAsACatalog() {
        assertNull(CatalogJson.decodeSnapshot("""{"catalogVersion":20,"categories":[]}"""))
        assertNull(CatalogJson.decodeSnapshot("""{"schemaVersion":1,"catalogVersion":20}"""))
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
            """{"schemaVersion":"one","catalogVersion":1,"categories":[]}""",
        ).forEach { body ->
            assertNull("should not decode: '$body'", CatalogJson.decodeSnapshot(body))
        }
    }

    @Test
    fun unknownExtraKeysAreIgnoredSoTheContractCanGrow() {
        val text = """{"schemaVersion":1,"catalogVersion":3,"categories":[],"somethingNew":true}"""

        val decoded = CatalogJson.decodeSnapshot(text)!!

        assertEquals(3L, decoded.catalogVersion)
    }

    @Test
    fun aPutRequestCarriesNoAuthoritativeVersion() {
        val request = CatalogPutRequest(
            schemaVersion = CATALOG_SCHEMA_VERSION,
            expectedCatalogVersion = 10L,
            categories = fullCatalog.categories,
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
        val body = """{"schemaVersion":1,"catalogVersion":999999,"categories":[]}"""

        val decoded = CatalogJson.decodePutRequest(body)

        assertNotNull(decoded)
        assertEquals(CATALOG_SCHEMA_VERSION, decoded!!.schemaVersion)
        assertNull(decoded.expectedCatalogVersion)
        assertTrue(decoded.categories.isEmpty())
    }

    @Test
    fun aPutRequestWithoutCategoriesIsNotReadable() {
        assertNull(CatalogJson.decodePutRequest("{}"))
        assertNull(CatalogJson.decodePutRequest("""{"schemaVersion":1}"""))
        assertNull(CatalogJson.decodePutRequest("""{"categories":[]}"""))
    }

    @Test
    fun theEncoderAndDecoderAreTheSameConfigurationBothWays() {
        // Round-tripping through the shared serializer twice must be stable, which is what stops the
        // server and the TV drifting apart on defaults or nulls.
        val once = CatalogJson.encode(fullCatalog)
        val twice = CatalogJson.encode(CatalogJson.decodeSnapshot(once)!!)

        assertEquals(once, twice)
    }
}
