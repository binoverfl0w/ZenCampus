package io.github.binoverfl0w.zencampus.desktop

import io.github.binoverfl0w.zencampus.data.SavedSignIn
import io.github.binoverfl0w.zencampus.data.SavedSignInPopup
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.service
import io.github.binoverfl0w.zencampus.ui.services.savedSignInAs
import io.github.binoverfl0w.zencampus.ui.services.savedSignInUsername
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.*

internal class DesktopSavedSignInBar(
    private val popup: SavedSignInPopup,
    private val words: Words,
    private val owner: Component,
    private val currentPage: () -> String?,
    private val fill: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEADING, 8, 4)) {
    private val choose = JButton()
    private val add = JButton(words.service("saved_add"))
    private val edit = JButton(words.service("saved_edit"))

    init {
        isOpaque = false
        listOf(choose, add, edit).forEach { button -> styleDesktopButton(button, desktopDark); add(button) }
        choose.addActionListener {
            JPopupMenu().apply {
                isLightWeightPopupEnabled = false
                popup.entries.forEach { entry ->
                    val title = listOfNotNull(words.savedSignInAs(entry.username), entry.label, entry.site).joinToString(" · ")
                    add(JMenuItem(title).apply {
                        addActionListener {
                            if (popup.pick(entry.id)) { refresh(); fill() }
                        }
                    })
                }
            }.show(choose, 0, choose.height)
        }
        add.addActionListener { editEntry(null) }
        edit.addActionListener { popup.selected?.let(::editEntry) }
        refresh()
    }

    fun refresh() {
        val selected = popup.selected
        choose.text = selected?.let { words.savedSignInAs(it.username) } ?: words.service("saved_choose")
        choose.isVisible = popup.entries.isNotEmpty()
        edit.isVisible = selected != null
        revalidate()
        repaint()
    }

    private fun editEntry(entry: SavedSignIn?) {
        val site = entry?.site ?: popup.siteForPage(currentPage())
        val username = JTextField(entry?.username.orEmpty(), 28)
        val password = JPasswordField(28)
        val label = JTextField(entry?.label.orEmpty(), 28)
        val mask = password.echoChar
        val show = JCheckBox(words.service("saved_show_password")).apply {
            addActionListener { password.echoChar = if (isSelected) '\u0000' else mask }
        }
        val form = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            fun field(name: String, input: JTextField) {
                add(JLabel(name).apply { labelFor = input })
                input.alignmentX = LEFT_ALIGNMENT
                add(input)
                add(Box.createVerticalStrut(6))
            }
            add(JLabel(words.service("saved_details")))
            add(JLabel(site))
            add(Box.createVerticalStrut(8))
            field(words.savedSignInUsername(popup.kind), username)
            field(words.service("saved_password"), password)
            if (entry != null) add(JLabel(words.service("saved_keep_password")))
            add(show)
            field(words.service("saved_label"), label)
        }
        val title = words.service(if (entry == null) "saved_add" else "saved_edit")
        val save = JButton(words.service("saved_save"))
        val cancel = JButton(words.text("cancel"))
        val dialog = JDialog(SwingUtilities.getWindowAncestor(owner), title, java.awt.Dialog.ModalityType.APPLICATION_MODAL)
        dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        dialog.contentPane = JPanel(java.awt.BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(16, 16, 12, 16)
            add(form, java.awt.BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.TRAILING)).apply { add(cancel); add(save) }, java.awt.BorderLayout.SOUTH)
        }
        dialog.rootPane.defaultButton = save
        cancel.addActionListener { dialog.dispose() }
        save.addActionListener {
            val secret = password.password
            try {
                if (username.text.isBlank() || (entry == null && secret.isEmpty())) {
                    if (username.text.isBlank()) username.requestFocusInWindow() else password.requestFocusInWindow()
                    return@addActionListener
                }
                save.isEnabled = false
                popup.save(site, username.text, String(secret), label.text, entry?.id) { success ->
                    if (success) {
                        dialog.dispose()
                        refresh()
                        fill()
                    } else if (dialog.isDisplayable) {
                        save.isEnabled = true
                        JOptionPane.showMessageDialog(dialog, words.service("saved_storage_error"), title, JOptionPane.ERROR_MESSAGE)
                    }
                }
            } finally {
                secret.fill('\u0000')
            }
        }
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true
        password.text = ""
    }
}
