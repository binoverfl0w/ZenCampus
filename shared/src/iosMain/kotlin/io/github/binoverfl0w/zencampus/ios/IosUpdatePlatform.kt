package io.github.binoverfl0w.zencampus

import io.github.binoverfl0w.zencampus.data.UpdateInstall
import io.github.binoverfl0w.zencampus.data.UpdatePlatform
import io.github.binoverfl0w.zencampus.data.UpdateWriter
import io.github.binoverfl0w.zencampus.model.ReleaseAsset
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication

/** iOS installations are updated through their distribution channel, never by replacing the app bundle. */
internal class IosUpdatePlatform : UpdatePlatform {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun installable(assets: List<ReleaseAsset>): ReleaseAsset? = null
    override suspend fun writer(asset: ReleaseAsset): UpdateWriter = error("iOS cannot install release assets")
    override suspend fun install(asset: ReleaseAsset): UpdateInstall = error("iOS cannot install release assets")
    override fun openPage(url: String) {
        if (url.startsWith("https://github.com/")) NSURL.URLWithString(url)?.let {
            UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null)
        }
    }
    override fun readState(): String? = defaults.stringForKey("zen.release.checks")
    override fun writeState(value: String) { defaults.setObject(value, forKey = "zen.release.checks") }
}
