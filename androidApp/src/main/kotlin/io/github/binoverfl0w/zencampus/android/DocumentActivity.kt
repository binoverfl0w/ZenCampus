package io.github.binoverfl0w.zencampus.android

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import io.github.binoverfl0w.zencampus.model.AppLanguage
import io.github.binoverfl0w.zencampus.model.MaterialAction
import io.github.binoverfl0w.zencampus.model.SyncStatus
import io.github.binoverfl0w.zencampus.ui.*
import io.github.binoverfl0w.zencampus.ui.design.IconAction
import io.github.binoverfl0w.zencampus.ui.design.LocalWords
import io.github.binoverfl0w.zencampus.ui.design.Symbol
import io.github.binoverfl0w.zencampus.ui.design.ZenTheme
import java.io.File
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DocumentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val path = intent.getStringExtra("path") ?: run { finish(); return }
        val file = File(path).canonicalFile
        if (!file.path.startsWith(File(filesDir, "materials").canonicalPath + File.separator)) { finish(); return }
        val container = (application as ZenApplication).container
        setContent {
            val settings by container.settings.collectAsState()
            val repository by container.repository.collectAsState()
            val ready by container.ready.collectAsState()
            LaunchedEffect(ready, repository?.account?.cacheScope) {
                val fileScope = file.relativeTo(File(filesDir, "materials").canonicalFile).path.substringBefore(File.separator)
                if (ready && (repository == null || AndroidMaterials.digest(repository!!.account.cacheScope) != fileScope)) finish()
            }
            val snapshot = repository?.snapshot?.collectAsState()?.value
            val sync = repository?.sync?.collectAsState()?.value ?: SyncStatus()
            val saved = snapshot?.localDocuments?.firstOrNull { it.path == path }
            val words = Words(settings.language == AppLanguage.GERMAN ||
                (settings.language == AppLanguage.SYSTEM && Locale.getDefault().language.startsWith("de")))
            ConfigureSystemBars(window, settings.theme)
            var failed by remember { mutableStateOf(false) }
            var fileMenu by remember { mutableStateOf(false) }
            val pdf by produceState<PdfSession?>(null, path) {
                var opened: PdfSession? = null
                try {
                    withContext(Dispatchers.IO) { opened = PdfSession(file) }
                    value = opened
                    awaitDispose { opened?.close() }
                } catch (e: Exception) { opened?.close(); failed = true }
            }
            CompositionLocalProvider(LocalWords provides words) { ZenTheme(settings.theme) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { Column {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconAction(Symbol.BACK, words.text("back"), { finish() })
                        Text(intent.getStringExtra("name") ?: "PDF", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        IconAction(Symbol.DOWNLOAD, words.text("save_downloads"), { saved?.let { repository?.requestMaterial(it.document, MaterialAction.SAVE_TO_DOWNLOADS) } },
                            saved != null && sync.active.none { it.startsWith("material:SAVE_TO_DOWNLOADS:${saved.document.id}:") })
                        Box {
                            IconAction(Symbol.MORE, words.service("more_options"), { fileMenu = true }, saved != null)
                            DropdownMenu(fileMenu, { fileMenu = false }) {
                                DropdownMenuItem(text = { Text(words.text("open_with")) }, onClick = {
                                    fileMenu = false; saved?.let { repository?.requestMaterial(it.document, MaterialAction.OPEN_WITH) }
                                })
                                DropdownMenuItem(text = { Text(words.text("share_pdf")) }, onClick = {
                                    fileMenu = false; saved?.let { repository?.requestMaterial(it.document, MaterialAction.SHARE) }
                                })
                            }
                        }
                    }
                    saved?.let { local ->
                        val pending = sync.active.any { it.startsWith("material:SAVE_TO_DOWNLOADS:${local.document.id}:") }
                        val location = sync.materialExports[local.document.id]
                        val error = sync.errors["download:${local.document.id}"]
                        if (pending || location != null || error != null) Text(
                            if (pending) words.text("file_exporting") else if (error != null) words.failure(error) else words.text("file_saved_to") + " " + location,
                            Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                }) { padding ->
                    if (failed) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text(words.text("pdf_error"))
                    } else if (pdf == null) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        if (!settings.reduceMotion) CircularProgressIndicator() else Text("PDF")
                    } else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(pdf!!.pageCount, key = { it }) { index -> PdfPage(pdf!!, index) }
                    }
                }
            } }
        }
    }
}

private class PdfSession(file: File) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
    private val lock = ReentrantLock()
    private var closed = false
    val pageCount = renderer.pageCount
    fun render(index: Int): ImageBitmap = lock.withLock {
        check(!closed)
        renderer.openPage(index).use { page ->
            val width = 1200
            val height = (width.toLong() * page.height / page.width.coerceAtLeast(1)).coerceIn(1, 4000).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap.asImageBitmap()
        }
    }
    override fun close() = lock.withLock {
        if (!closed) { closed = true; renderer.close(); descriptor.close() }
    }
}

@Composable
private fun PdfPage(pdf: PdfSession, index: Int) {
    val image by produceState<ImageBitmap?>(null, pdf, index) {
        value = withContext(Dispatchers.IO) { runCatching { pdf.render(index) }.getOrNull() }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (image != null) Image(image!!, "${LocalWords.current.text("page")} ${index + 1}",
            Modifier.fillMaxWidth().aspectRatio(image!!.width.toFloat() / image!!.height))
        else Box(Modifier.fillMaxWidth().aspectRatio(.707f).background(Color.White), contentAlignment = Alignment.Center) {
            Text((index + 1).toString(), color = Color.Gray)
        }
        Spacer(Modifier.height(6.dp))
        Text("${index + 1} / ${pdf.pageCount}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
