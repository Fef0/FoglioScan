package app.foglioscan.data

import java.text.Normalizer

/** A document matching a search, with where the first match was found. */
data class SearchMatch(
    /** 0-based page of the first text match, or null when only the name matched. */
    val page: Int?,
    /** A short excerpt around the first text match (or the name). */
    val snippet: String,
    /** The matched words inside [snippet], as index ranges. */
    val highlights: List<IntRange>,
)

/**
 * Full-text matching for saved documents: every word of the query must appear in the document's
 * name or text. Case and accents are ignored ("citta" finds "Città"), as are line breaks.
 */
object TextSearch {

    fun match(query: String, name: String, pages: List<String>): SearchMatch? {
        val words = normalize(query).text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val nName = normalize(name)
        val nPages = pages.map { normalize(it) }
        val all = words.all { w -> nName.text.contains(w) || nPages.any { it.text.contains(w) } }
        if (!all) return null
        // Excerpt from the first page containing the first word that occurs in the text.
        for (w in words) {
            for ((i, p) in nPages.withIndex()) {
                val at = p.text.indexOf(w)
                if (at < 0) continue
                return snippet(pages[i], p, at, words).copy(page = i)
            }
        }
        return SearchMatch(null, name, highlightsIn(name, nName, words))
    }

    private fun snippet(original: String, n: Normalized, at: Int, words: List<String>): SearchMatch {
        val from = (n.map[at] - CONTEXT).coerceAtLeast(0)
        val to = (n.map[at] + CONTEXT).coerceAtMost(original.length)
        // Widen to word boundaries.
        var s = from; while (s > 0 && !original[s - 1].isWhitespace()) s--
        var e = to; while (e < original.length && !original[e].isWhitespace()) e++
        val raw = original.substring(s, e).replace(Regex("\\s+"), " ")
        val prefix = if (s > 0) "…" else ""
        val suffix = if (e < original.length) "…" else ""
        val text = (prefix + raw.trim() + suffix)
        return SearchMatch(0, text, highlightsIn(text, normalize(text), words))
    }

    private fun highlightsIn(text: String, n: Normalized, words: List<String>): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        for (w in words) {
            var at = n.text.indexOf(w)
            while (at >= 0) {
                val start = n.map[at]
                val end = if (at + w.length < n.map.size) n.map[at + w.length] else text.length
                ranges += start until end
                at = n.text.indexOf(w, at + w.length)
            }
        }
        return ranges.sortedBy { it.first }
    }

    /** Lower-case, accents removed, runs of whitespace as one space; [map] gives original indices. */
    class Normalized(val text: String, val map: IntArray)

    fun normalize(s: String): Normalized {
        val sb = StringBuilder(s.length)
        // Room for characters that lower-case to more than one (e.g. 'İ').
        val map = IntArray(s.length * 3 + 1)
        var n = 0
        var lastSpace = true
        for ((i, ch) in s.withIndex()) {
            if (ch.isWhitespace()) {
                if (!lastSpace) { sb.append(' '); map[n++] = i; lastSpace = true }
                continue
            }
            val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                .lowercase()
            for (b in base) { sb.append(b); map[n++] = i }
            lastSpace = false
        }
        map[n] = s.length
        return Normalized(sb.toString(), map.copyOf(n + 1))
    }

    private const val CONTEXT = 50
}
