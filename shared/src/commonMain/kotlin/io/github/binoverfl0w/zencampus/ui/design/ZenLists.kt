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

// Grouped lists and rows: one rounded block of rows with hairlines between them.

/** One container for a short, fixed list of related rows ([ZenRow]), separated by [ZenDivider]. */
@Composable
fun ZenGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(Zen.radiusLarge), color = MaterialTheme.colorScheme.surface) {
        Column(content = content)
    }
}

/** Where a row sits in a run of rows; consecutive rows read as one rounded block with hairlines between them. */
enum class GroupPosition {
    SINGLE, FIRST, MIDDLE, LAST;
    val top: Boolean get() = this == SINGLE || this == FIRST
    val bottom: Boolean get() = this == SINGLE || this == LAST
    companion object {
        fun of(index: Int, count: Int): GroupPosition = when {
            count <= 1 -> SINGLE
            index == 0 -> FIRST
            index == count - 1 -> LAST
            else -> MIDDLE
        }
    }
}

/** Lazy-list friendly row of a grouped list: one item per lazy item, shaped by its [position]. */
@Composable
fun ZenListItem(
    position: GroupPosition = GroupPosition.SINGLE, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    val radius = Zen.radiusLarge
    val small = 4.dp
    val shape = RoundedCornerShape(
        topStart = if (position.top) radius else small, topEnd = if (position.top) radius else small,
        bottomStart = if (position.bottom) radius else small, bottomEnd = if (position.bottom) radius else small)
    val body: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = verticalAlignment, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
    // A 2 dp gap shows the background between rows: lighter than drawn dividers and robust in lazy lists.
    val spaced = modifier.fillMaxWidth().padding(bottom = if (position.bottom) 0.dp else 2.dp)
    if (onClick != null) Surface(onClick = onClick, modifier = spaced, shape = shape, color = MaterialTheme.colorScheme.surface, content = body)
    else Surface(spaced, shape = shape, color = MaterialTheme.colorScheme.surface, content = body)
}

/** Title, optional supporting line and optional meta line: the text block of every list row. */
@Composable
fun ZenListText(title: String, supporting: String = "", meta: String = "", modifier: Modifier = Modifier,
    titleColor: Color = Color.Unspecified, titleLines: Int = 2, overline: String = "") {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        if (overline.isNotBlank()) Text(overline, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 21.sp), color = titleColor,
            maxLines = titleLines, overflow = TextOverflow.Ellipsis)
        if (supporting.isNotBlank()) Text(supporting, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The leading icon box of list rows. */
@Composable
fun ZenIconTile(symbol: Symbol, tint: ZenTint? = null, size: Dp = 36.dp) {
    val colors = tint ?: ZenTint(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
    Box(Modifier.size(size).background(colors.soft, RoundedCornerShape(size * .28f)), contentAlignment = Alignment.Center) {
        ZenIcon(symbol, Modifier.size(size * .5f), colors.ink)
    }
}

/** Trailing chevron for rows that open something. */
@Composable
fun RowChevron() = ZenIcon(Symbol.NEXT, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)

/** Separates rows inside a [ZenGroup] with the same background gap as [ZenListItem] runs. */
@Composable
fun ZenDivider(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.background))

/** A row inside a [ZenGroup]: 16 dp insets, minimum touch height, optional click. */
@Composable
fun ZenRow(
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = verticalAlignment, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** A row in a single-choice list (semester, price group…): label, optional supporting text, a check when selected. */
@Composable
fun ZenOptionRow(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, supporting: String = "") {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(Zen.radiusSmall))
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            if (supporting.isNotBlank()) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) ZenIcon(Symbol.CHECK, Modifier.size(17.dp), MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
