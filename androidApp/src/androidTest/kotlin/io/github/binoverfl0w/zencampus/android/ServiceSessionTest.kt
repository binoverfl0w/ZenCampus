package io.github.binoverfl0w.zencampus.android

import io.github.binoverfl0w.zencampus.data.LibrarySession
import io.github.binoverfl0w.zencampus.data.SavedSession
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.binoverfl0w.zencampus.model.Account
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceSessionTest {
    @Test fun existingDigiCampusSessionFormatAndLibraryEncryptionRemainIndependent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureSessionStore(context)
        val previous = store.read()
        val previousLibrary = store.readLibrary()
        val campus = SavedSession(Account("fixture", "Fixture", "fixture", demo = true), "fixture_digi_cookie")
        val library = LibrarySession("fixture", "fixture_library_cookie")
        try {
            store.write(campus); store.writeLibrary(library)
            assertEquals(campus, SecureSessionStore(context).read())
            assertEquals(library, SecureSessionStore(context).readLibrary())
            val values = context.getSharedPreferences("zen_session", Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(values.contains("fixture_digi_cookie")); assertFalse(values.contains("fixture_library_cookie"))
            store.clear()
            assertNull(store.read()); assertEquals(library, store.readLibrary())
            store.write(campus); store.clearLibrary()
            assertEquals(campus, store.read()); assertNull(store.readLibrary())
        } finally {
            store.clear(); store.clearLibrary()
            previous?.let(store::write); previousLibrary?.let(store::writeLibrary)
        }
    }
}
