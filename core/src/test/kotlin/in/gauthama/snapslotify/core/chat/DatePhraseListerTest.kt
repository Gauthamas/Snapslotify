package `in`.gauthama.snapslotify.core.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Parity of [DatePhraseLister] with the Python reference lists in benchmarks/chat_cases/lister_parity_reference.json
 * (the 100 training seeds, and the clean and noisy chat images as read by Windows OCR), and key coverage: how many key
 * date phrases are listed exactly, and how many only inside a longer listed phrase.
 */
class DatePhraseListerTest {

    private val cc = File("../benchmarks/chat_cases")
    private fun json(f: File) = Json.parseToJsonElement(f.readText().removePrefix("﻿")).jsonObject  // the OCR files have a BOM
    private fun strings(o: JsonObject, k: String) = o.getValue(k).jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun parityWithReference() {
        val f = File(cc, "lister_parity_reference.json")
        assumeTrue("no parity fixture", f.exists())
        val cases = json(f).getValue("cases").jsonArray.map { it.jsonObject }
        val bad = cases.filter { c ->
            val l = DatePhraseLister.list(strings(c, "lines"))
            val phrases = c.getValue("phrases").jsonArray.map { it.jsonObject }.map {
                DatePhrase(it.getValue("text").jsonPrimitive.content, it.getValue("kind").jsonPrimitive.content,
                    it.getValue("first_line").jsonPrimitive.int, it.getValue("last_line").jsonPrimitive.int)
            }
            l.candidates != strings(c, "candidates") || l.phrases != phrases ||
                l.selectedLines != c.getValue("selected_lines").jsonArray.map { it.jsonPrimitive.int } ||
                l.headerLines != c.getValue("header_lines").jsonArray.map { it.jsonPrimitive.int }
        }.map { it.getValue("id").jsonPrimitive.content }
        println("lister parity: ${cases.size - bad.size} of ${cases.size} identical" + if (bad.isEmpty()) "" else "; differ: $bad")
        assertEquals(emptyList<String>(), bad)
    }

    private val year = Regex("""\b20\d\d\b""")

    /** The chat's header date chip is never a candidate: clean and noisy (chip text from KEY.md) and the seeds (no chip). */
    @Test
    fun headerDateNeverListed() {
        val seeds = File(cc, "seed/seed_train_categories.jsonl")
        val keys = File(cc, "key_phrases.json")
        val ocr = listOf("clean", "noisy").associateWith { File(cc, "$it/winocr_lines.json") }
        assumeTrue("chat case files missing", seeds.exists() && keys.exists() && ocr.values.all { it.exists() })
        val problems = mutableListOf<String>()
        var chips = 0
        for ((set, file) in ocr) {
            val images = json(file).getValue("images").jsonObject
            for (c in json(keys).getValue("cases").jsonArray.map { it.jsonObject }) {
                val name = c.getValue(set).jsonPrimitive.content
                val chip = c.getValue("screenshot_date").jsonPrimitive.content
                val lines = images.getValue(name).jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }
                val l = DatePhraseLister.list(lines)
                val at = lines.indices.filter { lines[it].trim().equals(chip, ignoreCase = true) }
                chips += at.size
                if (at.isEmpty()) problems += "$name: chip '$chip' not found in the OCR lines"
                if (!l.headerLines.containsAll(at)) problems += "$name: chip line not excluded"
                if (l.phrases.any { p -> at.any { it in p.firstLine..p.lastLine } }) problems += "$name: chip line listed"
                if (l.candidates.any { year.containsMatchIn(it) }) problems += "$name: a candidate with a year: ${l.candidates}"
            }
        }
        var seedRows = 0
        for (line in seeds.readLines().filter { it.isNotBlank() }) {
            val s = Json.parseToJsonElement(line).jsonObject
            val l = DatePhraseLister.list(s.getValue("messages").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content })
            seedRows++
            if (l.headerLines.isNotEmpty()) problems += "${s.getValue("id").jsonPrimitive.content}: a seed bubble was taken for a chip"
            if (l.candidates.any { year.containsMatchIn(it) }) problems += "${s.getValue("id").jsonPrimitive.content}: a candidate with a year"
        }
        println("header chip: $chips chip lines in clean+noisy, $seedRows seeds; problems: ${problems.size}")
        assertEquals(emptyList<String>(), problems)
    }

    private fun coverage(key: String, cands: List<String>) = when {
        cands.any { it.equals(key, ignoreCase = true) } -> "exact"
        cands.any { it.contains(key, ignoreCase = true) } -> "inside a longer phrase"
        else -> "missing"
    }

    @Test
    fun keyCoverage() {
        val seeds = File(cc, "seed/seed_train_categories.jsonl")
        val ocr = listOf("clean", "noisy").associateWith { File(cc, "$it/winocr_lines.json") }
        val keys = File(cc, "key_phrases.json")
        assumeTrue("chat case files missing", seeds.exists() && keys.exists() && ocr.values.all { it.exists() })
        val seedCount = sortedMapOf<String, Int>()
        for (line in seeds.readLines().filter { it.isNotBlank() }) {
            val s = Json.parseToJsonElement(line).jsonObject
            val cands = DatePhraseLister.list(s.getValue("messages").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }).candidates
            for (e in s.getValue("key").jsonObject.getValue("events").jsonArray)
                seedCount.merge(coverage(e.jsonObject.getValue("date_phrase").jsonPrimitive.content, cands), 1, Int::plus)
        }
        println("training seeds (key date_phrase per event): $seedCount")
        val keyCases = json(keys).getValue("cases").jsonArray.map { it.jsonObject }
        for ((set, file) in ocr) {
            val images = json(file).getValue("images").jsonObject
            val counts = sortedMapOf<String, Int>()
            val misses = mutableListOf<String>()
            for (c in keyCases) {
                val name = c.getValue(set).jsonPrimitive.content
                val lines = images.getValue(name).jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }
                val cands = DatePhraseLister.list(lines).candidates
                val group = if (c.getValue("heldout_category").jsonPrimitive.boolean) "held-out category" else "training category"
                for (p in strings(c, "phrases")) {
                    val how = coverage(p, cands)
                    counts.merge("$group: $how", 1, Int::plus)
                    if (how == "missing") misses += "$name '$p' (${c.getValue("type").jsonPrimitive.content})"
                }
            }
            println("$set (Windows OCR): $counts; missing: $misses")
        }
    }
}
