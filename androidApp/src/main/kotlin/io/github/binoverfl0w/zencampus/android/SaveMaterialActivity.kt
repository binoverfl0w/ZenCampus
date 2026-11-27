package io.github.binoverfl0w.zencampus.android

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.*
import io.github.binoverfl0w.zencampus.ui.design.ZenTheme
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Android 8/9 use the system save picker instead of requesting broad storage access. */
class SaveMaterialActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[MaterialCopyModel::class.java] }
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) finish()
        else model.save(applicationContext, intent.getStringExtra("path").orEmpty(), uri)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ZenApplication).container
        val name = materialFileName(intent.getStringExtra("name").orEmpty())
        setContent {
            val settings by container.settings.collectAsState()
            val words = Words(settings.language == AppLanguage.GERMAN || (settings.language == AppLanguage.SYSTEM && Locale.getDefault().language.startsWith("de")))
            val result by model.result.collectAsState()
            LaunchedEffect(result) { result?.let {
                Toast.makeText(this@SaveMaterialActivity, words.text(if (it) "file_saved_files" else "error_storage"), Toast.LENGTH_LONG).show(); finish()
            } }
            ZenTheme(settings.theme) { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(name); Spacer(Modifier.height(16.dp)); Text(words.text("file_exporting"))
                }
            } }
        }
        if (savedInstanceState == null) picker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = intent.getStringExtra("mime") ?: "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, name)
        })
    }
}

class MaterialCopyModel : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val result = MutableStateFlow<Boolean?>(null)
    private var started = false
    fun save(context: android.content.Context, path: String, uri: android.net.Uri) {
        if (started) return
        started = true
        scope.launch {
            var complete = false
            try {
                val file = AndroidMaterials(context).safeFile(path)
                copyMaterialToUri(context, file, uri)
                complete = true; result.value = true
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { result.value = false }
            finally { if (!complete) withContext(NonCancellable) { runCatching { context.contentResolver.delete(uri, null, null) } } }
        }
    }
    override fun onCleared() { scope.cancel() }
}
