package io.github.binoverfl0w.zencampus.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** A release version such as 0.12.0. Tags may start with v; a pre-release suffix (0.12.0-beta.1) sorts before its release. */
data class AppVersion(val parts: List<Int>, val suffix: String = "") : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (index in 0 until maxOf(parts.size, other.parts.size)) {
            val order = parts.getOrElse(index) { 0 }.compareTo(other.parts.getOrElse(index) { 0 })
            if (order != 0) return order
        }
        return when {
            suffix == other.suffix -> 0
            suffix.isEmpty() -> 1
            other.suffix.isEmpty() -> -1
            else -> suffix.compareTo(other.suffix)
        }
    }

    override fun toString() = parts.joinToString(".") + if (suffix.isEmpty()) "" else "-$suffix"

    companion object {
        fun parse(text: String): AppVersion? {
            val value = text.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val parts = value.substringBefore('-').split('.').map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
            if (parts.size !in 1..4) return null
            return AppVersion(parts, value.substringAfter('-', ""))
        }
    }
}

@Serializable
data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String? = null)

@Serializable
data class AppRelease(val version: String, val tag: String, val title: String, val notes: String, val page: String, val assets: List<ReleaseAsset>)

/** Release notes are shown as plain text; this keeps the dialog readable at the price of no formatting. */
const val MAX_RELEASE_NOTES = 6_000

/**
 * Reads GitHub's "latest release" response. Drafts and pre-releases are never offered, and links must point back to
 * GitHub over HTTPS.
 */
fun parseGitHubRelease(text: String): AppRelease? {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun JsonObject.flag(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull == true
    if (root.flag("draft") || root.flag("prerelease")) return null
    val tag = root.text("tag_name") ?: return null
    val version = AppVersion.parse(tag) ?: return null
    val page = root.text("html_url")?.takeIf { it.startsWith("https://github.com/") } ?: return null
    val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { element ->
        val asset = element as? JsonObject ?: return@mapNotNull null
        val name = asset.text("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val url = asset.text("browser_download_url")?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
        ReleaseAsset(name, url, (asset["size"] as? JsonPrimitive)?.longOrNull ?: 0L, asset.text("digest")?.let(::sha256Digest))
    }
    return AppRelease(version.toString(), tag, root.text("name")?.trim().orEmpty().ifBlank { tag },
        root.text("body").orEmpty().replace("\r\n", "\n").trim().take(MAX_RELEASE_NOTES), page, assets)
}

private val SHA256_HEX = Regex("[0-9a-f]{64}")

/** The hex digest of GitHub's "sha256:<hex>" asset digest, or null for any other algorithm. */
fun sha256Digest(value: String): String? =
    value.trim().takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()?.takeIf(SHA256_HEX::matches)

/** The published file name for each digest in `sha256sum` output ("<hex>  <name>"). */
fun parseSha256Sums(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
    val match = Regex("""^([0-9a-fA-F]{64})\s+\*?(\S.*)$""").find(line.trim()) ?: return@mapNotNull null
    match.groupValues[2].trim() to match.groupValues[1].lowercase()
}.toMap()

/** The release asset that holds the checksums of the others. */
const val SHA256_SUMS = "SHA256SUMS"
