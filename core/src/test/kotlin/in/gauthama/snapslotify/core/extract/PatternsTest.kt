package `in`.gauthama.snapslotify.core.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.PatternSyntaxException

/**
 * Android's ICU regex engine rejects the (?U) flag (UNICODE_CHARACTER_CLASS) that the JVM needs for Python-like \d \s \w \b;
 * that crashed the pipeline on the phone (slice 3). ICU's classes are Unicode already, so the fallback drops only the flag.
 */
class PatternsTest {

    @Test
    fun fallsBackWhenTheEngineRejectsTheUnicodeFlag() {
        val compiled = mutableListOf<String>()
        val icuLike: (String) -> Regex = { p ->
            compiled += p
            if (p.startsWith("(?iU)")) throw PatternSyntaxException("Syntax error", p, 3)
            Regex(p)
        }
        val r = Patterns.compileUnicode("""\d{1,2}:\d{2}\s?pm""", icuLike)
        assertEquals(listOf("""(?iU)\d{1,2}:\d{2}\s?pm""", """(?i)\d{1,2}:\d{2}\s?pm"""), compiled)
        assertTrue(r.matches("6:30 PM"))
    }

    @Test
    fun theJvmKeepsTheUnicodeFlag() {
        assertTrue(Patterns.regex("""\d""").pattern.startsWith("(?iU)"))
    }
}
