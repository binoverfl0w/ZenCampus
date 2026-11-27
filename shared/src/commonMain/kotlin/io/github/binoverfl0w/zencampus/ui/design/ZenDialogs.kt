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

// Dialogs and bottom sheets.

/**
 * The app's dialog: compact title, 20 dp corners, body text in the supporting colour, actions bottom-right.
 * Same parameters as Material's AlertDialog so call sites read the same.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZenDialog(
    onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(),
    neutralButton: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    BasicAlertDialog(onDismissRequest, modifier, properties) {
        Surface(Modifier.widthIn(min = 280.dp, max = 440.dp), shape = RoundedCornerShape(Zen.radiusLarge),
            color = if (isZenDark()) scheme.surfaceContainerHigh else scheme.surface, shadowElevation = 6.dp) {
            Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 12.dp)) {
                if (title != null) Box(Modifier.padding(end = 8.dp, bottom = 10.dp).semantics { heading() }) {
                    ProvideTextStyle(MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)) { title() }
                }
                if (text != null) Box(Modifier.weight(1f, fill = false).padding(end = 8.dp)) {
                    CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                    }
                }
                // Secondary links (Open in DigiCampus) sit on the left of the actions, or above them when the dialog is narrow.
                BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    val actions: @Composable () -> Unit = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), itemVerticalAlignment = Alignment.CenterVertically) {
                            dismissButton?.invoke()
                            confirmButton()
                        }
                    }
                    if (neutralButton != null && maxWidth < 400.dp) Column(Modifier.fillMaxWidth()) {
                        neutralButton()
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { actions() }
                    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (neutralButton != null) neutralButton()
                        Spacer(Modifier.weight(1f))
                        actions()
                    }
                }
            }
        }
    }
}

/** The app's bottom sheet: background colour, 24 dp top corners, standard drag handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZenSheet(onDismissRequest: () -> Unit, modifier: Modifier = Modifier, sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    sheetGesturesEnabled: Boolean = true, dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest, modifier, sheetState, sheetGesturesEnabled = sheetGesturesEnabled,
        shape = RoundedCornerShape(topStart = Zen.radiusHero, topEnd = Zen.radiusHero),
        containerColor = MaterialTheme.colorScheme.background, dragHandle = dragHandle, content = content)
}
