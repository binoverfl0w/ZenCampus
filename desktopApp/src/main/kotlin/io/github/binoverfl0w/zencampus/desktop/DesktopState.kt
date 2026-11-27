package io.github.binoverfl0w.zencampus.desktop

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.runtime.snapshots.SnapshotMutableState
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.io.File

private fun supportedUiEnum(name: String): Boolean = name.startsWith("io.github.binoverfl0w.zencampus.model.") ||
    name.startsWith("io.github.binoverfl0w.zencampus.ui.") ||
    // Material's SheetState.Saver returns this enum, including Hidden before the sheet opens.
    name == "androidx.compose.material3.SheetValue"

/** Explicit JSON types; saved UI state never uses Java object deserialization. */
@Serializable internal data class UiValue(val type: String, val value: String = "", val items: List<UiValue> = emptyList()) {
    fun decode(): Any? = when(type) {
        "null" -> null; "string" -> value; "int" -> value.toInt(); "long" -> value.toLong()
        "float" -> value.toFloat(); "double" -> value.toDouble(); "boolean" -> value.toBooleanStrict()
        "list" -> items.map { it.decode() }
        "map" -> items.chunked(2).associate { it[0].decode() to it[1].decode() }
        "int-array" -> items.map { it.value.toInt() }.toIntArray()
        "lazy-key" -> Class.forName("androidx.compose.foundation.lazy.layout.DefaultLazyKey")
            .getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(value.toInt())
        "int-state" -> mutableIntStateOf(value.toInt())
        "long-state" -> mutableLongStateOf(value.toLong())
        "float-state" -> mutableFloatStateOf(value.toFloat())
        "double-state" -> mutableDoubleStateOf(value.toDouble())
        "state" -> mutableStateOf(items.single().decode(), when(value) {
            "structural" -> structuralEqualityPolicy(); "referential" -> referentialEqualityPolicy()
            "never" -> neverEqualPolicy(); else -> error("Unknown state policy")
        })
        "enum" -> {
            val name = value.substringBefore('#')
            require(supportedUiEnum(name))
            Class.forName(name).enumConstants.first { (it as Enum<*>).name == value.substringAfter('#') }
        }
        else -> error("Unknown UI state type")
    }
    companion object {
        fun encode(value: Any?): UiValue? = when(value) {
            null -> UiValue("null")
            is String -> UiValue("string", value)
            is Int -> UiValue("int", value.toString())
            is Long -> UiValue("long", value.toString())
            is Float -> UiValue("float", value.toString())
            is Double -> UiValue("double", value.toString())
            is Boolean -> UiValue("boolean", value.toString())
            is Enum<*> -> if (supportedUiEnum(value.javaClass.name)) UiValue("enum", "${value.javaClass.name}#${value.name}") else null
            is List<*> -> encodeItems(value)?.let { UiValue("list", items = it) }
            is Map<*, *> -> encodeItems(value.entries.flatMap { listOf(it.key, it.value) })?.let { UiValue("map", items = it) }
            is IntArray -> UiValue("int-array", items = value.map { UiValue("int", it.toString()) })
            is MutableIntState -> UiValue("int-state", value.intValue.toString())
            is MutableLongState -> UiValue("long-state", value.longValue.toString())
            is MutableFloatState -> UiValue("float-state", value.floatValue.toString())
            is MutableDoubleState -> UiValue("double-state", value.doubleValue.toString())
            is SnapshotMutableState<*> -> {
                val policy = when(value.policy) {
                    structuralEqualityPolicy<Any?>() -> "structural"
                    referentialEqualityPolicy<Any?>() -> "referential"
                    neverEqualPolicy<Any?>() -> "never"
                    else -> null
                }
                if (policy == null) null else encode(value.value)?.let { UiValue("state", policy, listOf(it)) }
            }
            // Compose's own default lazy-item key has no public saver. Restrict reflection to this exact pinned type.
            else -> if (value.javaClass.name == "androidx.compose.foundation.lazy.layout.DefaultLazyKey")
                UiValue("lazy-key", value.javaClass.getDeclaredField("index").apply { isAccessible = true }.getInt(value).toString())
                else null
        }
        private fun encodeItems(items: List<*>): List<UiValue>? {
            val values = items.map { encode(it) }
            return if (values.any { it == null }) null else values.filterNotNull()
        }
    }
}

internal class DesktopStateStore(private val file: File) {
    val registry = SaveableStateRegistry(restoredValues = runCatching {
        if (!file.exists()) null else desktopJson.decodeFromString<Map<String, List<UiValue>>>(file.readText())
            .mapValues { (_, values) -> values.map { it.decode() } }
    }.getOrNull(), canBeSaved = { UiValue.encode(it) != null })
    private var previous: String? = null
    fun save(allowed: Boolean = true) {
        if (!allowed) { file.delete(); return }
        val values = registry.performSave().mapValues { (_, values) -> values.map { checkNotNull(UiValue.encode(it)) } }
        // Child providers can unregister before their parent's onDispose runs. Keep the last real snapshot.
        if (values.isEmpty()) return
        val text = desktopJson.encodeToString(values)
        if (text != previous) { atomicPrivateWrite(file, text.toByteArray()); previous = text }
    }
}

@Composable
internal fun PersistentDesktopState(container: DesktopContainer, owner: String,
    onSaveReady: (() -> Unit) -> Unit = {}, content: @Composable () -> Unit) {
    val store = remember(owner) { DesktopStateStore(File(container.paths.data, "ui-${digest(owner)}.json")) }
    fun save() = runCatching { store.save(owner == "public" || container.repository.value?.account?.cacheScope == owner) }
    LaunchedEffect(store) { while (true) { delay(3_000); save() } }
    DisposableEffect(store) { onSaveReady { save() }; onDispose { save() } }
    CompositionLocalProvider(LocalSaveableStateRegistry provides store.registry, content = content)
}
