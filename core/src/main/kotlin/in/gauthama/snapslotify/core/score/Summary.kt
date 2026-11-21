package `in`.gauthama.snapslotify.core.score

import `in`.gauthama.snapslotify.core.pipeline.PipelineResult
import `in`.gauthama.snapslotify.core.score.Scorer.DATE_FLAGS

/** Reports for a set of scored cases, as the notebook's summarise_rows (view a), plus breakdowns and the per-case table. */
object Summary {

    /**
     * One scored case. [alt] is the same case under the alternative (proposed, post-hoc) bill-title rule.
     * [meta] holds grouping values (category, frame, tier, traps) for the breakdowns.
     */
    class Row(
        val id: String, val expects: List<Expect>, val score: Score, val result: PipelineResult,
        val alt: Score? = null, val meta: Map<String, List<String>> = emptyMap(),
    )

    private fun pct(a: Int, b: Int) = if (b == 0) "$a of 0" else "$a of $b (${"%.1f".format(100.0 * a / b)}%)"

    /** An event needs a flag if it matched no expected event or its extracted date was wrong or unconfirmable. */
    private fun needs(e: PerEvent) = e.matched == null || e.modelDateOk != true

    /** All metrics. [withIds] = false leaves out case ids (aggregate only, for the sealed FINAL run). */
    fun metrics(scores: List<Pair<String, Score>>, withIds: Boolean = true): LinkedHashMap<String, String> {
        val counts = scores.groupingBy { it.second.label }.eachCount()
        val evs = scores.flatMap { it.second.perEvent }
        val flagged = evs.filter { it.dateFlags.isNotEmpty() }
        val needing = evs.filter(::needs)
        val good = scores.count { it.second.label in Scorer.GOOD }
        val needlessOnly = scores.count { (_, s) ->
            s.label == "pass_flagged" && s.perEvent.filter { it.dateFlags.isNotEmpty() }.none(::needs)
        }
        val n = scores.size
        fun ids(label: String) = scores.filter { it.second.label == label }.let { l ->
            if (withIds) l.joinToString { it.first }.ifEmpty { "-" } else l.size.toString()
        }
        return linkedMapOf<String, String>().apply {
            Scorer.LABELS.forEach { put(it, (counts[it] ?: 0).toString()) }
            put("fully correct or correctly flagged", pct(good, n))
            put("fully correct, no flags needed (pass + pass_extra_keys)", pct((counts["pass"] ?: 0) + (counts["pass_extra_keys"] ?: 0), n))
            put("without needless-only flags", pct(good - needlessOnly, n))
            put(if (withIds) "wrong_confident ids" else "wrong_confident count", ids("wrong_confident"))
            put(if (withIds) "phantom ids" else "phantom count", ids("phantom"))
            put("flag rate (events flagged / events)", pct(flagged.size, evs.size))
            put("  flagged correctly / needlessly", "${flagged.count(::needs)} / ${flagged.count { !needs(it) }}")
            put("verifier recall (needing a flag -> flagged)", pct(needing.count { it.dateFlags.isNotEmpty() }, needing.size))
        }
    }

    /** Side-by-side metric columns: name -> metrics. */
    fun table(columns: LinkedHashMap<String, LinkedHashMap<String, String>>): String = buildString {
        val keys = columns.values.first().keys
        val w0 = keys.maxOf { it.length }
        val ws = columns.map { (name, m) -> maxOf(name.length, m.values.maxOf { it.length }) }
        appendLine("".padEnd(w0) + "  " + columns.keys.mapIndexed { i, n -> n.padEnd(ws[i]) }.joinToString("  "))
        for (k in keys) appendLine(k.padEnd(w0) + "  " + columns.values.mapIndexed { i, m -> m.getValue(k).padEnd(ws[i]) }.joinToString("  "))
    }

    /** Label counts and % good per value of a meta field (a case counts under every value it has, e.g. each trap). */
    fun breakdown(rows: List<Row>, field: String, alt: Boolean = false): String = buildString {
        val groups = linkedMapOf<String, MutableList<Row>>()
        rows.forEach { r -> r.meta[field].orEmpty().ifEmpty { listOf("(none)") }.forEach { groups.getOrPut(it) { mutableListOf() } += r } }
        val labels = Scorer.LABELS.filter { l -> rows.any { (if (alt) it.alt else it.score)?.label == l } }
        appendLine("| $field | n | good | " + labels.joinToString(" | ") + " |")
        appendLine("|---|---|---|" + labels.joinToString("") { "---|" })
        for ((v, rs) in groups.toSortedMap()) {
            val ss = rs.map { if (alt) it.alt!! else it.score }
            appendLine("| $v | ${rs.size} | ${ss.count { it.label in Scorer.GOOD }} | " +
                labels.joinToString(" | ") { l -> ss.count { it.label == l }.toString() } + " |")
        }
    }

    fun dropped(rows: List<Row>): String = buildString {
        val dropped = rows.flatMap { r -> r.result.dropped.map { r to it } }
        if (dropped.isEmpty()) appendLine("  none")
        for ((r, d) in dropped) {
            val b = d.before
            val ev = ScoreEvent(b.title, b.time, b.date, b.date, emptyList())
            val real = r.expects.firstOrNull { it.titleMatches(ev) }?.name
            appendLine("  ${r.id.padEnd(26)} title='${b.title}' date='${b.date}' time='${b.time}'  " +
                (if (real != null) "[REAL KEY EVENT '$real': a missed event]" else "[not a key event]") + "  ${d.dropReason}")
        }
    }

    fun perCase(rows: List<Row>, metaFields: List<String> = emptyList()): String = buildString {
        val hasAlt = rows.any { it.alt != null }
        val head = listOf("case") + metaFields + listOf("label") + (if (hasAlt) listOf("label (payee rule)") else emptyList()) +
            listOf("events (title | date | time | location)", "date flags", "other flags", "reason")
        appendLine("| " + head.joinToString(" | ") + " |")
        appendLine("|" + head.joinToString("") { "---|" })
        for (r in rows) {
            val evs = r.result.events.joinToString(" ;; ") { "${it.event.title} \\| ${it.event.date} \\| ${it.event.time} \\| ${it.event.location}" }
                .ifEmpty { "(no event)" }
            val dateFlags = r.result.events.flatMap { it.verified.dateFlags }.distinct().joinToString(", ").ifEmpty { "-" }
            val other = r.result.events.flatMap { it.flags.map { f -> f.code } }.filter { it !in DATE_FLAGS }.distinct()
                .joinToString(", ").ifEmpty { "-" }
            val reason = if (r.score.label == "pass") "" else r.score.reasons.firstOrNull().orEmpty()
            val cells = listOf(r.id) + metaFields.map { r.meta[it].orEmpty().joinToString(", ").ifEmpty { "-" } } +
                listOf(r.score.label) + (if (hasAlt) listOf(r.alt?.label ?: "-") else emptyList()) +
                listOf(evs, dateFlags, other, reason.replace("|", "\\|"))
            appendLine("| " + cells.joinToString(" | ") + " |")
        }
    }

    /** The plain report used for the 29 seen cases (unchanged format from slice 2, plus the alternative column). */
    fun render(rows: List<Row>, header: List<String>): String = buildString {
        header.forEach { appendLine(it) }
        appendLine()
        val cols = linkedMapOf("strict title rule" to metrics(rows.map { it.id to it.score }))
        if (rows.any { it.alt != null }) cols["payee rule for bills (proposed)"] = metrics(rows.map { it.id to (it.alt ?: it.score) })
        append(table(cols))
        appendLine()
        appendLine("DROPPED by the verifier (validity rule)")
        append(dropped(rows))
        appendLine()
        appendLine("PER CASE")
        append(perCase(rows))
    }
}
