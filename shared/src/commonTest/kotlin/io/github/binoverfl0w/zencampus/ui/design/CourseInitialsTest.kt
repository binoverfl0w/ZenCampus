package io.github.binoverfl0w.zencampus.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals

class CourseInitialsTest {
    @Test fun initialsHandleUnicodePunctuationAndEmptyTitles() {
        assertEquals("DM", courseInitials("Diskrete Mathematik"))
        assertEquals("ÄP", courseInitials("Ästhetik & Pädagogik"))
        assertEquals("Ma", courseInitials("Mathematik"))
        assertEquals("3D", courseInitials("3-D Druck"))
        assertEquals("·", courseInitials("---"))
    }
}
