package `in`.gauthama.snapslotify.core

import `in`.gauthama.snapslotify.core.ocr.OcrJson
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.score.Score
import `in`.gauthama.snapslotify.core.score.Scorer
import `in`.gauthama.snapslotify.core.score.Summary
import `in`.gauthama.snapslotify.core.score.SyntheticExpect
import `in`.gauthama.snapslotify.core.score.TitleRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * The synthetic set (snapslotify_bills_flyers_100): phone OCR (benchmarks/phone_ocr/<set>_ocr.json) joined with the
 * key file (dev_expected.json or sealed_expected.json) by file name.
 */
object Synthetic {

    /** "today_for_this_set" in the key file's _meta: 2026-10-20. */
    val TODAY: LocalDate = LocalDate.of(2026, 10, 20)

    class Case(val id: String, val ocr: OcrResult, val expects: List<SyntheticExpect>, val meta: Map<String, List<String>>)

    fun load(ocrFile: File, keyFile: File): List<Case> {
        val ocr = OcrJson.decodeRecords(ocrFile.readText()).toMap()
        val keys = Json.parseToJsonElement(keyFile.readText()).jsonObject - "_meta"
        require(ocr.keys == keys.keys) { "OCR and key files cover different images: ${(ocr.keys - keys.keys).size} / ${(keys.keys - ocr.keys).size} unmatched" }
        return keys.keys.sorted().map { id ->
            val k = keys.getValue(id).jsonObject
            val payee = k.str("payee")
            val expects = k.getValue("events").jsonArray.map { it.jsonObject }.map { e ->
                SyntheticExpect(
                    title = e.str("title")!!, payee = payee,
                    date = e.str("date")?.let(LocalDate::parse), endDate = e.str("end_date")?.let(LocalDate::parse),
                    start = e.str("start")?.let(LocalTime::parse),
                )
            }
            val meta = listOf("category", "frame", "tier").associateWith { listOfNotNull(k.str(it)) } +
                ("traps" to k["traps"]!!.jsonArray.map { it.jsonPrimitive.content })
            Case(id, ocr.getValue(id), expects, meta)
        }
    }

    /** Runs the pipeline on every case and scores it under both title rules. */
    fun score(cases: List<Case>): List<Summary.Row> = cases.map { c ->
        val r = CodePipeline.run(c.ocr, TODAY)
        val evs = r.scoreEvents(TODAY)
        fun s(rule: TitleRule): Score = Scorer.score(evs, c.expects, emptyList(), false, r.cleanedText, rule)
        Summary.Row(c.id, c.expects, s(TitleRule.STRICT), r, s(TitleRule.PAYEE_FOR_BILLS), c.meta)
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
}
