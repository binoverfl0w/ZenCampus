package io.github.binoverfl0w.zencampus.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.*
import io.github.binoverfl0w.zencampus.ui.design.ButtonStyle
import io.github.binoverfl0w.zencampus.ui.design.IconAction
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.Tone
import io.github.binoverfl0w.zencampus.ui.design.ZenButton
import io.github.binoverfl0w.zencampus.ui.design.ZenChip
import io.github.binoverfl0w.zencampus.ui.design.ZenLink
import io.github.binoverfl0w.zencampus.ui.design.ZenNotice
import io.github.binoverfl0w.zencampus.ui.design.ZenStepper
import io.github.binoverfl0w.zencampus.ui.design.ZenTheme
import java.awt.Dimension
import java.io.File
import kotlinx.coroutines.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper

internal class PdfSession private constructor(private val document: PDDocument) : AutoCloseable {
    val pages = document.numberOfPages
    private val renderer = PDFRenderer(document)
    @Volatile private var closed = false
    @Synchronized fun render(page: Int, width: Int): ImageBitmap {
        check(!closed)
        val crop = document.getPage(page).cropBox
        val rotated = document.getPage(page).rotation % 180 != 0
        val pageWidth = if (rotated) crop.height else crop.width
        val pageHeight = if (rotated) crop.width else crop.height
        // Bound a page raster even for unusual PDFs or a maximized, highly zoomed display.
        val scale = minOf(width.coerceIn(160, 2200) / pageWidth, kotlin.math.sqrt(5_000_000f / (pageWidth * pageHeight)))
        return renderer.renderImage(page, scale).toComposeImageBitmap()
    }
    suspend fun find(query: String): List<Int> = withContext(Dispatchers.IO) {
        val matches = mutableListOf<Int>()
        for (page in 1..pages) {
            currentCoroutineContext().ensureActive()
            val text = synchronized(this@PdfSession) {
                check(!closed)
                PDFTextStripper().apply { startPage = page; endPage = page }.getText(document)
            }
            if (text.contains(query, true)) matches += page - 1
        }
        matches
    }
    @Synchronized override fun close() { if (!closed) { closed = true; document.close() } }
    companion object { fun open(file: File, password: String = "") = PdfSession(Loader.loadPDF(file, password)) }
}

@Composable
internal fun PdfWindow(info: MaterialPresentation, container: DesktopContainer, onQuit: () -> Unit = {}, onClose: () -> Unit) {
    val settings by container.settings.collectAsState()
    val words = desktopWords(settings)
    val scope = rememberCoroutineScope()
    var session by remember(info.file) { mutableStateOf<PdfSession?>(null) }
    var error by remember(info.file) { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var passwordNeeded by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Int>>(emptyList()) }
    var matchIndex by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var findJob by remember { mutableStateOf<Job?>(null) }
    var pageText by remember { mutableStateOf("") }
    var fileMenu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val windowState = rememberWindowState(width = 980.dp, height = 860.dp)
    LaunchedEffect(info.file, attempt) {
        var opened: PdfSession? = null
        try {
            withContext(Dispatchers.IO) { opened = PdfSession.open(info.file, password) }
            session = opened; passwordNeeded = false; error = null; password = ""
        } catch (e: CancellationException) { withContext(NonCancellable + Dispatchers.IO) { opened?.close() }; throw e
        } catch (_: org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException) { passwordNeeded = true; error = words.desktop("enter_password") }
        catch (_: Exception) { error = words.desktop("pdf_failed") }
    }
    DisposableEffect(session) { val opened = session; onDispose { findJob?.cancel(); container.scope.launch { opened?.close() } } }
    fun search() {
        val pdf = session ?: return
        findJob?.cancel()
        if (query.isBlank()) { matches = emptyList(); return }
        findJob = scope.launch {
            searching = true
            try {
                matches = pdf.find(query.trim()); matchIndex = 0; searched = true
                matches.firstOrNull()?.let { list.animateScrollToItem(it) }
            } finally { searching = false }
        }
    }
    fun saveCopy() { scope.launch { runCatching { container.materials.saveAs(info) }
        .onFailure { error = words.desktop("save_failed") } } }
    Window(onCloseRequest = onClose, title = "${info.name} · ZenCampus", state = windowState,
        onPreviewKeyEvent = {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onClose(); true }
            else if (it.type == KeyEventType.KeyDown && (if (isMac) it.isMetaPressed else it.isCtrlPressed) && it.key == Key.Q) { onQuit(); true }
            else if (it.type == KeyEventType.KeyDown && (if (isMac) it.isMetaPressed else it.isCtrlPressed) && it.key == Key.S) {
                saveCopy(); true
            } else false
        }) {
        SideEffect { window.minimumSize = Dimension(620, 500) }
        ZenTheme(settings.theme) {
            Scaffold(Modifier.testTag("desktop-pdf"), topBar = {
                Column {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconAction(Symbol.BACK, words.text("back"), onClose)
                        Text(info.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Box {
                            IconAction(Symbol.MORE, words.service("more_options"), { fileMenu = true })
                            DropdownMenu(fileMenu, { fileMenu = false }) {
                                DropdownMenuItem(text = { Text(words.desktop("save_copy")) }, onClick = { fileMenu = false; saveCopy() })
                                DropdownMenuItem(text = { Text(words.text("open_with")) }, onClick = {
                                    fileMenu = false
                                    scope.launch { runCatching { container.materials.openWith(info.file.path, info.mime, info.name) }
                                        .onFailure { error = words.desktop("viewer_failed") } }
                                })
                            }
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        ZenStepper(if (zoom == 1f) words.desktop("fit_width") else "${(zoom * 100).toInt()}%",
                            { zoom = (zoom - .25f).coerceAtLeast(.5f) }, { zoom = (zoom + .25f).coerceAtMost(3f) },
                            words.planner("zoom_out"), words.planner("zoom_in"), decreaseEnabled = zoom > .5f, increaseEnabled = zoom < 3f,
                            onLabel = { zoom = 1f })
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${list.firstVisibleItemIndex + 1} / ${session?.pages ?: "…"}", style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(pageText, { pageText = it.filter(Char::isDigit).take(6) }, label = { Text(words.text("page")) },
                            singleLine = true, modifier = Modifier.width(84.dp))
                        ZenLink(words.desktop("go"), { pageText.toIntOrNull()?.let { page -> session?.let { pdf ->
                            scope.launch { list.scrollToItem((page - 1).coerceIn(0, (pdf.pages - 1).coerceAtLeast(0))) }
                        } } })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(query, { query = it; searched = false }, singleLine = true, label = { Text(words.desktop("find_text")) }, modifier = Modifier.weight(1f)
                            .onKeyEvent { if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) { search(); true } else false })
                        ZenChip(words.desktop(if (searching) "searching" else "find"), { search() }, symbol = Symbol.SEARCH, enabled = !searching)
                        if (matches.isNotEmpty()) ZenChip("${matchIndex + 1}/${matches.size}", {
                            matchIndex = (matchIndex + 1) % matches.size
                            scope.launch { list.animateScrollToItem(matches[matchIndex]) }
                        }, trailing = Symbol.NEXT)
                    }
                    if (searched && !searching && matches.isEmpty()) Text(words.desktop("no_matches"), Modifier.padding(horizontal = 18.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                }
            }, bottomBar = { if (error != null && session != null) ZenNotice(error!!, Tone.DANGER, modifier = Modifier.padding(12.dp)) }) { padding ->
                BoxWithConstraints(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    val width = maxWidth
                    val pdf = session
                    if (pdf != null && pdf.pages > 0) {
                        val horizontal = rememberScrollState()
                        Box(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
                            LazyColumn(state = list, modifier = Modifier.width((width - 36.dp) * zoom + 36.dp).fillMaxHeight(),
                                contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                items(pdf.pages, key = { it }) { page ->
                                    var bitmap by remember(pdf, page, width, zoom) { mutableStateOf<ImageBitmap?>(null) }
                                    var failed by remember { mutableStateOf(false) }
                                    val pixels = with(androidx.compose.ui.platform.LocalDensity.current) { ((width - 36.dp) * zoom).roundToPx() }
                                    LaunchedEffect(pdf, page, pixels) {
                                        try { bitmap = withContext(Dispatchers.IO) { pdf.render(page, pixels) } }
                                        catch (e: CancellationException) { throw e }
                                        catch (_: Exception) { failed = true }
                                    }
                                    val image = bitmap
                                    if (image != null) Image(image, "${words.text("page")} ${page + 1}", Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White)
                                        .aspectRatio(image.width.toFloat() / image.height))
                                    else Box(Modifier.fillMaxWidth().height(600.dp), contentAlignment = Alignment.Center) {
                                        if (failed) Text("${words.text("page")} ${page + 1}: ${words.desktop("page_failed")}") else CircularProgressIndicator()
                                    }
                                }
                            }
                            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                        }
                        HorizontalScrollbar(rememberScrollbarAdapter(horizontal), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                    } else Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (error == null) CircularProgressIndicator() else Text(error!!)
                        if (passwordNeeded) {
                            OutlinedTextField(password, { password = it }, label = { Text(words.desktop("password")) },
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                            Spacer(Modifier.height(12.dp))
                            ZenButton(words.desktop("open"), { attempt++ }, style = ButtonStyle.PRIMARY)
                        }
                    }
                }
            }
        }
    }
}
