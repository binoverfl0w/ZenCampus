package io.github.binoverfl0w.zencampus.ui

import kotlin.test.*

class WordTablesTest {
    @Test fun everyTableHasTheSameKeysInEnglishAndGerman() {
        val problems = WORD_TABLES.flatMap { (namespace, tables) ->
            val (en, de) = tables
            (en.keys - de.keys).map { "$namespace: \"$it\" has no German text" } +
                (de.keys - en.keys).map { "$namespace: \"$it\" has no English text" }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test fun aDuplicateKeyFailsLoudly() {
        val failure = assertFailsWith<IllegalStateException> { wordTable("a" to "1", "b" to "2", "a" to "3") }
        assertTrue("\"a\"" in failure.message.orEmpty())
    }

    @Test fun catalogueResultCountUsesTheSingularOnlyForOne() {
        val english = Words(german = false)
        val german = Words(german = true)
        assertEquals("0 results", english.catalogueResultCount(0))
        assertEquals("1 result", english.catalogueResultCount(1))
        assertEquals("2 results", english.catalogueResultCount(2))
        assertEquals("1 Treffer", german.catalogueResultCount(1))
        assertEquals("2 Treffer", german.catalogueResultCount(2))
    }

}
