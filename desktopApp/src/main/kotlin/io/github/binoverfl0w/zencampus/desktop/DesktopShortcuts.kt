package io.github.binoverfl0w.zencampus.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.MenuSelectionManager

internal enum class DesktopShortcut { BACK, STUDY, LIBRARY, MENSA, REFRESH, QUIT }

internal fun desktopKeyShortcut(key: Key, mac: Boolean = isMac): KeyShortcut =
    KeyShortcut(key, ctrl = !mac, meta = mac)

internal fun desktopShortcut(event: KeyEvent, mac: Boolean = isMac): DesktopShortcut? {
    val primary = if (mac) event.isMetaDown else event.isControlDown
    return when {
        primary && event.keyCode == KeyEvent.VK_Q -> DesktopShortcut.QUIT
        primary && event.keyCode == KeyEvent.VK_1 -> DesktopShortcut.STUDY
        primary && event.keyCode == KeyEvent.VK_2 -> DesktopShortcut.LIBRARY
        primary && event.keyCode == KeyEvent.VK_3 -> DesktopShortcut.MENSA
        primary && event.keyCode == KeyEvent.VK_R -> DesktopShortcut.REFRESH
        event.keyCode == KeyEvent.VK_ESCAPE || event.isAltDown && event.keyCode == KeyEvent.VK_LEFT -> DesktopShortcut.BACK
        else -> null
    }
}

/** Window shortcuts also work before a text field or another Compose control gains focus. */
internal fun installDesktopShortcuts(window: Window, action: (DesktopShortcut) -> Boolean): AutoCloseable {
    val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val dispatcher = KeyEventDispatcher { event ->
        if (manager.focusedWindow !== window || event.id != KeyEvent.KEY_PRESSED || event.isConsumed) return@KeyEventDispatcher false
        val menus = MenuSelectionManager.defaultManager()
        if (event.keyCode == KeyEvent.VK_ESCAPE && menus.selectedPath.isNotEmpty()) return@KeyEventDispatcher false
        val command = desktopShortcut(event)
        if (command == null || !action(command)) false else {
            menus.clearSelectedPath(); event.consume(); true
        }
    }
    manager.addKeyEventDispatcher(dispatcher)
    return AutoCloseable { manager.removeKeyEventDispatcher(dispatcher) }
}
