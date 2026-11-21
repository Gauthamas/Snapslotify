package `in`.gauthama.snapslotify.core

import `in`.gauthama.snapslotify.core.ocr.OcrJson
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.score.Key
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * The 29 seen cases as exported from the notebook by benchmarks/slice2/export_notebook.py (private, gitignored).
 * Tests that need it are skipped when the file is absent.
 */
object Fixtures {

    val exportFile = File("../benchmarks/slice2/notebook_export.json")

    class Case(
        val id: String,
        val set: String,
        val ocr: OcrResult,
        val gText: String,
        val gRemoved: List<Pair<String, String>>,
        val key: Key,
        val rules: JsonObject,
    )

    val cases: List<Case> by lazy {
        val root = Json.parseToJsonElement(exportFile.readText()).jsonObject
        root.getValue("cases").jsonArray.map { it.jsonObject }.map { c ->
            val entry = JsonObject(mapOf(
                "width" to c.getValue("width"), "height" to c.getValue("height"),
                "ocr_ms" to JsonPrimitive(0), "lines" to c.getValue("lines"),
            ))
            Case(
                id = c.str("id"), set = c.str("set"),
                ocr = OcrJson.decode(entry.toString()),
                gText = c.str("g_text"),
                gRemoved = c.getValue("g_removed").jsonArray.map { it.jsonObject.str("text") to it.jsonObject.str("rule") },
                key = parseKey(c.getValue("key").jsonObject, bill = "_bill" in c.str("id")),
                rules = c.getValue("rules").jsonObject,
            )
        }
    }

    fun requireExport() = assumeTrue("notebook_export.json not present", exportFile.exists())

    private fun parseKey(k: JsonObject, bill: Boolean) = Key(
        events = k.getValue("events").jsonArray.map { w ->
            w.jsonObject.mapValues { (_, v) -> phrases(v) }
        },
        forbid = k["forbid"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        flaggedOk = k["flagged_ok"]?.jsonPrimitive?.booleanOrNull ?: false,
        payeeCases = bill,
    )

    /** A list of accepted phrases, or null for the "UNCONFIRMED" date. */
    private fun phrases(v: JsonElement): List<String>? =
        if (v is JsonArray) v.map { it.jsonPrimitive.content } else null

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

}
