package app.foglioscan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {

    private val pages = listOf(
        "Contratto di locazione\nIl conduttore si impegna a pagare il canone",
        "Città di Perugia, 12 marzo 2026\nTotale dovuto: 1.234,56 €\nFIRMA del locatore",
    )

    @Test
    fun ignoresCaseAccentsAndLineBreaks() {
        val m = assertNotNull(TextSearch.match("citta", "Affitto", pages))
        assertEquals(1, m.page)
        val h = m.highlights.single()
        assertEquals("Città", m.snippet.substring(h.first, h.last + 1))
        // Across a line break.
        assertNotNull(TextSearch.match("canone città", "Affitto", pages))
        assertNotNull(TextSearch.match("LOCAZIONE il", "x", pages))
    }

    @Test
    fun everyWordMustAppear() {
        assertNull(TextSearch.match("perugia milano", "Affitto", pages))
        assertNotNull(TextSearch.match("perugia firma", "Affitto", pages))
    }

    @Test
    fun nameOnlyMatches() {
        val m = assertNotNull(TextSearch.match("affitto", "Affitto 2026", pages))
        assertNull(m.page)
        assertEquals("Affitto", m.snippet.substring(m.highlights[0].first, m.highlights[0].last + 1))
        // Name + text words together.
        assertNotNull(TextSearch.match("affitto totale", "Affitto 2026", pages))
    }

    @Test
    fun snippetsAreShortAndMarked() {
        val long = listOf("parola ".repeat(100) + "IMPORTANTE " + "altro ".repeat(100))
        val m = assertNotNull(TextSearch.match("importante", "d", long))
        assertTrue(m.snippet.startsWith("…") && m.snippet.endsWith("…"))
        assertTrue(m.snippet.length < 140)
        val h = m.highlights.single()
        assertEquals("IMPORTANTE", m.snippet.substring(h.first, h.last + 1))
    }

    @Test
    fun blankQueriesMatchNothing() {
        assertNull(TextSearch.match("   ", "Affitto", pages))
    }

    private fun <T> assertNotNull(v: T?): T { assertNotNull(null, v); return v!! }
}
