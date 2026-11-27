package io.github.binoverfl0w.zencampus.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopShortcutsTest {
    @Test fun primaryModifierMatchesThePlatform() {
        val keys = mapOf(
            KeyEvent.VK_1 to DesktopShortcut.STUDY,
            KeyEvent.VK_2 to DesktopShortcut.LIBRARY,
            KeyEvent.VK_3 to DesktopShortcut.MENSA,
            KeyEvent.VK_R to DesktopShortcut.REFRESH,
            KeyEvent.VK_Q to DesktopShortcut.QUIT,
        )
        val source = Canvas()
        for ((code, command) in keys) {
            val control = KeyEvent(source, KeyEvent.KEY_PRESSED, 0, InputEvent.CTRL_DOWN_MASK, code, KeyEvent.CHAR_UNDEFINED)
            val commandKey = KeyEvent(source, KeyEvent.KEY_PRESSED, 0, InputEvent.META_DOWN_MASK, code, KeyEvent.CHAR_UNDEFINED)
            assertEquals(command, desktopShortcut(control, mac = false))
            assertNull(desktopShortcut(control, mac = true))
            assertEquals(command, desktopShortcut(commandKey, mac = true))
            assertNull(desktopShortcut(commandKey, mac = false))
        }
        val typing = KeyEvent(source, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_R, 'r')
        assertNull(desktopShortcut(typing, mac = true))
        assertNull(desktopShortcut(typing, mac = false))
    }

    @Test fun swingMenuAcceleratorUsesTheSameModifier() {
        assertEquals(KeyShortcut(Key.K, meta = true), desktopKeyShortcut(Key.K, mac = true))
        assertEquals(KeyShortcut(Key.K, ctrl = true), desktopKeyShortcut(Key.K, mac = false))
    }
}
