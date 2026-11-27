package io.github.binoverfl0w.zencampus.model

/** Room labels use both building/number orders and punctuation inside room numbers. */
internal class CampusRoomQuery(value: String) {
    val text = value.trim().replace(Regex("[\\s\\u00a0]+"), " ")
    private val parts = parts(text)
    val cacheText = if (parts.none { it.length >= 3 }) text.lowercase()
        else parts.distinct().sorted().joinToString(" ")

    /** Equivalent labels use the same server anchor as well as the same cache key. */
    val searchTerms: List<String> = buildList {
        if (parts.isEmpty()) return@buildList
        val number = parts.filter { it.all(Char::isDigit) && it.length in 3..12 }.maxWithOrNull(compareBy<String> { it.length }.thenBy { it })
        if (number != null) {
            add(number)
            // Stud.IP binds this as a LIKE value; punctuation may separate floor and room digits.
            add(number.toList().joinToString("%"))
        } else {
            add(parts.filter { it.length >= 3 }.maxWithOrNull(compareBy<String> { it.length }.thenBy { it }) ?: text)
        }
    }.distinct()

    fun matches(name: String): Boolean {
        val available = parts(name)
        return parts.isNotEmpty() && parts.all { wanted -> available.any { candidate ->
            when {
                wanted.all(Char::isDigit) -> candidate.all(Char::isDigit) && candidate.contains(wanted)
                wanted.length <= 3 -> candidate == wanted
                else -> candidate.contains(wanted)
            }
        } }
    }

    companion object {
        private fun parts(value: String): List<String> {
            // Join only separators between digits: N-2.013 -> N-2013, never join across a letter.
            val joined = value.lowercase().replace(Regex("(?<=\\d)[\\s\\u00a0./_·\\-–—]+(?=\\d)"), "")
            return Regex("[\\p{L}]+|[0-9]+").findAll(joined).map { it.value }.toList()
        }
    }
}

fun campusRoomNameMatches(query: String, name: String): Boolean = query.isBlank() || CampusRoomQuery(query).matches(name)
