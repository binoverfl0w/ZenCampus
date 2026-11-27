package io.github.binoverfl0w.zencampus.ui

import io.github.binoverfl0w.zencampus.ui.study.materialDe
import io.github.binoverfl0w.zencampus.ui.study.materialEn

/**
 * Like mapOf for copy, but a key listed twice fails at start-up instead of the later entry silently winning
 * (that has already relabelled the sync status once).
 */
internal fun wordTable(vararg entries: Pair<String, String>): Map<String, String> {
    val table = LinkedHashMap<String, String>(entries.size)
    entries.forEach { (key, value) -> check(table.put(key, value) == null) { "Copy key \"$key\" is listed twice" } }
    return table
}

/** Every copy table by namespace, English then German; tests check that both languages have the same keys. */
internal val WORD_TABLES: Map<String, Pair<Map<String, String>, Map<String, String>>> get() = mapOf(
    "text" to (Words.en to Words.de),
    "ui" to (SHELL_EN to SHELL_DE),
    "study" to (studyEn to studyDe),
    "action" to (assignmentEn to assignmentDe),
    "service" to (campusEn to campusDe),
    "group" to (groupEn to groupDe),
    "tool" to (toolEn to toolDe),
    "planner" to (plannerEn to plannerDe),
    "material" to (materialEn to materialDe),
    "release" to (releaseEn to releaseDe),
)
