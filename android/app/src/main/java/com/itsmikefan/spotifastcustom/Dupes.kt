package com.itsmikefan.spotifastcustom

/** Duplicate songs, matched exactly as the desktop's `PlayableItem::song_key` and `dedupe_plan`. */
object Dupes {
    /**
     * The same song by name and lead artist, so a reupload on another album
     * counts. Case, spacing and remaster tags are ignored; "Live" or "Edit"
     * versions stay distinct because they are different recordings.
     */
    fun key(name: String, leadArtist: String, uri: String): String {
        if (name.isBlank()) return uri
        return withoutRemaster(normal(name)) + "\u001f" + normal(leadArtist)
    }

    fun key(track: Track) = key(track.name, track.lead, track.uri)

    private fun normal(text: String) = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").lowercase()

    /** Drops "- Remastered 2011" suffixes and "(2009 Remaster)" or "[Remastered]" groups. */
    private fun withoutRemaster(name: String): String {
        val dash = name.indexOf(" - ")
        var rest = if (dash >= 0 && name.substring(dash).contains("remaster")) name.substring(0, dash) else name
        val kept = StringBuilder()
        while (true) {
            val start = rest.indexOfAny(charArrayOf('(', '['))
            if (start < 0) break
            val close = rest.substring(start).indexOfAny(charArrayOf(')', ']'))
            if (close < 0) break
            val end = start + close
            kept.append(rest, 0, start)
            if (!rest.substring(start, end).contains("remaster")) kept.append(rest, start, end + 1)
            rest = rest.substring(end + 1)
        }
        kept.append(rest)
        return kept.toString().split(" ").filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Row indices of songs that appear more than once, in first-seen order. */
    fun groups(tracks: List<Track>): List<List<Int>> {
        val byKey = LinkedHashMap<String, MutableList<Int>>()
        tracks.forEachIndexed { index, track -> if (track.uri.isNotEmpty()) byKey.getOrPut(key(track)) { mutableListOf() } += index }
        return byKey.values.filter { it.size > 1 }
    }

    /**
     * Spotify removes every copy of a URI at once. To remove only the picked
     * rows, remove each picked URI entirely and put back its kept copies:
     * returns the URIs to remove and (uri, final position) to re-add, ascending.
     */
    fun plan(rows: List<String>, remove: Set<Int>): Pair<List<String>, List<Pair<String, Int>>> {
        val uris = remove.sorted().mapNotNull { rows.getOrNull(it)?.takeIf(String::isNotEmpty) }.distinct()
        val readd = mutableListOf<Pair<String, Int>>()
        var position = 0
        rows.forEachIndexed { index, uri ->
            if (uri !in uris) position++
            else if (index !in remove) readd += uri to position++
        }
        return uris to readd
    }
}
