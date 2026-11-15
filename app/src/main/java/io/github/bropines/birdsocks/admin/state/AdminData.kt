package io.github.bropines.birdsocks.admin.state

import androidx.compose.runtime.mutableStateMapOf
import io.github.bropines.birdsocks.admin.api.Listing
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.core.AppJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

/**
 * One piece of data from the API on its way to the screen: what was last
 * read ([value], kept while a refresh runs or fails), why the last read
 * failed, whether one is running, and whether [value] came from the
 * on-disk cache rather than the server.
 */
data class Loadable<T>(
    val value: T? = null,
    val error: Throwable? = null,
    val loading: Boolean = false,
    /** Epoch millis of the read that produced [value]; 0 for never. */
    val loadedAt: Long = 0,
    /** [value] is the cache's copy: shown at once, replaced when the server answers. */
    val fromDisk: Boolean = false,
) {
    /** Nothing to show yet and nothing failed: a spinner. */
    val isInitialLoad: Boolean get() = value == null && error == null
}

/** How a [DataKey]'s value goes in and out of the sealed cache. */
class CacheCodec<T>(val encode: (T) -> JsonElement, val decode: (JsonElement) -> T)

/**
 * A piece of shared, account-wide data the panel reads: its id (stable — the
 * cache file keys by it), the permission module that governs reading it,
 * how to read it, and how to cache it. A null [codec] is never written to
 * disk: invites, tokens, event-streaming configs and audit events carry
 * secrets or are not worth keeping.
 *
 * The panel's keys are in [AdminKeys]; an area with data of its own that
 * other areas do not need keeps it in its own state holder instead.
 */
class DataKey<T>(
    val id: String,
    val module: String,
    val codec: CacheCodec<T>? = null,
    val load: suspend (NetbirdApi) -> T,
) {
    val cacheable: Boolean get() = codec != null
    override fun toString() = "DataKey($id)"

    companion object {
        /** A list key: cached element by element, [strip] removing anything secret first. */
        fun <E> list(
            id: String,
            module: String,
            serializer: KSerializer<E>,
            cache: Boolean = true,
            strip: (E) -> E = { it },
            load: suspend (NetbirdApi) -> Listing<E>,
        ): DataKey<Listing<E>> = DataKey(
            id, module,
            codec = if (!cache) null else CacheCodec(
                encode = { AppJson.encodeToJsonElement(ListSerializer(serializer), it.items.map(strip)) },
                // What the cache holds decoded once already; a copy that no longer reads is dropped whole.
                decode = { Listing(AppJson.decodeFromJsonElement(ListSerializer(serializer), it)) },
            ),
            load = load,
        )

        /** A single-object key. */
        fun <T> single(
            id: String,
            module: String,
            serializer: KSerializer<T>,
            cache: Boolean = true,
            load: suspend (NetbirdApi) -> T,
        ): DataKey<T> = DataKey(
            id, module,
            codec = if (!cache) null else CacheCodec(
                encode = { AppJson.encodeToJsonElement(serializer, it) },
                decode = { AppJson.decodeFromJsonElement(serializer, it) },
            ),
            load = load,
        )
    }
}

/**
 * The account's shared data, one [Loadable] per [DataKey], as Compose state:
 * a screen reading `data[AdminKeys.PEERS]` recomposes when that key changes
 * and no other. Written only by the AdminViewModel (load, refresh, seed from
 * the cache); screens read.
 */
class AdminData {
    private val entries = mutableStateMapOf<String, Loadable<*>>()

    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: DataKey<T>): Loadable<T> = (entries[key.id] as Loadable<T>?) ?: Loadable()

    /** The items of a list key, or an empty list before it is read. */
    fun <E> items(key: DataKey<Listing<E>>): List<E> = get(key).value?.items.orEmpty()

    internal fun <T> set(key: DataKey<T>, value: Loadable<T>) {
        entries[key.id] = value
    }

    internal fun <T> update(key: DataKey<T>, change: (Loadable<T>) -> Loadable<T>) = set(key, change(get(key)))

    internal fun clear() = entries.clear()

    /** What of the cacheable keys in [keys] has a value read from the server, for the cache. */
    internal fun snapshot(keys: Collection<DataKey<*>>): Map<String, AdminCache.Entry> = buildMap {
        for (key in keys) {
            @Suppress("UNCHECKED_CAST")
            val k = key as DataKey<Any?>
            val codec = k.codec ?: continue
            val l = this@AdminData[k]
            val v = l.value ?: continue
            runCatching { put(k.id, AdminCache.Entry(l.loadedAt, codec.encode(v))) }
        }
    }

    /** Fills the keys the cache had, marked as from disk, where nothing newer is held. */
    internal fun seed(keys: Collection<DataKey<*>>, cached: Map<String, AdminCache.Entry>) {
        for (key in keys) {
            @Suppress("UNCHECKED_CAST")
            val k = key as DataKey<Any?>
            val codec = k.codec ?: continue
            val e = cached[k.id] ?: continue
            if (get(k).value != null) continue
            val value = runCatching { codec.decode(e.value) }.getOrNull() ?: continue
            set(k, Loadable(value, loadedAt = e.at, fromDisk = true))
        }
    }
}
