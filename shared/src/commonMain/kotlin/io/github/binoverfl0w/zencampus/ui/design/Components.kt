package io.github.binoverfl0w.zencampus.ui.design

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.binoverfl0w.zencampus.model.*

// Layout basics shared by every screen: frames, panels, titles, labels and course marks.

val LocalAppVersion = staticCompositionLocalOf { "" }

val LocalBackgroundWorkWhileClosed = staticCompositionLocalOf { true }

/** The board header, sections and page content share the same desktop column. */
@Composable
fun CampusContentFrame(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(modifier.widthIn(max = 760.dp).fillMaxWidth(), content = content)
    }
}

/** A standalone card. Lists use [ZenListItem] so rows share one container instead of a card each. */
@Composable
fun ZenPanel(
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surface,
    padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Zen.radiusLarge)
    val border: BorderStroke? = null
    val body: @Composable () -> Unit = { Column(Modifier.padding(padding), content = content) }
    if (onClick != null) Surface(onClick = onClick, modifier = modifier, color = color, shape = shape,
        border = border, content = body)
    else Surface(modifier = modifier, color = color, shape = shape,
        border = border, content = body)
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 24.sp),
            modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null) ZenLink(action, onAction)
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-.6).sp),
            modifier = Modifier.semantics { heading() })
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Course identity: initials on the course's own tint. */
@Composable
fun CourseBadge(course: Course, size: Dp = 44.dp) {
    val tint = courseTint(course.id)
    Box(Modifier.size(size).background(tint.soft, RoundedCornerShape(size * .3f)), contentAlignment = Alignment.Center) {
        Text(courseInitials(course.title), color = tint.ink, maxLines = 1,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = (size.value * .32f).sp, fontWeight = FontWeight.Bold, letterSpacing = .2.sp))
    }
}

@Composable
fun CourseDot(courseId: String?, size: Dp = 8.dp) {
    Box(Modifier.size(size).background(courseTint(courseId).ink, CircleShape))
}

@Composable
fun SmallLabel(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}

fun fileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${(bytes * 10 / (1024 * 1024)).toDouble() / 10} MB"
    bytes >= 1024 -> "${(bytes + 512) / 1024} KB"
    else -> "$bytes B"
}
