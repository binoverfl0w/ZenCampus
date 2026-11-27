package io.github.binoverfl0w.zencampus

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.window.ComposeUIViewController
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.github.binoverfl0w.zencampus.data.*
import io.github.binoverfl0w.zencampus.data.digicampus.*
import io.github.binoverfl0w.zencampus.data.services.*
import io.github.binoverfl0w.zencampus.data.study.*
import io.github.binoverfl0w.zencampus.db.CampusDatabase
import io.github.binoverfl0w.zencampus.model.*
import io.github.binoverfl0w.zencampus.ui.ZenApp
import io.github.binoverfl0w.zencampus.ui.design.DialogButton
import io.github.binoverfl0w.zencampus.ui.design.ZenDialog
import io.github.binoverfl0w.zencampus.ui.services.LocalServiceAlerts
import io.github.binoverfl0w.zencampus.ui.services.LocalAuthorizeServiceAlert
import io.github.binoverfl0w.zencampus.ui.services.LocalSignInFields
import io.github.binoverfl0w.zencampus.ui.study.LocalMaterialAutomaticSync
import io.github.binoverfl0w.zencampus.ui.study.LocalChooseMaterialFolder
import io.github.binoverfl0w.zencampus.ui.study.LocalMaterialDefaultFolder
import io.github.binoverfl0w.zencampus.ui.study.LocalMaterialResetFolder
import io.github.binoverfl0w.zencampus.ui.study.LocalAssignmentOpenUrl
import io.github.binoverfl0w.zencampus.ui.study.LocalAssignmentUpload
import io.github.binoverfl0w.zencampus.ui.study.IncomingShare
import io.github.binoverfl0w.zencampus.ui.study.LocalIncomingShare
import io.github.binoverfl0w.zencampus.ui.study.LocalVipsReader
import io.github.binoverfl0w.zencampus.ui.study.LocalCampusBrowser
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.Url
import io.ktor.http.URLProtocol
import kotlinx.coroutines.*
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

fun MainViewController(platform: IosPlatform, runtime: IosRuntime): UIViewController = ComposeUIViewController {
    val scope = runtime.scope
    val services = runtime.services
    val accounts = runtime.accounts
    val settings = runtime.settings
    val activeRepository by accounts.repositoryFlow.collectAsState()
    val openUrl: (String) -> Unit = { value ->
        val url = runCatching { Url(value) }.getOrNull()
        if (url != null && url.protocol == URLProtocol.HTTPS && url.port == 443 && url.user.isNullOrEmpty() && url.password.isNullOrEmpty())
            NSURL.URLWithString(value)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null) }
        else accounts.message = "This link cannot be opened."
    }
    val share = runtime.incomingFile
    CompositionLocalProvider(
        LocalIncomingShare provides share?.let { file ->
            IncomingShare(file.name, onChoose = choose@{ course, folder ->
                val repo = accounts.repository ?: return@choose
                if (runtime.incomingFile !== file) return@choose
                val draft = runCatching { readIosUpload(file.path, file.name, file.mime, course, folder) }
                runtime.dismissIncomingFile(file)
                scope.launch {
                    if (accounts.repository === repo) draft.fold(
                        onSuccess = { repo.selectUpload(it) },
                        onFailure = { repo.uploadSelectionFailed(FailureKind.STORAGE) })
                }
            }, onDismiss = { runtime.dismissIncomingFile(file) })
        },
        LocalServiceAlerts provides (activeRepository?.account?.demo != true),
        LocalAuthorizeServiceAlert provides { kind, enabled, done ->
            runtime.cancelBackground()
            if (!enabled) { platform.clearNotification(kind); done(true) } else platform.requestNotifications { allowed ->
                if (allowed) runtime.scheduleForAlert()
                else accounts.message = "Enable notifications in iOS Settings to receive alerts."
                done(allowed)
            }
        },
        LocalMaterialDefaultFolder provides "Files / ZenCampus",
        LocalMaterialResetFolder provides (if (settings.language == AppLanguage.GERMAN ||
            settings.language == AppLanguage.SYSTEM && runtime.language().startsWith("de")) "App-Ordner verwenden" else "Use app folder"),
        LocalMaterialAutomaticSync provides settings.backgroundRefresh,
        LocalChooseMaterialFolder provides { course ->
            val repo = accounts.repository
            if (repo != null) platform.selectMaterialFolder { bookmark, label ->
                if (bookmark != null && label != null && accounts.repository === repo) scope.launch {
                    if (accounts.repository === repo) repo.setMaterialDestination(course, MaterialDestination(bookmark, label))
                }
            }
        },
        LocalAssignmentUpload provides { course, folder ->
            val repo = accounts.repository
            if (repo != null) platform.selectUpload { path, name, mime ->
                if (path != null && accounts.repository === repo) {
                    val draft = runCatching { readIosUpload(path, name.orEmpty(), mime.orEmpty(), course, folder) }
                    scope.launch {
                        if (accounts.repository === repo) draft.fold(
                            onSuccess = { repo.selectUpload(it) },
                            onFailure = { repo.uploadSelectionFailed(FailureKind.STORAGE) })
                    }
                }
            }
        },
        LocalAssignmentOpenUrl provides openUrl,
        LocalVipsReader provides accounts::openVips,
        LocalCampusBrowser provides { url -> accounts.openCampusPage(null, url, "DigiCampus") },
        LocalSignInFields provides { kind, username, onUsername, password, onPassword, visible, onToggle ->
            IosSignInFields(kind, username, onUsername, password, onPassword, visible, onToggle)
        },
    ) {
    ZenApp(repository = activeRepository, settings = settings, network = NetworkConditions(), ready = accounts.ready,
        systemLanguage = runtime.language(),
        onSignIn = { accounts.signIn(runtime.words()) },
        onAutomaticSignIn = { accounts.signIn(runtime.words(), expired = true) },
        onSignOut = { removeSaved -> runtime.dismissIncomingFile(); accounts.signOut(removeSaved) }, onSettings = { next ->
            val enablingCoursework = next.courseworkReminders && !runtime.settings.courseworkReminders
            val enablingUpdates = next.updateNotifications && !runtime.settings.updateNotifications
            if (enablingCoursework || enablingUpdates) platform.requestNotifications { allowed ->
                if (allowed) runtime.updateSettings(runtime.settings.copy(
                    courseworkReminders = if (enablingCoursework) true else runtime.settings.courseworkReminders,
                    updateNotifications = if (enablingUpdates) true else runtime.settings.updateNotifications))
                else accounts.message = "Enable notifications in iOS Settings to receive alerts."
            } else runtime.updateSettings(next)
        },
        onOpenUrl = openUrl,
        services = if (activeRepository?.account?.demo == true) accounts.previewServices else services,
        onConnectLibrary = { if (accounts.repository?.account?.demo == true) accounts.connectPreviewLibrary() else accounts.connectLibrary(runtime.words()) },
        onDisconnectLibrary = { removeSaved -> if (accounts.repository?.account?.demo == true) accounts.disconnectPreviewLibrary() else accounts.disconnectLibrary(removeSaved) },
        onLibraryReminders = { enabled ->
            runtime.cancelBackground()
            val target = if (accounts.repository?.account?.demo == true) accounts.previewServices else services
            val connection = target.library.value.connectionId
            if (!enabled) {
                platform.clearNotification("library"); platform.clearNotification("holds")
                scope.launch { target.setPreferences { it.copy(libraryReminders = false) } }
            } else platform.requestNotifications { allowed ->
                if (allowed && connection != null && target.library.value.connectionId == connection) {
                    runtime.scheduleForAlert()
                    scope.launch { target.setPreferences { it.copy(libraryReminders = true) } }
                }
                else if (!allowed) accounts.message = "Enable notifications in iOS Settings to receive alerts."
            }
        },
        serviceRequest = runtime.serviceRoute,
        signInSession = accounts.signInSession, signInForeground = runtime.signInForeground, signInConnected = runtime.signInConnected,
        appVersion = "iOS preview", onPreview = if (runtime.previewEnabled) accounts::usePreview else null,
        maintenance = runtime.maintenance)
    }
    accounts.message?.let { message -> ZenDialog(onDismissRequest = { accounts.message = null },
        title = { Text("ZenCampus") }, text = { Text(message) },
        confirmButton = { DialogButton("OK", { accounts.message = null }, primary = true) }) }
}
