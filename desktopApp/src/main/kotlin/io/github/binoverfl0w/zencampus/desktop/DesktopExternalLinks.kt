package io.github.binoverfl0w.zencampus.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.util.concurrent.TimeUnit

/** Opens only validated HTTPS links; the fallback receives the URI as one argument, never as shell code. */
internal fun launchExternalLink(
    url: String,
    desktopBrowse: (URI) -> Boolean = { uri ->
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) false
        else { Desktop.getDesktop().browse(uri); true }
    },
    systemBrowse: (URI) -> Boolean = { uri ->
        when {
            System.getProperty("os.name").startsWith("Mac") -> browseWithXdgOpen(uri, "open")
            isWindows -> browseWithCommand(listOf("rundll32", "url.dll,FileProtocolHandler", uri.toASCIIString()))
            else -> browseWithXdgOpen(uri)
        }
    },
): Boolean {
    if (!safeExternalUrl(url)) return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (runCatching { desktopBrowse(uri) }.getOrDefault(false)) return true
    return runCatching { systemBrowse(uri) }.getOrDefault(false)
}

internal fun browseWithXdgOpen(uri: URI, executable: String = "xdg-open"): Boolean = browseWithCommand(listOf(executable, uri.toASCIIString()))

internal fun browseWithCommand(command: List<String>): Boolean {
    val process = ProcessBuilder(command)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    process.outputStream.close()
    // Some desktop launchers stay alive while the browser is running. Never block the UI for them.
    return !process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() == 0
}

internal suspend fun openExternal(url: String): Boolean = withContext(Dispatchers.IO) { launchExternalLink(url) }
