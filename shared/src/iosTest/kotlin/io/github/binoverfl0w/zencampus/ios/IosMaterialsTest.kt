package io.github.binoverfl0w.zencampus

import io.github.binoverfl0w.zencampus.ui.Words
import io.github.binoverfl0w.zencampus.data.SavedSignInPopup
import io.github.binoverfl0w.zencampus.data.SignInKind

import io.github.binoverfl0w.zencampus.model.CampusException
import io.github.binoverfl0w.zencampus.model.Document
import io.github.binoverfl0w.zencampus.model.MaterialDestination
import kotlinx.coroutines.runBlocking
import platform.Foundation.*
import kotlin.test.*

private class MaterialPlatform : IosPlatform {
    override fun readSecret(service: String): String? = null
    override fun writeSecret(service: String, value: String) = true
    override fun deleteSecret(service: String) = true
    override fun showLogin(kind: SignInKind, savedSignIn: SavedSignInPopup?, words: Words,
                           verifyCookie: (String, (Boolean) -> Unit) -> Unit, onCookie: (String) -> Unit) = Unit
    override fun openMaterial(path: String) = true
    override fun shareMaterial(path: String) = true
    override fun writeDiagnosticLine(line: String) = Unit
    override fun shareDiagnosticLog() = true
    override fun exportMaterial(path: String, onComplete: (String?) -> Unit) = onComplete(null)
    override fun selectUpload(onFile: (String?, String?, String?) -> Unit) = Unit
    override fun selectMaterialFolder(onFolder: (String?, String?) -> Unit) = Unit
    override fun beginMaterialFolder(bookmark: String): String? {
        if (!bookmark.startsWith("test:")) return null
        selectedRoot = bookmark.removePrefix("test:")
        return "test-lease\n$selectedRoot"
    }
    var ended = 0
    var commitWorks = true
    private var selectedRoot = ""
    override fun endMaterialFolder(lease: String) { ended++ }
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    override fun commitMaterialFolder(lease: String, stagedPath: String, folders: List<String>, name: String): String? {
        if (!commitWorks) return null
        val directory = (listOf(selectedRoot) + folders).joinToString("/")
        val files = NSFileManager.defaultManager
        if (!files.createDirectoryAtPath(directory, true, null, null)) return null
        val target = "$directory/$name"
        // Simulate a coordinated replacement, preserving the old file until the new copy exists.
        val part = "$directory/.${NSUUID().UUIDString}.part"
        if (!files.copyItemAtPath(stagedPath, part, null)) return null
        if (platform.posix.rename(part, target) != 0) { files.removeItemAtPath(part, null); return null }
        return target
    }
    override fun openVips(url: String, title: String, cookie: String, classify: (String, String) -> String, onAttachment: (String, String) -> Unit, onCookie: (String) -> Unit) = Unit
    override fun openVipsPreview(url: String, title: String, classify: (String) -> String, onAttachment: (String, String) -> Unit) = Unit
    override fun scheduleBackground(enabled: Boolean) = true
    override fun canRunBackground() = true
    override fun requestNotifications(onResult: (Boolean) -> Unit) = onResult(true)
    override fun notificationsEnabled(onResult: (Boolean) -> Unit) = onResult(true)
    override fun sendNotification(kind: String, title: String, body: String, onResult: (Boolean) -> Unit) = onResult(true)
    override fun clearNotification(kind: String) = Unit
    override fun closePrivateReader() = Unit
}

class IosMaterialsTest {
    @Test fun downloadsAreScopedAndCanBeRemoved() = runBlocking {
        val storage = IosMaterials(MaterialPlatform())
        val scope = NSUUID().UUIDString
        val other = NSUUID().UUIDString
        val doc = Document("1", "course", "folder", "../notes.pdf", mimeType = "application/pdf")
        try {
            val writer = storage.create(scope, doc)
            writer.write(byteArrayOf(1, 2)); writer.write(byteArrayOf(3))
            val path = writer.finish()
            assertTrue(storage.exists(path))
            val draft = readIosUpload(path, "../notes.pdf", "application/pdf", "course", "folder")
            assertContentEquals(byteArrayOf(1, 2, 3), draft.bytes)
            assertEquals("_notes.pdf", draft.name)
            assertTrue(path.endsWith("_notes.pdf"))
            val otherWriter = storage.create(other, doc)
            otherWriter.write(byteArrayOf(4))
            val otherPath = otherWriter.finish()
            storage.clear(scope)
            assertFalse(storage.exists(path))
            assertTrue(storage.exists(otherPath))
            storage.remove(otherPath)
            assertFalse(storage.exists(otherPath))
            assertFailsWith<CampusException> { storage.exists("$path/../../secret") }
        } finally { storage.clear(scope); storage.clear(other) }
        Unit
    }

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    @Test fun privateCopiesStreamIntoCourseFolders() = runBlocking {
        val storage = IosMaterials(MaterialPlatform())
        val scope = NSUUID().UUIDString
        val folder = "test-copy-" + NSUUID().UUIDString
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        val bytes = ByteArray(140_000) { (it % 251).toByte() }
        try {
            val source = storage.create(scope, Document("1", "c", "f", "notes.pdf"))
            source.write(bytes)
            val sourcePath = source.finish()
            val target = storage.syncWriter(null, listOf(folder, "Vips", "Sheet"), "notes.pdf", "application/pdf")
            storage.copyInto(sourcePath, target)
            val targetPath = target.finish()
            assertTrue(targetPath.endsWith("/Vips/Sheet/notes.pdf"))
            assertContentEquals(bytes, readIosUpload(targetPath, "notes.pdf", "application/pdf", "c", "f").bytes)
            assertFailsWith<CampusException> { storage.copyInto("$sourcePath/../../secret", target) }
        } finally { storage.clear(scope); NSFileManager.defaultManager.removeItemAtPath("$documents/ZenCampus/$folder", null) }
        Unit
    }

    @Test fun abortedDownloadIsNotPublished() = runBlocking {
        val storage = IosMaterials(MaterialPlatform())
        val scope = NSUUID().UUIDString
        try {
            val writer = storage.create(scope, Document("1", "c", "f", "test.pdf"))
            writer.write(byteArrayOf(1)); writer.abort()
            assertFailsWith<IllegalStateException> { writer.finish() }
        } finally { storage.clear(scope) }
        Unit
    }
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    @Test fun syncReplacesOnlyAfterFinishing() = runBlocking {
        val storage = IosMaterials(MaterialPlatform())
        val folder = "test-" + NSUUID().UUIDString
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        try {
            val first = storage.syncWriter(null, listOf(folder), "notes.pdf", "application/pdf")
            first.write(byteArrayOf(1, 2)); val path = first.finish()
            val aborted = storage.syncWriter(null, listOf(folder), "notes.pdf", "application/pdf")
            aborted.write(byteArrayOf(9)); aborted.abort()
            assertContentEquals(byteArrayOf(1, 2), readIosUpload(path, "notes.pdf", "", "c", "f").bytes)
            val replacement = storage.syncWriter(null, listOf(folder), "notes.pdf", "application/pdf")
            replacement.write(byteArrayOf(3, 4)); assertEquals(path, replacement.finish())
            assertContentEquals(byteArrayOf(3, 4), readIosUpload(path, "notes.pdf", "", "c", "f").bytes)
        } finally { NSFileManager.defaultManager.removeItemAtPath("$documents/ZenCampus/$folder", null) }
        Unit
    }

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    @Test fun selectedFolderWritesAndReleasesAccess() = runBlocking {
        val platform = MaterialPlatform()
        val storage = IosMaterials(platform)
        val root = (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String) + "/test-folder-" + NSUUID().UUIDString
        val destination = MaterialDestination("test:$root", "chosen folder")
        val scope = NSUUID().UUIDString
        try {
            NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
            val first = storage.syncWriter(destination, listOf("Week 1"), "notes.pdf", "application/pdf")
            first.write(byteArrayOf(1, 2)); val path = first.finish()
            assertEquals(1, platform.ended)
            val aborted = storage.syncWriter(destination, listOf("Week 1"), "notes.pdf", "application/pdf")
            aborted.write(byteArrayOf(9)); aborted.abort()
            assertEquals(2, platform.ended)
            assertContentEquals(byteArrayOf(1, 2), readIosUpload(path, "notes.pdf", "", "c", "f").bytes)
            val failed = storage.syncWriter(destination, listOf("Week 1"), "notes.pdf", "application/pdf")
            failed.write(byteArrayOf(8)); platform.commitWorks = false
            assertFailsWith<CampusException> { failed.finish() }
            failed.abort(); platform.commitWorks = true
            assertEquals(3, platform.ended, "A failed commit must release its folder exactly once")
            assertContentEquals(byteArrayOf(1, 2), readIosUpload(path, "notes.pdf", "", "c", "f").bytes)
            val privateWriter = storage.create(scope, Document("1", "c", "f", "copy.pdf"))
            privateWriter.write(byteArrayOf(5, 6)); val privatePath = privateWriter.finish()
            val exported = storage.saveToFolder(privatePath, "application/pdf", "copy.pdf", destination)
            assertContentEquals(byteArrayOf(5, 6), readIosUpload(exported, "copy.pdf", "", "c", "f").bytes)
            assertEquals(4, platform.ended)
            assertFailsWith<CampusException> { storage.syncWriter(MaterialDestination("invalid", "bad"), emptyList(), "x", "") }
        } finally { storage.clear(scope); NSFileManager.defaultManager.removeItemAtPath(root, null) }
        Unit
    }

}
