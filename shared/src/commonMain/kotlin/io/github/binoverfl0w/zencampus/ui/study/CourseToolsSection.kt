package io.github.binoverfl0w.zencampus.ui.study

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.design.GroupPosition
import io.github.binoverfl0w.zencampus.ui.design.LocalNow
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.SectionTitle
import io.github.binoverfl0w.zencampus.ui.design.SmallLabel
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Tone
import io.github.binoverfl0w.zencampus.ui.design.ZenChip
import io.github.binoverfl0w.zencampus.ui.design.ZenIcon
import io.github.binoverfl0w.zencampus.ui.design.ZenIconTile
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenListItem
import io.github.binoverfl0w.zencampus.ui.design.ZenListText
import io.github.binoverfl0w.zencampus.ui.design.ZenNotice
import io.github.binoverfl0w.zencampus.ui.design.courseTint
import io.github.binoverfl0w.zencampus.ui.study
import io.github.binoverfl0w.zencampus.ui.tool
import io.github.binoverfl0w.zencampus.ui.ui

/** Platforms may supply an authenticated, account-bound Vips reader. */
val LocalVipsReader = staticCompositionLocalOf<((courseId: String, url: String, title: String) -> Unit)?> { null }

/**
 * Platforms may open DigiCampus pages in the signed-in in-app browser, so "Open in DigiCampus" shows the page it names
 * instead of a signed-out system browser. True when it took the address; anything else opens in the system browser.
 */
val LocalCampusBrowser = staticCompositionLocalOf<((url: String) -> Boolean)?> { null }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseToolsSection(course: Course, snapshot: CampusSnapshot, sync: SyncStatus,
    onOpenUrl: (String) -> Unit, onRetry: () -> Unit) {
    val words = LocalWords.current
    val tools = snapshot.courseTools[course.id]
    val key = CacheKeys.tools(course.id)
    val sections = tools?.sections.orEmpty().filter { it.additional }
    val reader = LocalVipsReader.current
    val official: (String) -> Unit = { onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else it) }
    Column(Modifier.fillMaxWidth().testTag("course-tools"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(words.tool("sections"))
        if (key in sync.active && tools == null) SmallLabel(words.tool("loading"))
        else if (key in sync.errors || tools == null) {
            ZenNotice(words.tool(if (key in sync.errors) "unavailable" else "not_checked"), if (key in sync.errors) Tone.DANGER else Tone.NEUTRAL,
                words.text("retry"), onRetry, secondary = words.text("open_official"),
                onSecondary = { official(courseOverviewUrl(course.id)) }, secondaryExternal = true)
        }
        if (sections.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sections.forEach { tool ->
                    ZenChip(tool.title, { if (tool.vips && reader != null) reader(course.id, tool.sourceUrl, tool.title)
                        else official(tool.sourceUrl.ifBlank { courseOverviewUrl(course.id) }) }, Modifier.testTag("course-tool-${tool.id}"), trailing = Symbol.NEXT)
                }
            }
        }
        if (sections.isEmpty() && tools != null && key !in sync.errors && key !in sync.active)
            SmallLabel(words.tool("none"))
        snapshot.updatedAt[key]?.let { SmallLabel("${words.study("updated")} ${words.age(it, LocalNow.current)}") }
    }
}

/** Sheets share the course's outer list: no inner scroller and no hidden result cap. */
fun LazyListScope.courseToolsItems(course: Course, snapshot: CampusSnapshot, sync: SyncStatus,
    onOpenUrl: (String) -> Unit, onRetry: () -> Unit) {
    item(key = CacheKeys.tools(course.id)) { CourseToolsSection(course, snapshot, sync, onOpenUrl, onRetry) }
    if (snapshot.courseTools[course.id]?.hasVips != true && course.id !in snapshot.vipsSheets) return
    val key = CacheKeys.vips(course.id)
    val list = snapshot.vipsSheets[course.id]
    item(key = "vips-header:${course.id}") {
        val words = LocalWords.current
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(words.tool("sheets"))
            when {
                list == null && key in sync.errors -> ZenNotice(words.tool("sheets_unavailable"), Tone.DANGER, words.text("retry"), onRetry)
                list == null -> SmallLabel(words.tool("sheets_loading"))
                // The last update failed temporarily: the saved list stays, said quietly, and refreshes on its own.
                key in sync.stale -> Box(Modifier.testTag("vips-saved-${course.id}")) { SmallLabel(words.ui("saved_list")) }
                list.sheets.isEmpty() -> SmallLabel(words.tool("sheets_empty"))
            }
        }
    }
    items(list?.sheets.orEmpty().sortedBy { it.endsAt ?: Long.MAX_VALUE }, key = { "vips:${course.id}:${it.id}" }) { sheet ->
        VipsSheetRow(sheet) { onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else it) }
    }
    item(key = "vips-footer:${course.id}") {
        val words = LocalWords.current
        val reader = LocalVipsReader.current
        Column {
            if (!list?.sheets.isNullOrEmpty()) SmallLabel(words.tool("submission_check"))
            snapshot.updatedAt[key]?.let { SmallLabel("${words.study("updated")} ${words.age(it, LocalNow.current)}") }
            ZenLink(words.tool("all_sheets"), { if (reader != null) reader(course.id, vipsSheetsUrl(course.id), "Vips")
                else onOpenUrl(if (snapshot.account.demo) DIGICAMPUS_ORIGIN else vipsSheetsUrl(course.id)) }, external = true)
        }
    }
}

/** "Due …", "Self-test closes …" or "Exam window closes …": exam windows never read as exercise deadlines. */
@Composable
fun vipsEndText(sheet: VipsSheet): String {
    val words = LocalWords.current
    val endLabel = when (sheet.kind) {
        VipsKind.PRACTICE -> "deadline"
        VipsKind.SELFTEST -> "selftest_closes"
        VipsKind.EXAM -> "exam_closes"
        VipsKind.UNKNOWN -> "ends"
    }
    return if (sheet.ends.isNotBlank()) "${words.tool(endLabel)} ${sheet.ends}" else words.tool("unlimited")
}

@Composable
fun VipsSheetRow(sheet: VipsSheet, course: Course? = null, position: GroupPosition = GroupPosition.SINGLE, onOpenUrl: (String) -> Unit) {
    val words = LocalWords.current
    val reader = LocalVipsReader.current
    ZenListItem(position, Modifier.testTag("vips-sheet-${sheet.id}"), {
        if (reader != null) reader(sheet.courseId, sheet.sourceUrl, sheet.title) else onOpenUrl(sheet.sourceUrl)
    }) {
        ZenIconTile(Symbol.TASK, course?.let { courseTint(it.id) } ?: courseTint(sheet.courseId))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZenListText(sheet.title, vipsEndText(sheet), overline = course?.title.orEmpty())
            if (sheet.kind in setOf(VipsKind.SELFTEST, VipsKind.EXAM) && sheet.mode.isNotBlank()) SmallLabel(sheet.mode)
            if (sheet.startsAt?.let { it > LocalNow.current } == true) SmallLabel("${words.tool(if (sheet.kind == VipsKind.EXAM) "exam_opens" else "starts")} ${sheet.starts}")
            if (!sheet.datesVerified) SmallLabel(words.tool("date_unverified"), MaterialTheme.colorScheme.error)
            if (sheet.status.isNotBlank() && sheet.status !in setOf("–", "—", "-"))
                SmallLabel("${words.tool("status")}: ${sheet.status}")
            if (sheet.kind == VipsKind.EXAM) SmallLabel(words.tool("exam_timer"))
        }
        ZenIcon(if (reader != null) Symbol.NEXT else Symbol.EXTERNAL, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
