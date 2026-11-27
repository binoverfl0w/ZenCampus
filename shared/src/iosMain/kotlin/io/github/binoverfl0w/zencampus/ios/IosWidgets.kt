package io.github.binoverfl0w.zencampus

import androidx.compose.runtime.snapshotFlow
import io.github.binoverfl0w.zencampus.model.nowMillis
import io.github.binoverfl0w.zencampus.ui.WidgetFrame
import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.ui.homeWidgetFrames
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToURL

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class, ExperimentalForeignApi::class, BetaInteropApi::class)
internal suspend fun IosRuntime.publishIosWidgets() {
    val group = NSBundle.mainBundle.objectForInfoDictionaryKey("ZenWidgetGroup") as? String ?: return
    val target = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(group)
        ?.URLByAppendingPathComponent("home-widgets.json") ?: return
    val languageAndRefresh = combine(snapshotFlow { words().german }, widgetRefresh) { german, _ -> Words(german) }
    accounts.awaitReady()
    accounts.repositoryFlow.flatMapLatest { repo ->
        val source = if (repo?.account?.demo == true) accounts.previewServices else services
        combine(repo?.snapshot ?: flowOf(null), source.mensa, source.library, source.preferences,
            languageAndRefresh) { snapshot, mensa, library, preferences, words ->
            homeWidgetFrames(snapshot, mensa.menu, library, preferences, nowMillis(), words)
        }.distinctUntilChangedBy { it.map(WidgetFrame::content) }
            .debounce { frames -> if (frames.first().content.signedIn) 750L else 0L }
    }.collect { frames ->
        if (NSString.create(string = Json.encodeToString(frames))
                .writeToURL(target, atomically = true, encoding = NSUTF8StringEncoding, error = null)) widgetsChanged()
    }
}
