package `in`.gauthama.snapslotify.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No source file holds a control character other than tab, line feed and carriage return. A stray one is invisible in
 * an editor and silently changes code: a backspace byte where a regex meant `\b` matched nothing, and no test noticed.
 */
class SourceTextTest {

    @Test fun noControlCharactersInSources() {
        val roots = listOf(File("src/main"), File("src/test")).filter { it.isDirectory }
        assertTrue("source roots not found from ${File(".").absolutePath}", roots.isNotEmpty())
        val bad = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "kts", "java") }.toList() }
            .mapNotNull { f ->
                val bytes = f.readBytes()
                val at = bytes.indexOfFirst { b -> val c = b.toInt() and 0xff; c < 32 && c != 9 && c != 10 && c != 13 }
                if (at < 0) null else {
                    val line = bytes.take(at).count { it == '\n'.code.toByte() } + 1
                    "${f.path}: byte 0x%02x at line $line".format(bytes[at].toInt() and 0xff)
                }
            }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
