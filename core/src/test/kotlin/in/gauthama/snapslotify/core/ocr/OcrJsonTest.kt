package `in`.gauthama.snapslotify.core.ocr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class OcrJsonTest {

    private val sample = OcrResult(
        imageWidth = 1080,
        imageHeight = 2340,
        lines = listOf(
            OcrLine("Team dinner", 49, 38, 400, 90, confidence = 0.93f, block = 0, line = 0),
            OcrLine("Fri 23 Oct, 7:30 pm — ₹500", 49, 100, 700, 150, confidence = null, block = 0, line = 1),
            OcrLine("\"Quotes\" \\ and\ttabs", 60, 300, 500, 340, confidence = 0.5f, block = 1, line = 0),
        ),
        ocrMs = 412,
    )

    @Test
    fun roundTrip() {
        assertEquals(sample, OcrJson.decode(OcrJson.encode(sample)))
        assertEquals(sample, OcrJson.decode(OcrJson.encode(sample, file = "x.png", includesModelLoad = true)))
    }

    @Test
    fun wordsAndTheirBoxesRoundTrip() {
        val withWords = OcrResult(1080, 1500, listOf(
            OcrLine("Piano class 12pm", 324, 264, 429, 309, 0.91f, 3, 0, listOf(
                OcrWord("Piano", 324, 264, 370, 280, 0.95f), OcrWord("class", 374, 264, 429, 280, 0.9f), OcrWord("12pm", 324, 290, 375, 309, null),
            )),
            OcrLine("1", 338, 217, 345, 236),
        ), 120)
        val back = OcrJson.decode(OcrJson.encode(withWords))
        assertEquals(withWords, back)
        assertEquals(3, back.lines[0].words.size)
        assertEquals(emptyList<OcrWord>(), back.lines[1].words)   // a line with no words, as in older dumps
    }

    @Test
    fun roundTripEmpty() {
        val empty = OcrResult(10, 20, emptyList(), 0)
        assertEquals(empty, OcrJson.decode(OcrJson.encode(empty)))
    }

    @Test
    fun encodesTheDumpFieldNames() {
        val o = Json.parseToJsonElement(OcrJson.encode(sample, file = "x.png", includesModelLoad = false)).jsonObject
        assertEquals(setOf("file", "width", "height", "ocr_ms", "includes_model_load", "text", "block_count", "lines"), o.keys)
        val lines = o.getValue("lines").jsonArray
        assertEquals(setOf("block", "line", "text", "boundingBox", "confidence"), lines[0].jsonObject.keys)
        assertFalse("null confidence is left out", "confidence" in lines[1].jsonObject)
        assertEquals(setOf("left", "top", "right", "bottom"), lines[0].jsonObject.getValue("boundingBox").jsonObject.keys)
        assertEquals("2", o.getValue("block_count").toString())
        val noExtras = Json.parseToJsonElement(OcrJson.encode(sample)).jsonObject
        assertFalse("file" in noExtras || "includes_model_load" in noExtras)
    }

    /** Shape check against the real dump, if this checkout has it (benchmarks/ is private and gitignored). */
    @Test
    fun matchesSavedBoxDump() {
        val dump = File("../benchmarks/step_a_ocr/run3_boxes.json")
        assumeTrue("run3_boxes.json not present", dump.exists())
        val text = dump.readText()
        val results = OcrJson.decodeResults(text)
        val entries = Json.parseToJsonElement(text).jsonObject.getValue("results") as JsonArray
        assertEquals(entries.size, results.size)
        assertTrue(results.isNotEmpty())
        for ((entry, result) in entries.zip(results)) {
            val original = entry.jsonObject
            val again = Json.parseToJsonElement(OcrJson.encode(result)).jsonObject
            // Everything except the two sidecar keys must come back identical, including text and block_count.
            assertEquals(JsonObject(original - "file" - "includes_model_load"), again)
            assertEquals(result, OcrJson.decode(OcrJson.encode(result)))
        }
    }
}
