package io.github.binoverfl0w.zencampus.desktop

import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.*
import io.github.binoverfl0w.zencampus.ui.design.DialogButton
import io.github.binoverfl0w.zencampus.ui.design.IconAction
import io.github.binoverfl0w.zencampus.ui.design.LocalBackgroundWorkWhileClosed
import io.github.binoverfl0w.zencampus.ui.design.LocalKeyboardShortcutModifier
import io.github.binoverfl0w.zencampus.ui.design.LocalKeyboardShortcuts
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.ZenDialog
import io.github.binoverfl0w.zencampus.ui.design.ZenTheme
import io.github.binoverfl0w.zencampus.ui.study.LocalAssignmentUpload
import io.github.binoverfl0w.zencampus.ui.study.LocalCampusBrowser
import io.github.binoverfl0w.zencampus.ui.study.LocalChooseMaterialFolder
import io.github.binoverfl0w.zencampus.ui.study.LocalVipsReader
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import kotlinx.coroutines.*

fun main(args: Array<String>) {
    if (isMac) {
        System.setProperty("apple.laf.useScreenMenuBar", "true")
        System.setProperty("apple.awt.application.name", "ZenCampus")
    }
    if (args.contains("--help")) {
        println("ZenCampus desktop\n  --data-dir PATH   Separate local profile\n  --help            Show this help")
        return
    }
    var root: File? = null
    var argument = 0
    while (argument < args.size) {
        if (args[argument] != "--data-dir" || argument + 1 >= args.size) {
            System.err.println("Unknown or incomplete option. Use --help.")
            return
        }
        root = File(args[argument + 1])
        argument += 2
    }
    val paths = DesktopPaths(root)
    val channel = FileChannel.open(File(paths.data, ".running").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val lock = channel.tryLock()
    if (lock == null) {
        channel.close()
        JOptionPane.showMessageDialog(null, "ZenCampus is already running for this profile. Check your open windows.")
        return
    }
    val release = DesktopRelease.load()
    val log = startDesktopDiagnostics(paths, release)
    val container = DesktopContainer(paths, release = release, log = log, arguments = args.toList())
    val browser = DesktopBrowser(container)
    prepareDesktopFonts()
    application {
        val savedWindow = remember { container.preferences.read() }
        val state = rememberWindowState(width = savedWindow.width.coerceIn(640, 2400).dp, height = savedWindow.height.coerceIn(500, 1600).dp)
        var closing by remember { mutableStateOf(false) }
        var mainWindowActive by remember { mutableStateOf(false) }
        var pdf by remember { mutableStateOf<MaterialPresentation?>(null) }
        var back by remember { mutableStateOf<(() -> Unit)?>(null) }
        var refresh by remember { mutableStateOf<(() -> Unit)?>(null) }
        var request by remember { mutableStateOf("") }
        var help by remember { mutableStateOf(false) }
        var saveUi by remember { mutableStateOf<(() -> Unit)?>(null) }
        var mainWindow by remember { mutableStateOf<java.awt.Window?>(null) }
        var returnToCards by remember { mutableStateOf(false) }
        val uiScope = rememberCoroutineScope()
        val desktopFocus = remember { FocusRequester() }
        val settings by container.settings.collectAsState()
        val repository by container.repository.collectAsState()
        val ready by container.ready.collectAsState()
        val network by container.network.collectAsState()
        val notice by container.notice.collectAsState()
        val words = desktopWords(settings)
        val dark = settings.theme == ThemeMode.DARK || settings.theme == ThemeMode.SYSTEM && isSystemInDarkTheme()
        val openUrl: (String) -> Unit = { url ->
            uiScope.launch { if (!openExternal(url)) container.notice.value = words.desktop("external_link_failed") }
        }
        fun navigate(route: String) { request = "$route#${System.nanoTime()}" }
        LaunchedEffect(repository) { if (repository != null && returnToCards) { returnToCards = false; navigate("accounts") } }
        fun close() {
            if (closing) return
            runCatching { saveUi?.invoke() }
            closing = true
            // Hide immediately; the composition and its coroutine scope must stay alive until cleanup finishes.
            mainWindow?.isVisible = false
            pdf = null
            uiScope.launch {
                withContext(NonCancellable) {
                    try {
                        runCatching { container.preferences.update { it.copy(width = state.size.width.value.toInt(), height = state.size.height.value.toInt()) } }
                        closeDesktopResources(browser::close, container::close)
                    } finally {
                        try { lock.release() } finally { try { channel.close() } finally { exitApplication() } }
                    }
                }
            }
        }
        DisposableEffect(Unit) {
            val desktop = if (isMac && Desktop.isDesktopSupported()) Desktop.getDesktop() else null
            val quitDesktop = desktop?.takeIf { it.isSupported(Desktop.Action.APP_QUIT_HANDLER) }
            quitDesktop?.setQuitHandler { _, response ->
                // Compose owns process exit after the asynchronous cleanup.
                response.cancelQuit()
                close()
            }
            onDispose { quitDesktop?.setQuitHandler(null) }
        }
        SideEffect {
            container.materials.onPdf = { pdf = it }
            container.closePrivateReaders = { pdf = null; browser.closeVipsWindow() }
            browser.onQuit = ::close
            container.updatePlatform.quit = ::close
        }
        Window(onCloseRequest = ::close, title = "ZenCampus", state = state, visible = !closing, icon = ZenWindowIcon) {
            SideEffect { window.minimumSize = Dimension(640, 500); if (mainWindow !== window) mainWindow = window }
            LaunchedEffect(Unit) { desktopFocus.requestFocus() }
            LaunchedEffect(dark) {
                styleDesktopChrome(dark)
                if (!isMac) {
                    while (window.jMenuBar == null) delay(25)
                    styleDesktopMenuBar(window.jMenuBar, dark)
                }
            }
            DisposableEffect(window) {
                mainWindowActive = window.isActive
                val listener = object : WindowAdapter() {
                    override fun windowActivated(e: WindowEvent) { mainWindowActive = true; container.foreground() }
                    override fun windowDeactivated(e: WindowEvent) { mainWindowActive = false }
                }
                window.addWindowListener(listener)
                val shortcuts = installDesktopShortcuts(window) { command ->
                    when(command) {
                        DesktopShortcut.BACK -> { back?.invoke(); back != null }
                        DesktopShortcut.STUDY -> { navigate("study"); true }
                        DesktopShortcut.LIBRARY -> { navigate("library-board"); true }
                        DesktopShortcut.MENSA -> { navigate("mensa"); true }
                        DesktopShortcut.REFRESH -> { refresh?.invoke(); true }
                        DesktopShortcut.QUIT -> { close(); true }
                    }
                }
                onDispose { shortcuts.close(); window.removeWindowListener(listener) }
            }
            MenuBar {
                Menu("ZenCampus") {
                    Item(words.service("your_cards"), onClick = { navigate("accounts") })
                    Item(words.desktop("local_files"), onClick = { uiScope.launch { runCatching { externalOpen(paths.data) } } })
                    if (!isMac) {
                        Separator()
                        Item(words.desktop("quit"), shortcut = desktopKeyShortcut(Key.Q), onClick = ::close)
                    }
                }
                Menu(words.desktop("boards")) {
                    Item(words.board(CampusBoard.STUDY), shortcut = desktopKeyShortcut(Key.One), onClick = { navigate("study") })
                    Item(words.board(CampusBoard.LIBRARY), shortcut = desktopKeyShortcut(Key.Two), onClick = { navigate("library-board") })
                    Item(words.board(CampusBoard.MENSA), shortcut = desktopKeyShortcut(Key.Three), onClick = { navigate("mensa") })
                }
                Menu(words.desktop("navigate")) {
                    Item(words.text("back"), shortcut = KeyShortcut(Key.DirectionLeft, alt = true), enabled = back != null, onClick = { back?.invoke() })
                    Item(words.text("refresh"), shortcut = desktopKeyShortcut(Key.R), enabled = refresh != null, onClick = { refresh?.invoke() })
                    Item(words.ui("search"), shortcut = desktopKeyShortcut(Key.K), onClick = { navigate("search") })
                }
                Menu(words.desktop("help")) { Item(words.desktop("controls"), onClick = { help = true }) }
            }
            ZenTheme(settings.theme) {
                Column(Modifier.fillMaxSize().focusRequester(desktopFocus).focusable()) {
                    if (notice != null) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(notice!!, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            IconAction(Symbol.CLOSE, words.text("close"), { container.notice.value = null })
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        if (!closing) key(repository?.account?.cacheScope ?: "public") {
                            PersistentDesktopState(container, repository?.account?.cacheScope ?: "public", onSaveReady = { saveUi = it }) {
                                CompositionLocalProvider(LocalVipsReader provides browser::vips,
                                    LocalCampusBrowser provides { url -> browser.campus(null, url, "DigiCampus") },
                                    LocalBackgroundWorkWhileClosed provides false,
                                    LocalKeyboardShortcuts provides true,
                                    LocalKeyboardShortcutModifier provides if (isMac) "⌘" else null,
                                    LocalChooseMaterialFolder provides { course ->
                                        val current = repository
                                        if (current != null) {
                                            val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; isAcceptAllFileFilterUsed = false }
                                            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                                val selected = chooser.selectedFile
                                                if (selected != null && selected.isDirectory)
                                                    uiScope.launch { current.setMaterialDestination(course, MaterialDestination(selected.absolutePath, selected.absolutePath)) }
                                            }
                                        }
                                    },
                                    LocalAssignmentUpload provides { course, folder ->
                                        val owner = repository?.account?.cacheScope
                                        if (owner != null) FileDialog(null as Frame?, words.action("choose_file"), FileDialog.LOAD).run {
                                            isVisible = true
                                            val selected = file?.let { File(directory, it) }; dispose()
                                            if (selected != null) container.selectAssignment(selected, owner, course, folder)
                                        }
                                    }) {
                                    fun openStudySignIn(expired: Boolean) {
                                        if (window.isActive) {
                                            returnToCards = true
                                            browser.signIn(SignInService.DIGICAMPUS, expired)
                                        }
                                    }
                                    ZenApp(repository, settings, network, ready, Locale.getDefault().language,
                                        onSignIn = { openStudySignIn(false) },
                                        onAutomaticSignIn = { openStudySignIn(true) },
                                        onSignOut = container::signOut, onSettings = container::setSettings, onOpenUrl = openUrl,
                                        onBackHandler = { back = it },
                                        services = if (repository?.account?.demo == true) container.previewServices else container.services,
                                        onConnectLibrary = {
                                            if (repository?.account?.demo == true) container.connectPreviewLibrary() else browser.signIn(SignInService.LIBRARY, container.services.library.value.error == FailureKind.AUTHENTICATION)
                                        }, onDisconnectLibrary = { removeSaved -> container.disconnectLibrary(repository?.account?.demo == true, removeSaved) },
                                        onLibraryReminders = container::libraryReminders, serviceRequest = request, onRefreshHandler = { refresh = it },
                                        appVersion = release.version, maintenance = container.maintenance,
                                        signInSession = container.signInSession, signInForeground = mainWindowActive && !closing)
                                }
                            }
                        }
                    }
                }
                if (help) ZenDialog(onDismissRequest = { help = false }, title = { Text(words.desktop("controls")) },
                    text = { Text(words.desktopShortcutHelp(), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall) },
                    confirmButton = { DialogButton(words.text("close"), { help = false }) })
            }
        }
        if (pdf != null && !closing) key(pdf!!.file.path) { PdfWindow(pdf!!, container, onQuit = ::close) { pdf = null } }
    }
}

/** Both independent owners must be closed even if one fails. */
internal suspend fun closeDesktopResources(closeBrowser: suspend () -> Unit, closeContainer: suspend () -> Unit) {
    try { closeBrowser() } finally { closeContainer() }
}


/** The window and taskbar icon: the leaf on its tile, as in the app's header and the installed launcher icon. */
private object ZenWindowIcon : androidx.compose.ui.graphics.painter.Painter() {
    override val intrinsicSize = androidx.compose.ui.geometry.Size(256f, 256f)
    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        val unit = size.minDimension / 108f
        val ink = androidx.compose.ui.graphics.Color(0xFF1C3A4D)
        drawRoundRect(androidx.compose.ui.graphics.Color(0xFFDDE8EF), size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(25f * unit))
        // The 24-unit leaf glyph, scaled 2.5x and centred on its drawn outline, as in the installed SVG icon.
        val k = 2.5f * unit
        fun p(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(23.54f * unit + x * k, 24.46f * unit + y * k)
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.7f * k, cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val leaf = androidx.compose.ui.graphics.Path().apply {
            p(4f, 20f).let { moveTo(it.x, it.y) }
            val (a, b, c) = listOf(p(1f, 7f), p(9f, 3f), p(21f, 3f)); cubicTo(a.x, a.y, b.x, b.y, c.x, c.y)
            val (d, e, f) = listOf(p(21f, 15f), p(17f, 23f), p(4f, 20f)); cubicTo(d.x, d.y, e.x, e.y, f.x, f.y)
            close()
        }
        drawPath(leaf, ink, style = stroke)
        drawLine(ink, p(4f, 20f), p(15f, 9f), 1.7f * k, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
