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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.binoverfl0w.zencampus.model.*

// Buttons, links and chips.

enum class ButtonStyle { PRIMARY, TONAL, OUTLINE, DANGER }

/** The app's button: 12 dp corners (not Material's pill), consistent height and optional icons. */
@Composable
fun ZenButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: ButtonStyle = ButtonStyle.TONAL,
    enabled: Boolean = true, symbol: Symbol? = null, trailing: Symbol? = null) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val padding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    val content: @Composable RowScope.() -> Unit = {
        val ink = LocalContentColor.current
        if (symbol != null) { ZenIcon(symbol, Modifier.size(17.dp), ink); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) { Spacer(Modifier.width(8.dp)); ZenIcon(trailing, Modifier.size(15.dp), ink) }
    }
    val size = modifier.heightIn(min = 44.dp)
    when (style) {
        ButtonStyle.PRIMARY -> Button(onClick, size, enabled, shape, contentPadding = padding, content = content)
        ButtonStyle.TONAL -> FilledTonalButton(onClick, size, enabled, shape,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer),
            contentPadding = padding, content = content)
        ButtonStyle.OUTLINE -> OutlinedButton(onClick, size, enabled, shape, border = BorderStroke(1.dp, scheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onSurface), contentPadding = padding, content = content)
        ButtonStyle.DANGER -> FilledTonalButton(onClick, size, enabled, shape,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
            contentPadding = padding, content = content)
    }
}

/** A dialog action. One per dialog is [primary]; [destructive] marks irreversible actions. */
@Composable
fun DialogButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    primary: Boolean = false, destructive: Boolean = false) {
    if (primary) ZenButton(text, onClick, modifier.heightIn(min = 40.dp), if (destructive) ButtonStyle.DANGER else ButtonStyle.PRIMARY, enabled)
    else TextButton(onClick, modifier, enabled, colors = ButtonDefaults.textButtonColors(
        contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The leading padding extends into the gutter so the contents stay aligned with nearby text.
 * The trailing padding is included in the measured width to keep adjacent actions apart.
 */
@Composable
fun ZenLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    external: Boolean = false, symbol: Symbol? = null, color: Color = MaterialTheme.colorScheme.primary,
    trailing: Symbol? = null) {
    val ink = if (enabled) color else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
    Row(modifier.heightIn(min = 44.dp).layout { measurable, constraints ->
        val inset = 8.dp.roundToPx()
        val target = measurable.measure(constraints.offset(horizontal = inset))
        layout(target.width - inset, target.height) { target.placeRelative(-inset, 0) }
    }.clip(RoundedCornerShape(8.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (symbol != null) ZenIcon(symbol, Modifier.size(16.dp), ink)
        Text(text, if (trailing == null) Modifier else Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (external) ZenIcon(Symbol.EXTERNAL, Modifier.size(13.dp), ink)
        if (trailing != null) ZenIcon(trailing, Modifier.size(15.dp), ink)
    }
}

/** An outlined action chip, matching [ZenTabs] choice chips. [selected] tints it like a toggled filter. */
@Composable
fun ZenChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, symbol: Symbol? = null, trailing: Symbol? = null,
    selected: Boolean = false, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val ink = when { !enabled -> scheme.onSurface.copy(alpha = .38f); selected -> scheme.onPrimaryContainer; else -> scheme.onSurface }
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(Zen.radiusSmall),
        color = if (selected) scheme.primaryContainer else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
        modifier = modifier.heightIn(min = 36.dp).semantics { if (selected) this.selected = true }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (symbol != null) ZenIcon(symbol, Modifier.size(15.dp), if (selected) ink else scheme.primary)
            Text(text, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trailing != null) ZenIcon(trailing, Modifier.size(13.dp), scheme.onSurfaceVariant)
        }
    }
}

/**
 * Switches between views inside a page.
 * [fill] = page sections (course Overview/Updates/…): quiet underline tabs across the width.
 * Otherwise a light choice row (open/submitted, day/week, filters): the selected option is tinted, the rest outlined; wraps on narrow screens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZenTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    tags: List<String> = emptyList(), fill: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    if (fill) Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            tabs.forEachIndexed { index, label ->
                val active = index == selected
                Column(Modifier.weight(1f).heightIn(min = 44.dp).clickable { if (!active) onSelect(index) }
                    .then(tags.getOrNull(index)?.let { Modifier.testTag(it) } ?: Modifier)
                    .semantics(mergeDescendants = true) { role = Role.Tab; this.selected = active },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text(label, Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                        style = if (tabs.size > 3) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) scheme.onSurface else scheme.onSurfaceVariant)
                    Box(Modifier.fillMaxWidth(.7f).height(2.5.dp).background(
                        if (active) scheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
    } else FlowRow(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEachIndexed { index, label ->
            val active = index == selected
            Surface(onClick = { if (!active) onSelect(index) }, shape = RoundedCornerShape(Zen.radiusSmall),
                color = if (active) scheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                border = if (active) null else BorderStroke(1.dp, scheme.outlineVariant),
                modifier = Modifier.heightIn(min = 36.dp).then(tags.getOrNull(index)?.let { Modifier.testTag(it) } ?: Modifier)
                    .semantics { role = Role.Tab; this.selected = active }) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (active) ZenIcon(Symbol.CHECK, Modifier.size(14.dp), scheme.onPrimaryContainer)
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) scheme.onPrimaryContainer else scheme.onSurface)
                }
            }
        }
    }
}

@Composable
fun ZenIconToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable () -> Unit) {
    IconToggleButton(checked, onCheckedChange, modifier, enabled, content = content)
}

@Composable
fun CoursePinButton(courseId: String, pinned: Boolean, onToggle: () -> Unit) {
    val words = LocalWords.current
    IconToggleButton(pinned, { onToggle() }, modifier = Modifier.testTag("course-pin-$courseId").semantics {
        contentDescription = words.text(if (pinned) "unpin_course" else "pin_course")
        stateDescription = words.text(if (pinned) "pinned" else "not_pinned")
    }, colors = IconButtonDefaults.iconToggleButtonColors(
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )) {
        ZenIcon(if (pinned) Symbol.PIN_FILLED else Symbol.PIN, Modifier.size(19.dp),
            if (pinned) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A compact − value + control (zoom levels). Icon buttons are centred in one outlined pill;
 * [onLabel] makes the value itself an action (e.g. reset to fit).
 */
@Composable
fun ZenStepper(label: String, onDecrease: () -> Unit, onIncrease: () -> Unit, decreaseLabel: String, increaseLabel: String,
    modifier: Modifier = Modifier, decreaseEnabled: Boolean = true, increaseEnabled: Boolean = true, onLabel: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.height(40.dp).border(1.dp, scheme.outlineVariant, RoundedCornerShape(Zen.radiusMedium)).clip(RoundedCornerShape(Zen.radiusMedium)),
        verticalAlignment = Alignment.CenterVertically) {
        @Composable fun Step(symbol: Symbol, description: String, enabled: Boolean, onClick: () -> Unit) {
            Box(Modifier.size(40.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                ZenIcon(symbol, Modifier.size(16.dp), if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = .38f))
            }
        }
        Step(Symbol.MINUS, decreaseLabel, decreaseEnabled, onDecrease)
        Box(Modifier.fillMaxHeight().widthIn(min = 52.dp).then(if (onLabel != null) Modifier.clickable(role = Role.Button, onClick = onLabel) else Modifier)
            .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1)
        }
        Step(Symbol.PLUS, increaseLabel, increaseEnabled, onIncrease)
    }
}
