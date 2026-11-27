package io.github.binoverfl0w.zencampus.ui.design

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextDecoration

/** Native selectable content with explicit links; remote markup never executes. */
@Composable
fun ReadingText(text: String) {
    val color = MaterialTheme.colorScheme.primary
    val annotated = remember(text, color) { linkedText(text, color) }
    SelectionContainer { Text(annotated, style = MaterialTheme.typography.bodyLarge) }
}

internal fun linkedText(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    Regex("https?://[^\\s<>\"']+").findAll(text).forEach { match ->
        val url = match.value.trimEnd('.', ',', ';', ')')
        append(text.substring(cursor, match.range.first))
        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)))) {
            append(url)
        }
        append(match.value.substring(url.length))
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}
