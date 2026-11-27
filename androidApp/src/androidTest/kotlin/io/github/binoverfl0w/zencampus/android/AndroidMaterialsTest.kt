package io.github.binoverfl0w.zencampus.android

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.binoverfl0w.zencampus.data.DemoProvider
import io.github.binoverfl0w.zencampus.model.Document
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidMaterialsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private suspend fun ready() { (context.applicationContext as ZenApplication).container.ready.first { it } }
    private suspend fun pdf(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        DemoProvider().download(Document("fixture", "c", "f", "notes.pdf")) { out.write(it) }
        return out.toByteArray()
    }

    @Test fun legacyBinAliasesKeepTheirContentAndOpenAcrossARealUidWithCorrectNameAndType() { runBlocking {
        ready()
        val storage = AndroidMaterials(context); val scope = "material-native-${UUID.randomUUID()}"
        try {
            val bytes = pdf()
            val legacy = File(context.filesDir, "materials/${AndroidMaterials.digest(scope)}/123456789.bin").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            val document = Document("legacy", "c", "f", "01 · Übung.PDF", mimeType = "application/octet-stream")
            val path = storage.prepare(legacy.absolutePath, document)
            assertEquals(document.name, File(path).name); assertTrue(legacy.isFile); assertArrayEquals(bytes, File(path).readBytes())
            val info = storage.presentation(path, document.mimeType, document.name)
            val view = storage.viewIntent(info)
            assertEquals("application/pdf", view.type); assertEquals("application/pdf", context.contentResolver.getType(view.data!!))
            context.contentResolver.query(view.data!!, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(document.name, it.getString(0))
            }
            assertEquals(document.name, view.data!!.lastPathSegment!!.substringAfterLast('/'))
            assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0); assertNotNull(view.clipData)
            view.component = ComponentName(instrumentation.context.packageName, MaterialViewerActivity::class.java.name)
            context.startActivity(view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val device = UiDevice.getInstance(instrumentation)
            assertTrue(device.wait(Until.hasObject(By.textContains("Header: %PDF-")), 10_000))
            assertTrue(device.hasObject(By.textContains("Name: ${document.name}")))
            assertTrue(device.hasObject(By.textContains("Type: application/pdf")))
            device.pressBack()
            storage.cleanup(scope, setOf(path))
            assertFalse(legacy.exists()); assertTrue(File(path).isFile)
        } finally { storage.clear(scope) }
    } }

    @Test fun modernDownloadsAreNamedTypedPublishedAndSurvivePrivateCacheRemoval() { runBlocking {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 29)
        ready()
        val scope = "material-export-${UUID.randomUUID()}"; val storage = AndroidMaterials(context)
        val name = "Lecture ${UUID.randomUUID()}.pdf"; var exported: Uri? = null
        try {
            val bytes = pdf(); val document = Document("export", "c", "f", name, mimeType = "application/octet-stream")
            val writer = storage.create(scope, document); writer.write(bytes); val path = writer.finish()
            assertEquals(name, File(path).name)
            assertEquals("Downloads/ZenCampus/$name", storage.saveToDownloads(path, document.mimeType, name))
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.IS_PENDING),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null)!!.use {
                assertTrue(it.moveToFirst()); exported = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0))
                assertEquals(name, it.getString(1)); assertEquals("application/pdf", it.getString(2)); assertEquals("Download/ZenCampus/", it.getString(3)); assertEquals(0, it.getInt(4))
            }
            storage.clear(scope)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(exported!!)!!.use { it.readBytes() })
        } finally { exported?.let { context.contentResolver.delete(it, null, null) }; storage.clear(scope) }
    } }

    @Test fun partialFilesAreRemovedWithoutDeletingOtherAccountsOrNamedCompletedFiles() { runBlocking {
        ready()
        val scope = "material-clean-${UUID.randomUUID()}"; val other = "material-clean-${UUID.randomUUID()}"
        val storage = AndroidMaterials(context)
        try {
            val a = storage.create(scope, Document("a", "c", "f", "Notes.docx")); a.write(byteArrayOf(1,2)); val path = a.finish()
            for (hint in listOf("application/octet-stream", "application/zip", "application/msword")) {
                assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", storage.presentation(path, hint, "Notes.docx").mime)
            }
            val b = storage.create(other, Document("b", "c", "f", "Other.txt")); b.write(byteArrayOf(4)); val otherPath = b.finish()
            val partial = storage.create(scope, Document("partial", "c", "f", "Pending.txt")); partial.write(byteArrayOf(5)); partial.abort()
            storage.cleanup(scope, setOf(path))
            assertTrue(storage.exists(path)); assertTrue(storage.exists(otherPath))
            val files = File(context.filesDir, "materials/${AndroidMaterials.digest(scope)}").walkTopDown().filter { it.isFile }.toList()
            assertEquals(listOf("Notes.docx"), files.map { it.name })
            storage.clear(scope); assertTrue(storage.exists(otherPath))
        } finally { storage.clear(scope); storage.clear(other) }
    } }
}
