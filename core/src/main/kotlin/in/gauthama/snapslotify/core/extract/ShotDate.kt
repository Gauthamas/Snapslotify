package `in`.gauthama.snapslotify.core.extract

import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * The screenshot's own date, the anchor for a relative day word when no date line in the image anchors it. Sources, in
 * order: the gallery's taken / added date, the EXIF date, a date in the file name; else none. Any of them can be later
 * than the real capture (a copied or re-saved file), so a date counted from it is always flagged.
 */
object ShotDate {

    /** A date in a file name: "Screenshot_20261105-101523", "IMG-20261105-WA0001", "Screenshot 2026-11-05 at 10.15.23". */
    private val NAME = Regex("""(?<!\d)(20\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])(?!\d)""")

    /** An EXIF date and time, "2026:11:05 10:15:23". */
    private val EXIF = Regex("""^\s*(\d{4})[:\-](\d{2})[:\-](\d{2})""")

    fun fromName(name: String?): LocalDate? = name?.let { NAME.find(it) }?.let { m -> of(m.groupValues) }

    fun fromExif(value: String?): LocalDate? = value?.let { EXIF.find(it) }?.let { m -> of(m.groupValues) }

    /** The first date found, in source order: gallery, EXIF, file name. */
    fun pick(gallery: LocalDate?, exif: LocalDate?, name: LocalDate?): LocalDate? = gallery ?: exif ?: name

    /**
     * The day a date line names relative to the screenshot date [shot]: "Today" is [shot], "Yesterday" the day before,
     * a weekday name ("Monday") the most recent such day on or before [shot]. Null for any other line.
     */
    fun lineDay(line: String, shot: LocalDate): LocalDate? {
        val w = line.lowercase().trim { !it.isLetter() }
        return when (w) {
            "today" -> shot
            "yesterday" -> shot.minusDays(1)
            else -> Patterns.weekday(w)?.takeIf { WEEKDAY_LINE.matches(w) }?.let { shot.with(TemporalAdjusters.previousOrSame(it)) }
        }
    }

    private val WEEKDAY_LINE = Regex("""(?:mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)(?:day|nesday|sday|urday|rsday)?""")

    /** "Thu, 5 Nov 2026", as the flag shows it. */
    fun label(d: LocalDate): String =
        "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}, ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.year}"

    private fun of(g: List<String>): LocalDate? = runCatching { LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt()) }.getOrNull()
}
