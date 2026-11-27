package io.github.binoverfl0w.zencampus.desktop

import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import javax.swing.UIManager
import javax.swing.JMenuBar
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JButton
import javax.swing.plaf.basic.BasicMenuBarUI
import javax.swing.plaf.basic.BasicButtonUI
import javax.swing.plaf.ColorUIResource
import javax.swing.plaf.FontUIResource

internal var desktopDark = false
    private set

internal fun prepareDesktopFonts() {
    DesktopContainer::class.java.getResourceAsStream("/composeResources/io.github.binoverfl0w.zencampus.resources/font/manrope_regular.ttf")?.use {
        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(Font.createFont(Font.TRUETYPE_FONT, it))
    }
}
internal fun desktopFont(size: Int = 14) = Font("Manrope", Font.PLAIN, size)

internal fun styleDesktopButton(button: JButton, dark: Boolean) {
    // Native look-and-feel gradients can ignore UIManager colors after Chromium starts.
    button.setUI(BasicButtonUI())
    button.background = if (dark) Color(0x293b46) else Color(0xdce8eb)
    button.foreground = if (dark) Color(0xe1e7e9) else Color(0x33454d)
    button.font = desktopFont()
    button.isOpaque = true
    button.isContentAreaFilled = true
    button.border = javax.swing.BorderFactory.createCompoundBorder(
        javax.swing.BorderFactory.createLineBorder(if (dark) Color(0x526875) else Color(0x9cafb5)),
        javax.swing.BorderFactory.createEmptyBorder(7, 12, 7, 12),
    )
}

internal fun styleDesktopChrome(dark: Boolean) {
    desktopDark = dark
    val background = ColorUIResource(if (dark) Color(0x171e23) else Color(0xf5f6f4))
    val foreground = ColorUIResource(if (dark) Color(0xe1e7e9) else Color(0x33454d))
    val selection = ColorUIResource(if (dark) Color(0x304956) else Color(0xdce8eb))
    val widgets = listOf("Button", "Label", "Panel", "OptionPane", "TextField")
    val menus = if (isMac) emptyList() else listOf("MenuBar", "Menu", "MenuItem", "PopupMenu")
    for (widget in menus + widgets) {
        UIManager.put("$widget.font", FontUIResource(desktopFont()))
        UIManager.put("$widget.background", background)
        UIManager.put("$widget.foreground", foreground)
        UIManager.put("$widget.selectionBackground", selection)
        UIManager.put("$widget.selectionForeground", foreground)
    }
    if (!isMac) {
        UIManager.put("MenuBar.border", javax.swing.BorderFactory.createEmptyBorder(3, 8, 3, 8))
        UIManager.put("MenuBar.gradient", null)
    }
    UIManager.put("Button.gradient", null)
}

internal fun styleDesktopMenuBar(bar: JMenuBar, dark: Boolean) {
    val background = if (dark) Color(0x171e23) else Color(0xf5f6f4)
    val foreground = if (dark) Color(0xe1e7e9) else Color(0x33454d)
    // Compose creates the Swing menu separately from its content. Apply after that menu exists.
    bar.setUI(BasicMenuBarUI())
    bar.isOpaque = true; bar.background = background; bar.foreground = foreground
    fun style(item: JMenuItem) {
        item.background = background; item.foreground = foreground; item.font = desktopFont()
        if (item is JMenu) {
            item.popupMenu.background = background
            item.menuComponents.filterIsInstance<JMenuItem>().forEach(::style)
        }
    }
    bar.components.filterIsInstance<JMenuItem>().forEach(::style)
    bar.repaint()
}
