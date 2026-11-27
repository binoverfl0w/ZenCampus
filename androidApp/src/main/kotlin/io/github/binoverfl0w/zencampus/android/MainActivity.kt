package io.github.binoverfl0w.zencampus.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import io.github.binoverfl0w.zencampus.model.MaterialDestination
import io.github.binoverfl0w.zencampus.model.ThemeMode
import io.github.binoverfl0w.zencampus.ui.LocalBoardGestureExclusion
import io.github.binoverfl0w.zencampus.ui.ZenApp
import io.github.binoverfl0w.zencampus.ui.study.IncomingShare
import io.github.binoverfl0w.zencampus.ui.study.LocalAssignmentUpload
import io.github.binoverfl0w.zencampus.ui.study.LocalChooseMaterialFolder
import io.github.binoverfl0w.zencampus.ui.study.LocalIncomingShare
import io.github.binoverfl0w.zencampus.ui.study.LocalCampusBrowser
import io.github.binoverfl0w.zencampus.ui.study.LocalVipsReader
import io.github.binoverfl0w.zencampus.ui.ui
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var resumed by mutableStateOf(false)
    private var serviceRequest by mutableStateOf("")
    /** A file shared into the app (ACTION_SEND), kept until the student picks a folder or dismisses the sheet. */
    private var sharedFile by mutableStateOf<android.net.Uri?>(null)
    private var sharedName by mutableStateOf("")
    private var uploadScope: String? = null
    private var uploadCourse: String? = null
    private var uploadFolder: String? = null
    private var destinationScope: String? = null
    private var destinationCourse: String? = null
    private val materialFolderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val owner = destinationScope; val course = destinationCourse
        destinationScope = null; destinationCourse = null
        val uri = result.data?.data
        val repository = (application as ZenApplication).container.repository.value
        if (result.resultCode == RESULT_OK && uri != null && owner != null && course != null && repository?.account?.cacheScope == owner) {
            val saved = runCatching {
                val flags = result.data!!.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                require(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 && flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                contentResolver.takePersistableUriPermission(uri, flags)
                val label = DocumentsContract.getTreeDocumentId(uri).substringAfterLast('/').substringAfterLast(':').ifBlank { uri.lastPathSegment.orEmpty() }
                MaterialDestination(uri.toString(), label)
            }
            saved.onSuccess { destination -> lifecycleScope.launch { repository.setMaterialDestination(course, destination) } }
                .onFailure { Toast.makeText(this, "Unable to use this folder", Toast.LENGTH_LONG).show() }
        }
    }
    private val assignmentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val scope = uploadScope; val course = uploadCourse; val folder = uploadFolder
        uploadScope = null; uploadCourse = null; uploadFolder = null
        if (uri != null && scope != null && course != null && folder != null)
            (application as ZenApplication).container.prepareAssignmentUpload(uri, scope, course, folder)
    }
    private val courseworkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val container = (application as ZenApplication).container
        container.setSettings(container.settings.value.copy(courseworkReminders = allowed))
    }
    private val updatePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val container = (application as ZenApplication).container
        container.setSettings(container.settings.value.copy(updateNotifications = allowed))
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val container = (application as ZenApplication).container
        container.libraryReminders(allowed)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        // The launch theme only paints the leaf while the process starts; the app itself uses the plain theme.
        setTheme(R.style.Theme_ZenCampus)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ZenApplication).container
        uploadScope = savedInstanceState?.getString("uploadScope")
        uploadCourse = savedInstanceState?.getString("uploadCourse")
        uploadFolder = savedInstanceState?.getString("uploadFolder")
        destinationScope = savedInstanceState?.getString("destinationScope")
        destinationCourse = savedInstanceState?.getString("destinationCourse")
        receiveServiceIntent(intent)
        if (savedInstanceState == null) receiveShare(intent)
        else savedInstanceState.getString("sharedFile")?.let { sharedFile = android.net.Uri.parse(it); sharedName = savedInstanceState.getString("sharedName").orEmpty() }
        setContent {
            val repository by container.repository.collectAsState()
            val settings by container.settings.collectAsState()
            val network by container.network.collectAsState()
            val ready by container.ready.collectAsState()
            val target = remember { BackTarget() }
            var backEnabled by remember { mutableStateOf(false) }
            BackHandler(backEnabled) { target.action?.invoke() }
            val dark = settings.theme == ThemeMode.DARK || (settings.theme == ThemeMode.SYSTEM && isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = !dark
            }
            val view = LocalView.current
            val platformHaptic = LocalHapticFeedback.current
            val boardHaptic = remember(view, platformHaptic) { object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    if (hapticFeedbackType == HapticFeedbackType.SegmentTick)
                        ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.SEGMENT_TICK)
                    else platformHaptic.performHapticFeedback(hapticFeedbackType)
                }
            } }
            CompositionLocalProvider(LocalBoardGestureExclusion provides { it.systemGestureExclusion() },
                LocalHapticFeedback provides boardHaptic,
                LocalVipsReader provides { course, url, title -> container.openCampusPage(course, url, title) },
                LocalCampusBrowser provides { url -> container.openCampusPage(null, url, "DigiCampus") },
                LocalChooseMaterialFolder provides { course ->
                    repository?.account?.let { account ->
                        destinationScope = account.cacheScope; destinationCourse = course
                        materialFolderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
                    }
                },
                LocalIncomingShare provides sharedFile?.let { uri ->
                    IncomingShare(sharedName, onChoose = { course, folder ->
                        val account = repository?.account
                        sharedFile = null
                        if (account != null) container.prepareAssignmentUpload(uri, account.cacheScope, course, folder)
                    }, onDismiss = { sharedFile = null })
                },
                LocalAssignmentUpload provides { course, folder ->
                    val account = repository?.account
                    if (account != null) {
                        uploadScope = account.cacheScope; uploadCourse = course; uploadFolder = folder
                        assignmentPicker.launch(arrayOf("*/*"))
                    }
                }) {
                fun openStudySignIn(expired: Boolean) {
                    if (resumed) {
                        resumed = false
                        startActivity(Intent(this, LoginActivity::class.java).putExtra("expired", expired))
                    }
                }
                ZenApp(repository, settings, network, ready, Locale.getDefault().language,
                    onSignIn = { openStudySignIn(false) },
                    onAutomaticSignIn = { openStudySignIn(true) },
                    onSignOut = container::signOut,
                    onSettings = { next ->
                        if (next.updateNotifications && !settings.updateNotifications && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            updatePermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else if (next.courseworkReminders && !settings.courseworkReminders && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            courseworkPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else container.setSettings(next)
                    },
                    onOpenUrl = container::openUrl,
                    services = if (repository?.account?.demo == true) container.previewServices else container.campusServices,
                    onConnectLibrary = {
                        if (repository?.account?.demo == true) container.connectPreviewLibrary()
                        else startActivity(Intent(this, LibraryLoginActivity::class.java))
                    },
                    onDisconnectLibrary = { removeSaved -> container.disconnectLibrary(repository?.account?.demo == true, removeSaved) },
                    onLibraryReminders = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else container.libraryReminders(enabled)
                    },
                    serviceRequest = serviceRequest,
                    onBackHandler = { target.action = it; backEnabled = it != null }, appVersion = BuildConfig.VERSION_NAME,
                    maintenance = container.maintenance, signInSession = container.signInSession,
                    signInForeground = resumed)
            }
        }
    }
    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("uploadScope", uploadScope); outState.putString("uploadCourse", uploadCourse); outState.putString("uploadFolder", uploadFolder)
        outState.putString("destinationScope", destinationScope); outState.putString("destinationCourse", destinationCourse)
        sharedFile?.let { outState.putString("sharedFile", it.toString()); outState.putString("sharedName", sharedName) }
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveServiceIntent(intent); receiveShare(intent) }
    private fun receiveServiceIntent(intent: Intent) {
        val route = intent.getStringExtra("openService")
        if (route in SERVICE_ROUTES) serviceRequest = "$route#${System.nanoTime()}"
    }
    private fun receiveShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri) ?: return
        val container = (application as ZenApplication).container
        if (container.repository.value == null) {
            Toast.makeText(this, container.words().ui("share_signed_out"), Toast.LENGTH_LONG).show(); return
        }
        sharedName = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        sharedFile = uri
    }
    private companion object {
        // Routes other apps (notifications, the widget, launcher shortcuts) may open.
        val SERVICE_ROUTES = setOf("library", "mensa", "coursework", "updates", "today", "calendar", "catalogue", "search", "officehours", "inbox", "accounts")
    }
}
private class BackTarget { var action: (() -> Unit)? = null }
