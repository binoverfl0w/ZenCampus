package io.github.binoverfl0w.zencampus.model

enum class MaterialAction { OPEN, OPEN_WITH, SHARE, SAVE_TO_DOWNLOADS, KEEP_OFFLINE }
data class MaterialProgress(val bytes: Long, val total: Long?)

/** A readable single filename, bounded in UTF-8 bytes without losing its extension. */
fun materialFileName(value: String, extension: String = ""): String {
    val clean = value.map { if (it < ' ' || it == '\u007f' || it in "/\\:*?\"<>|") '_' else it }
        .joinToString("").trim().trim('.').ifBlank { "Document" }
    val suffix = clean.substringAfterLast('.', "").takeIf { it.length in 1..16 && it.all { c -> c.isLetterOrDigit() } }
    val ext = suffix ?: extension.trim('.').takeIf { it.length in 1..16 && it.all(Char::isLetterOrDigit) }
    var stem = if (suffix != null) clean.dropLast(suffix.length + 1) else clean
    val ending = ext?.let { ".$it" }.orEmpty()
    while ((stem + ending).encodeToByteArray().size > 220) {
        stem = stem.dropLast(1)
        if (stem.lastOrNull()?.isHighSurrogate() == true) stem = stem.dropLast(1)
    }
    return stem.ifBlank { "Document" } + ending
}
