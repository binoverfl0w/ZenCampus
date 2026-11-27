package io.github.binoverfl0w.zencampus.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/** Store session cookies in the macOS login keychain, without passing secrets in process arguments. */
internal class MacKeychainSessionVault(private val profile: String) : SessionVault {
    private interface Security : Library {
        fun SecKeychainFindGenericPassword(keychain: Pointer?, serviceLength: Int, service: ByteArray, accountLength: Int, account: ByteArray, length: IntByReference, data: PointerByReference, item: PointerByReference): Int
        fun SecKeychainAddGenericPassword(keychain: Pointer?, serviceLength: Int, service: ByteArray, accountLength: Int, account: ByteArray, length: Int, data: ByteArray, item: PointerByReference?): Int
        fun SecKeychainItemModifyAttributesAndData(item: Pointer, attributes: Pointer?, length: Int, data: ByteArray): Int
        fun SecKeychainItemFreeContent(attributes: Pointer?, data: Pointer?): Int
        fun SecKeychainItemDelete(item: Pointer): Int
    }
    private interface CoreFoundation : Library { fun CFRelease(value: Pointer) }
    private val security by lazy { Native.load("/System/Library/Frameworks/Security.framework/Security", Security::class.java) }
    private val core by lazy { Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", CoreFoundation::class.java) }
    private val account = profile.toByteArray(Charsets.UTF_8)
    private fun identifier(service: String) = "io.github.binoverfl0w.zencampus.$service".toByteArray(Charsets.UTF_8)
    private fun checkStatus(status: Int) { check(status == 0) { "macOS Keychain error $status" } }

    private inline fun <T> withItem(service: String, action: (Pointer?, ByteArray?) -> T): T {
        val name = identifier(service)
        val length = IntByReference()
        val data = PointerByReference()
        val item = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(null, name.size, name, account.size, account, length, data, item)
        if (status == -25300) return action(null, null)
        checkStatus(status)
        val secret = data.value?.getByteArray(0, length.value)
        try { return action(item.value, secret) }
        finally {
            secret?.fill(0)
            if (data.value != null) security.SecKeychainItemFreeContent(null, data.value)
            if (item.value != null) core.CFRelease(item.value)
        }
    }
    @Synchronized override fun read(service: String): String? = withItem(service) { _, bytes -> bytes?.toString(Charsets.UTF_8) }
    @Synchronized override fun write(service: String, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            withItem(service) { item, _ ->
                if (item != null) checkStatus(security.SecKeychainItemModifyAttributesAndData(item, null, bytes.size, bytes))
                else {
                    val name = identifier(service)
                    checkStatus(security.SecKeychainAddGenericPassword(null, name.size, name, account.size, account, bytes.size, bytes, null))
                }
            }
        } finally { bytes.fill(0) }
    }
    @Synchronized override fun clear(service: String) { withItem(service) { item, _ -> if (item != null) checkStatus(security.SecKeychainItemDelete(item)) } }
}
