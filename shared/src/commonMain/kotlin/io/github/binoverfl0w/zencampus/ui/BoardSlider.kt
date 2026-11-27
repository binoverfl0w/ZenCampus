package io.github.binoverfl0w.zencampus.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.ui.design.LocalReducedMotion
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import kotlin.math.abs
import kotlin.math.roundToInt

/** Android reserves only the slider's small edge region; other platforms use the modifier as-is. */
val LocalBoardGestureExclusion = staticCompositionLocalOf<(Modifier) -> Modifier> { { it } }

/** Lives outside the changing board content so a single held drag can cross several boards. */
@Composable
fun BoardSlider(board: CampusBoard, onBoard: (CampusBoard) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, onGesture: (Boolean) -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    val words = LocalWords.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val latestBoard by rememberUpdatedState(board)
    val latestSelect by rememberUpdatedState(onBoard)
    val latestGesture by rememberUpdatedState(onGesture)
    val latestHaptic by rememberUpdatedState(haptic)
    val excludeGesture = LocalBoardGestureExclusion.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    var active by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val reducedMotion = LocalReducedMotion.current
    val opacity by animateFloatAsState(if (active) 1f else .32f,
        animationSpec = tween(if (reducedMotion) 0 else 140))
    val settledPosition by animateFloatAsState(dragPosition ?: board.ordinal.toFloat(),
        animationSpec = tween(if (active || reducedMotion) 0 else 140))
    val commit: (Int) -> Unit = { index ->
        latestHaptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        latestSelect(CampusBoard.entries[index])
    }
    val latestCommit by rememberUpdatedState(commit)
    val select: (Int) -> Boolean = { index ->
        val target = CampusBoard.entries[index.coerceIn(0, 2)]
        if (target == latestBoard) false else {
            latestCommit(target.ordinal)
            true
        }
    }
    val latestSelectIndex by rememberUpdatedState(select)
    BoxWithConstraints(modifier) {
        val visible = enabled && !keyboardVisible && maxHeight >= 200.dp
        val top = (maxHeight - 160.dp) / 2
        Box(Modifier.fillMaxSize().pointerInput(visible, top) {
            if (!visible) return@pointerInput
            val hitArea = Rect(0f, top.toPx(), 28.dp.toPx(), (top + 160.dp).toPx())
            val centerStop = (top + 80.dp).toPx()
            val spacing = 52.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!hitArea.contains(down.position) || down.isConsumed) return@awaitEachGesture
                down.consume()
                active = true
                latestGesture(true)
                val startIndex = latestBoard.ordinal
                var chosen = startIndex
                var dragging = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } > 1) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        val vertical = change.position.y - down.position.y
                        val horizontal = abs(change.position.x - down.position.x)
                        if (!dragging && abs(vertical) > viewConfiguration.touchSlop) dragging = true
                        change.consume()
                        if (dragging) {
                            // Move the strip with the finger beneath the stationary center line.
                            val position = (startIndex - vertical / spacing).coerceIn(0f, 2f)
                            dragPosition = position
                            // A small dead band prevents repeated switches and vibration near a stop.
                            if (position > chosen + .58f || position < chosen - .58f) {
                                val next = position.roundToInt().coerceIn(0, 2)
                                if (next != chosen) { chosen = next; latestCommit(chosen) }
                            }
                        }
                        if (change.changedToUpIgnoreConsumed()) {
                            if (!dragging && horizontal < viewConfiguration.touchSlop && abs(vertical) < viewConfiguration.touchSlop)
                                latestSelectIndex((startIndex + (change.position.y - centerStop) / spacing).roundToInt())
                            break
                        }
                    }
                } finally { active = false; dragPosition = null; latestGesture(false) }
            }
        }) {
            content()
            if (visible) {
                val position = dragPosition ?: settledPosition
                val primary = MaterialTheme.colorScheme.primary
                Canvas(excludeGesture(Modifier.align(AbsoluteAlignment.CenterLeft).width(28.dp).height(160.dp)).clipToBounds()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithCache {
                        val halfHeight = 38.dp.toPx()
                        val fade = Brush.verticalGradient(0f to Color.Transparent, .2f to Color.Black,
                            .8f to Color.Black, 1f to Color.Transparent,
                            startY = size.height / 2f - halfHeight, endY = size.height / 2f + halfHeight)
                        onDrawWithContent { drawContent(); drawRect(fade, blendMode = BlendMode.DstIn) }
                    }
                    .testTag("board-slider").semantics {
                        contentDescription = words.service("board_slider")
                        stateDescription = words.board(board)
                        progressBarRangeInfo = ProgressBarRangeInfo(board.ordinal.toFloat(), 0f..2f, 1)
                        setProgress { latestSelectIndex(it.roundToInt()) }
                        customActions = CampusBoard.entries.map { target ->
                            CustomAccessibilityAction(words.board(target)) { latestSelectIndex(target.ordinal); true }
                        }
                    }) {
                    val center = size.height / 2f
                    val halfHeight = 38.dp.toPx()
                    val left = 5.dp.toPx()
                    val right = 17.dp.toPx()
                    val x = (left + right) / 2f
                    // The housing never translates. Only the repeating surface markings rotate.
                    drawRoundRect(Brush.horizontalGradient(listOf(primary.copy(alpha = opacity * .08f),
                        primary.copy(alpha = opacity * .2f), primary.copy(alpha = opacity * .08f)), left, right),
                        Offset(left, center - halfHeight), Size(right - left, halfHeight * 2), CornerRadius(6.dp.toPx()))
                    val pitch = 6.5.dp.toPx()
                    val phase = (position * 52.dp.toPx()) % pitch
                    clipRect(left, center - halfHeight, right, center + halfHeight) {
                        for (index in -7..7) {
                            val y = center + index * pitch - phase
                            val distance = (abs(y - center) / halfHeight).coerceIn(0f, 1f)
                            val halfWidth = (1f + (1f - distance * distance) * 4f).dp.toPx()
                            drawLine(primary.copy(alpha = opacity * (.65f - distance * .2f)),
                                Offset(x - halfWidth, y), Offset(x + halfWidth, y),
                                strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                }
                Box(Modifier.align(AbsoluteAlignment.CenterLeft).absoluteOffset(x = 5.dp).width(12.dp).height(2.5.dp)
                    .background(primary.copy(alpha = opacity), RoundedCornerShape(50)).testTag("board-slider-marker"))
                if (active) Surface(Modifier.align(AbsoluteAlignment.CenterLeft).absoluteOffset(x = 30.dp)
                    .testTag("board-slider-active"),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp)) {
                    Text(words.board(board), Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
