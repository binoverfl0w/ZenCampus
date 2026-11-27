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

// Status, notices, empty states and loading placeholders.

@Composable
fun StatusPill(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .75f),
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = containerColor) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = contentColor,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small rounded label whose colour carries meaning (due soon, overdue, new…). */
@Composable
fun ToneChip(text: String, tone: Tone = Tone.NEUTRAL, modifier: Modifier = Modifier) {
    val colors = toneColors(tone)
    StatusPill(text, colors.soft, colors.ink, modifier)
}

/** A tinted notice with up to two actions: warnings, partial data, "check DigiCampus first" states. */
@Composable
fun ZenNotice(text: String, tone: Tone = Tone.WARN, action: String? = null, onAction: () -> Unit = {},
    actionEnabled: Boolean = true, modifier: Modifier = Modifier, secondary: String? = null, onSecondary: () -> Unit = {},
    secondaryExternal: Boolean = false) {
    val colors = toneColors(tone)
    Column(modifier.fillMaxWidth().background(colors.soft, RoundedCornerShape(Zen.radiusMedium)).padding(start = 14.dp, end = 14.dp, top = 10.dp,
        bottom = if (action == null && secondary == null) 10.dp else 0.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.ink)
        if (action != null || secondary != null) Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (action != null) ZenLink(action, onAction, enabled = actionEnabled, color = colors.ink)
            if (secondary != null) ZenLink(secondary, onSecondary, color = colors.ink, external = secondaryExternal)
        }
    }
}

/** A one-line loading or empty state inside a list: muted text, with a small spinner while [loading]. */
@Composable
fun ZenStatus(text: String, loading: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (loading && !LocalReducedMotion.current) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyMoment(title: String, body: String, symbol: Symbol = Symbol.LEAF) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(Zen.radiusLarge), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                ZenIcon(symbol, Modifier.size(20.dp), MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun LoadingCards() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            ZenPanel(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth(.65f).height(18.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)))
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth(.4f).height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)))
            }
        }
    }
}
