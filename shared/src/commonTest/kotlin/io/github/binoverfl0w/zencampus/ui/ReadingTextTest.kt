package io.github.binoverfl0w.zencampus.ui

import androidx.compose.ui.graphics.Color
import io.github.binoverfl0w.zencampus.data.digicampus.JsonApiMapper
import io.github.binoverfl0w.zencampus.ui.design.linkedText
import kotlin.test.*

class ReadingTextTest {
    @Test fun preservesSafeLinksAndRemovesExecutableMarkup() {
        val text = JsonApiMapper.plainText("""<p>Read <a href="/dispatch.php/courseware">the notes</a>.</p>
            <script>window.location='https://evil.example'</script>
            <a href="javascript:alert(1)">ordinary text</a>""")
        assertTrue(text.contains("the notes (https://digicampus.uni-augsburg.de/dispatch.php/courseware)"))
        assertFalse(text.contains("javascript"))
        assertFalse(text.contains("evil.example"))
        assertTrue(text.contains("ordinary text"))
        assertEquals(text, linkedText(text, Color.Green).text)
    }
}
