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

// The app's line icons, drawn on a 24-unit grid so they stay crisp at any size.

enum class Symbol { TODAY, COURSES, CALENDAR, SETTINGS, BACK, NEXT, DOWN, CARD, REFRESH, SEARCH, PIN, PIN_FILLED, GROUP, EDIT, FOLDER, FILE, DOWNLOAD, CHECK, MORE, SHARE, CLOSE, LEAF, LOCATION, EXTERNAL, CLOCK, CAMPUS, DINING, MAIL, PEOPLE, FORUM, TASK, BELL, PLUS, MINUS, STAR, STAR_FILLED,
    WEATHER_CLEAR, WEATHER_CLOUD, WEATHER_RAIN, WEATHER_SNOW, WEATHER_FOG, WEATHER_STORM, WEATHER_NIGHT }

@Composable
fun ZenIcon(symbol: Symbol, modifier: Modifier = Modifier.size(22.dp), color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier) {
        val stroke = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1,y1), Offset(x2,y2), 1.7f, StrokeCap.Round)
            fun outline(path: Path) = drawPath(path, color, style = stroke)
            fun cloud() = outline(Path().apply {
                moveTo(6f,17f); cubicTo(0f,17f,1f,10f,6f,10f)
                cubicTo(7f,3f,17f,3f,18f,10f); cubicTo(24f,9f,24f,17f,19f,17f); close()
            })
            when (symbol) {
                Symbol.DOWN -> { line(5f,9f,12f,16f); line(12f,16f,19f,9f) }
                Symbol.CARD -> {
                    drawRoundRect(color, Offset(3f,6f), Size(18f,14f), CornerRadius(2f), style = stroke)
                    outline(Path().apply { moveTo(6f,3f); lineTo(19f,3f); quadraticTo(22f,3f,22f,6f) })
                    line(7f,11f,11f,11f); line(7f,15f,15f,15f)
                }
                Symbol.CAMPUS -> {
                    outline(Path().apply { moveTo(2f,9f); lineTo(12f,3f); lineTo(22f,9f); close() })
                    line(5f,10f,5f,20f); line(12f,10f,12f,20f); line(19f,10f,19f,20f); line(3f,21f,21f,21f)
                }
                Symbol.DINING -> {
                    line(5f,3f,5f,8f); line(8f,3f,8f,8f); line(11f,3f,11f,8f)
                    outline(Path().apply { moveTo(5f,8f); quadraticTo(8f,13f,11f,8f) }); line(8f,11f,8f,21f)
                    drawOval(color, Offset(16f,3f), Size(5f,9f), style = stroke); line(18.5f,12f,18.5f,21f)
                }
                Symbol.WEATHER_CLEAR -> {
                    drawCircle(color, 4f, Offset(12f,12f), style = stroke)
                    for (i in 0..7) {
                        val angle = i * kotlin.math.PI / 4
                        line((12 + 7 * kotlin.math.cos(angle)).toFloat(), (12 + 7 * kotlin.math.sin(angle)).toFloat(),
                            (12 + 10 * kotlin.math.cos(angle)).toFloat(), (12 + 10 * kotlin.math.sin(angle)).toFloat())
                    }
                }
                Symbol.WEATHER_NIGHT -> outline(Path().apply {
                    moveTo(18f,17f); cubicTo(6f,21f,3f,7f,11f,3f)
                    cubicTo(9f,10f,11f,16f,18f,17f); close()
                })
                Symbol.WEATHER_CLOUD -> cloud()
                Symbol.WEATHER_RAIN -> { cloud(); line(7f,20f,6f,22f); line(12f,20f,11f,22f); line(17f,20f,16f,22f) }
                Symbol.WEATHER_SNOW -> { cloud(); drawCircle(color,1f,Offset(7f,21f)); drawCircle(color,1f,Offset(12f,21f)); drawCircle(color,1f,Offset(17f,21f)) }
                Symbol.WEATHER_FOG -> { line(5f,7f,19f,7f); line(2f,12f,22f,12f); line(5f,17f,19f,17f) }
                Symbol.WEATHER_STORM -> { cloud(); outline(Path().apply { moveTo(13f,15f); lineTo(10f,19f); lineTo(14f,19f); lineTo(11f,23f) }) }
                Symbol.TODAY -> {
                    drawRoundRect(color, Offset(3f,3f), Size(18f,18f), CornerRadius(3f), style = stroke)
                    drawCircle(color, 1.6f, Offset(7f,8f))
                    line(11f,8f,17f,8f); line(7f,13f,17f,13f); line(7f,17f,14f,17f)
                }
                Symbol.COURSES -> {
                    outline(Path().apply { moveTo(3f,5f); quadraticTo(7f,3f,12f,6f); quadraticTo(17f,3f,21f,5f); lineTo(21f,20f); quadraticTo(17f,18f,12f,21f); quadraticTo(7f,18f,3f,20f); close() })
                    line(12f,6f,12f,21f)
                }
                Symbol.CALENDAR -> {
                    drawRoundRect(color, Offset(3f,5f), Size(18f,16f), CornerRadius(2f), style = stroke)
                    line(3f,10f,21f,10f); line(8f,3f,8f,7f); line(16f,3f,16f,7f)
                    drawCircle(color,1f,Offset(8f,14f)); drawCircle(color,1f,Offset(12f,14f)); drawCircle(color,1f,Offset(16f,14f))
                }
                Symbol.SETTINGS -> {
                    line(4f,6f,20f,6f); line(4f,12f,20f,12f); line(4f,18f,20f,18f)
                    drawCircle(color,2f,Offset(9f,6f), style = stroke); drawCircle(color,2f,Offset(16f,12f), style = stroke); drawCircle(color,2f,Offset(8f,18f), style = stroke)
                }
                Symbol.BACK -> { line(14f,5f,7f,12f); line(7f,12f,14f,19f) }
                Symbol.NEXT -> { line(9f,5f,16f,12f); line(16f,12f,9f,19f) }
                Symbol.REFRESH -> {
                    drawArc(color,45f,290f,false,Offset(4f,4f),Size(16f,16f),style=stroke)
                    line(19f,4f,20f,10f); line(20f,10f,14f,9f)
                }
                Symbol.SEARCH -> { drawCircle(color,6.5f,Offset(10f,10f),style=stroke); line(15f,15f,21f,21f) }
                Symbol.PIN, Symbol.PIN_FILLED -> {
                    val pin = Path().apply { moveTo(7f,3f); lineTo(17f,3f); lineTo(16f,10f); lineTo(19f,14f); lineTo(5f,14f); lineTo(8f,10f); close() }
                    if (symbol == Symbol.PIN_FILLED) drawPath(pin, color) else outline(pin)
                    line(12f,14f,12f,22f)
                }
                Symbol.GROUP -> {
                    outline(Path().apply { moveTo(6f,3f); lineTo(21f,3f); lineTo(21f,18f) })
                    drawRoundRect(color, Offset(3f,6f), Size(15f,15f), CornerRadius(2f), style = stroke)
                    line(7f,11f,14f,11f); line(7f,16f,14f,16f)
                }
                Symbol.EDIT -> {
                    outline(Path().apply { moveTo(4f,16f); lineTo(16f,4f); lineTo(20f,8f); lineTo(8f,20f); lineTo(3f,21f); close() })
                    line(14f,6f,18f,10f)
                }
                Symbol.FOLDER -> outline(Path().apply { moveTo(3f,7f); lineTo(3f,20f); lineTo(21f,20f); lineTo(21f,7f); lineTo(11f,7f); lineTo(9f,4f); lineTo(3f,4f); close() })
                Symbol.FILE -> {
                    outline(Path().apply { moveTo(5f,2f); lineTo(14f,2f); lineTo(20f,8f); lineTo(20f,22f); lineTo(5f,22f); close(); moveTo(14f,2f); lineTo(14f,8f); lineTo(20f,8f) })
                    line(9f,13f,16f,13f); line(9f,17f,16f,17f)
                }
                Symbol.DOWNLOAD -> { line(12f,3f,12f,16f); line(7f,11f,12f,16f); line(12f,16f,17f,11f); outline(Path().apply{moveTo(4f,17f);lineTo(4f,21f);lineTo(20f,21f);lineTo(20f,17f)}) }
                Symbol.CHECK -> { line(5f,12f,10f,17f); line(10f,17f,20f,6f) }
                Symbol.MORE -> { listOf(5f,12f,19f).forEach { drawCircle(color,1.5f,Offset(12f,it)) } }
                Symbol.SHARE -> {
                    line(8f,11f,16f,6f); line(8f,13f,16f,18f)
                    listOf(Offset(5f,12f), Offset(19f,4f), Offset(19f,20f)).forEach { drawCircle(color,3f,it,style=stroke) }
                }
                Symbol.CLOSE -> { line(5f,5f,19f,19f); line(19f,5f,5f,19f) }
                Symbol.LEAF -> {
                    outline(Path().apply { moveTo(4f,20f); cubicTo(1f,7f,9f,3f,21f,3f); cubicTo(21f,15f,17f,23f,4f,20f); close() })
                    line(4f,20f,15f,9f)
                }
                Symbol.LOCATION -> {
                    outline(Path().apply { moveTo(12f,22f); cubicTo(9f,18f,4f,13f,4f,9f); cubicTo(4f,0f,20f,0f,20f,9f); cubicTo(20f,13f,15f,18f,12f,22f); close() })
                    drawCircle(color,2.5f,Offset(12f,9f),style=stroke)
                }
                Symbol.EXTERNAL -> { outline(Path().apply {moveTo(10f,4f);lineTo(4f,4f);lineTo(4f,20f);lineTo(20f,20f);lineTo(20f,14f)});line(12f,12f,21f,3f);line(14f,3f,21f,3f);line(21f,3f,21f,10f) }
                Symbol.CLOCK -> { drawCircle(color,9f,Offset(12f,12f),style=stroke);line(12f,6f,12f,12f);line(12f,12f,16f,14f) }
                Symbol.MAIL -> {
                    drawRoundRect(color, Offset(3f, 5f), Size(18f, 14f), CornerRadius(2.5f), style = stroke)
                    outline(Path().apply { moveTo(3.5f, 6.5f); lineTo(12f, 13f); lineTo(20.5f, 6.5f) })
                }
                Symbol.PEOPLE -> {
                    drawCircle(color, 3.5f, Offset(12f, 8f), style = stroke)
                    outline(Path().apply { moveTo(5f, 20f); cubicTo(5f, 15.2f, 19f, 15.2f, 19f, 20f) })
                }
                Symbol.STAR, Symbol.STAR_FILLED -> {
                    val star = Path().apply {
                        val points = (0 until 10).map { index ->
                            val angle = -kotlin.math.PI / 2 + index * kotlin.math.PI / 5
                            val radius = if (index % 2 == 0) 9.5f else 4f
                            Offset((12 + radius * kotlin.math.cos(angle)).toFloat(), (12.5f + radius * kotlin.math.sin(angle)).toFloat())
                        }
                        moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) }; close()
                    }
                    if (symbol == Symbol.STAR_FILLED) drawPath(star, color) else outline(star)
                }
                Symbol.PLUS -> { line(5f,12f,19f,12f); line(12f,5f,12f,19f) }
                Symbol.MINUS -> line(5f,12f,19f,12f)
                Symbol.TASK -> {
                    outline(Path().apply { moveTo(6f,2.5f); lineTo(14f,2.5f); lineTo(19f,7.5f); lineTo(19f,21.5f); lineTo(6f,21.5f); close(); moveTo(14f,2.5f); lineTo(14f,7.5f); lineTo(19f,7.5f) })
                    line(9f,14f,11f,16f); line(11f,16f,15f,12f)
                }
                Symbol.BELL -> {
                    outline(Path().apply { moveTo(6f,16f); lineTo(6f,10f); cubicTo(6f,2.5f,18f,2.5f,18f,10f); lineTo(18f,16f); lineTo(20f,18f); lineTo(4f,18f); close() })
                    line(10f,21f,14f,21f)
                }
                Symbol.FORUM -> {
                    outline(Path().apply {
                        moveTo(4f, 6f); quadraticTo(4f, 4f, 6f, 4f); lineTo(18f, 4f)
                        quadraticTo(20f, 4f, 20f, 6f); lineTo(20f, 14f); quadraticTo(20f, 16f, 18f, 16f)
                        lineTo(9f, 16f); lineTo(5f, 20f); lineTo(5f, 16f); quadraticTo(4f, 16f, 4f, 14f); close()
                    })
                    line(8f, 8.5f, 16f, 8.5f); line(8f, 12f, 13f, 12f)
                }
            }
        }
    }
}

@Composable
fun IconAction(symbol: Symbol, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconAction(label, onClick, enabled = enabled) {
        ZenIcon(symbol, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant)
    }
}


@Composable
fun IconAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable () -> Unit) {
    IconButton(onClick, modifier.semantics { contentDescription = label }, enabled, content = content)
}
