package io.github.binoverfl0w.zencampus.android

import io.github.binoverfl0w.zencampus.data.LibrarySession
import io.github.binoverfl0w.zencampus.data.SavedSession

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.binoverfl0w.zencampus.model.Account
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec


/** Android Keystore encrypts the entire record. It is excluded from backups. */
class SecureSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("zen_session", Context.MODE_PRIVATE)
    private val alias = "zencampus.session.v1"
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized fun read(): SavedSession? {
        val saved = prefs.getString("record", null) ?: return null
        return runCatching {
            val objectValue = JSONObject(unseal(saved))
            SavedSession(json.decodeFromString<Account>(objectValue.getString("account")), objectValue.getString("cookie"),
                objectValue.optString("generation"))
        }.getOrElse { prefs.edit().remove("record").commit(); null }
    }
    @Synchronized fun write(session: SavedSession) {
        val plaintext = JSONObject().put("account", json.encodeToString(session.account)).put("cookie", session.cookie)
            .put("generation", session.generation).toString()
        writeSecret("record", plaintext)
    }
    @Synchronized fun clear() { prefs.edit().remove("record").commit() }

    private fun seal(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        return try {
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(bytes), Base64.NO_WRAP)
        } finally { bytes.fill(0) }
    }

    private fun unseal(encrypted: String): String {
        val parts = encrypted.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        val bytes = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
        return try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
    }

    @Synchronized internal fun readSecret(service: String): String? = prefs.getString(service, null)?.let(::unseal)
    @Synchronized internal fun writeSecret(service: String, value: String) {
        check(prefs.edit().putString(service, seal(value)).commit()) { "Secure storage unavailable" }
    }
    @Synchronized internal fun deleteSecret(service: String) {
        check(prefs.edit().remove(service).commit()) { "Secure storage unavailable" }
    }

    @Synchronized fun readLibrary(): LibrarySession? {
        val saved = prefs.getString("library", null) ?: return null
        return runCatching {
            val value = JSONObject(unseal(saved))
            LibrarySession(value.getString("connectionId"), value.getString("cookie"))
        }.getOrElse { prefs.edit().remove("library").commit(); null }
    }
    @Synchronized fun writeLibrary(session: LibrarySession) {
        writeSecret("library", JSONObject().put("connectionId", session.connectionId).put("cookie", session.cookie).toString())
    }
    @Synchronized fun clearLibrary() { prefs.edit().remove("library").commit() }
}
