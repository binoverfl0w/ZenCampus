package io.github.binoverfl0w.zencampus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.design.CourseBadge
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.ToneChip
import io.github.binoverfl0w.zencampus.ui.design.Zen
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenIconTile
import io.github.binoverfl0w.zencampus.ui.design.ZenRow
import io.github.binoverfl0w.zencampus.ui.design.boardTint

private sealed interface FindResult
private data class CourseResult(val course: Course) : FindResult
private data class FileResult(val document: Document, val course: Course?) : FindResult
private data class CatalogueResult(val query: String) : FindResult
private data class PlaceResult(val place: RailLink) : FindResult

/** Loaded courses and files whose text matches every word of the query. Nothing is fetched. */
internal fun quickFind(snapshot: CampusSnapshot, query: String): Pair<List<Course>, List<Document>> {
    val terms = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
    if (terms.isEmpty()) return emptyList<Course>() to emptyList()
    fun matches(vararg fields: String) = fields.joinToString(" ").lowercase().let { text -> terms.all { it in text } }
    val courses = snapshot.courses.filter { matches(it.title, it.number, it.teacher, it.subtitle, it.typeName) }
    val documents = (snapshot.courseMaterials.values.flatMap { it.documents } + snapshot.folderContents.values.flatMap { it.documents })
        .distinctBy { it.id }.filter { matches(it.name) }.sortedByDescending { it.modified }
    return courses to documents
}

/** Ctrl+K on desktop: jump to a course, open a loaded file or search the library catalogue. */
@Composable
fun QuickSearchDialog(snapshot: CampusSnapshot, onDismiss: () -> Unit, onCourse: (String) -> Unit,
    onFile: (Document) -> Unit, onCatalogue: (String) -> Unit, places: List<RailLink> = emptyList()) {
    val words = LocalWords.current
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val (courses, documents) = remember(query, snapshot.courses, snapshot.courseMaterials, snapshot.folderContents) { quickFind(snapshot, query) }
    val terms = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
    // Places are offered before typing and matched by their label afterwards: they are the app's actions.
    val matchingPlaces = places.filter { place -> terms.isEmpty() || terms.all { it in place.label.lowercase() } }
    val results: List<FindResult> = matchingPlaces.map { PlaceResult(it) } + courses.take(5).map { CourseResult(it) } +
        documents.take(8).map { doc -> FileResult(doc, snapshot.courses.firstOrNull { it.id == doc.courseId }) } +
        listOfNotNull(query.trim().takeIf { it.length >= 2 }?.let { CatalogueResult(it) })
    fun choose(result: FindResult) {
        onDismiss()
        when (result) {
            is CourseResult -> onCourse(result.course.id)
            is FileResult -> onFile(result.document)
            is CatalogueResult -> onCatalogue(result.query)
            is PlaceResult -> result.place.onClick()
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(.92f).heightIn(max = 560.dp).testTag("quick-search"),
            shape = RoundedCornerShape(Zen.radiusLarge), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 12.dp) {
            Column {
                OutlinedTextField(query, { query = it.take(120) }, Modifier.fillMaxWidth().padding(12.dp).focusRequester(focus).testTag("quick-search-query"),
                    placeholder = { Text(words.ui("search_hint")) }, singleLine = true,
                    leadingIcon = { ZenIcon(Symbol.SEARCH, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { results.firstOrNull()?.let(::choose) }))
                if (query.isNotBlank() && results.isEmpty()) Text(words.text("no_results"), Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (query.isBlank()) Text(words.ui("search_scope"), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 8.dp)) {
                    results.forEachIndexed { index, result ->
                        val heading = when {
                            result is PlaceResult && index == 0 -> words.ui("go_to")
                            result is CourseResult && results.getOrNull(index - 1) !is CourseResult -> words.text("courses")
                            result is FileResult && results.getOrNull(index - 1) !is FileResult -> words.text("files")
                            result is CatalogueResult -> words.service("library")
                            else -> null
                        }
                        if (heading != null) item { Text(heading, Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        item { FindRow(result) { choose(result) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun FindRow(result: FindResult, onClick: () -> Unit) {
    val words = LocalWords.current
    ZenRow(Modifier.padding(horizontal = 4.dp), onClick) {
        when (result) {
            is CourseResult -> {
                CourseBadge(result.course, 32.dp)
                Column(Modifier.weight(1f)) {
                    Text(result.course.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(result.course.number, result.course.teacher).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            is FileResult -> {
                Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                    ZenIcon(Symbol.FILE, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f)) {
                    Text(result.document.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(result.course?.title.orEmpty(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            is PlaceResult -> {
                ZenIconTile(result.place.symbol, size = 32.dp)
                Text(result.place.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (result.place.badge.isNotBlank()) ToneChip(result.place.badge, result.place.tone)
            }
            is CatalogueResult -> {
                val tint = boardTint(CampusBoard.LIBRARY)
                Box(Modifier.size(32.dp).background(tint.soft, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                    ZenIcon(Symbol.SEARCH, Modifier.size(16.dp), tint.ink)
                }
                Text("${words.ui("search_catalogue_for")} “${result.query}”", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
