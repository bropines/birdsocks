package io.github.bropines.birdsocks.core

import android.content.Context
import appctr.Subscription
import io.github.bropines.birdsocks.models.NbEvent
import io.github.bropines.birdsocks.models.NbExposeReady
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NetBird's system events — network, DNS, sign-in, connectivity — newest
 * last. The daemon keeps no history to ask for (GetEvents is not served);
 * the status carries the recent ones and the service streams the rest.
 */
object EventLog {
    private const val MAX = 200

    private val eventsFlow = MutableStateFlow<List<NbEvent>>(emptyList())
    val events: StateFlow<List<NbEvent>> = eventsFlow.asStateFlow()

    // Kept in a file so the history outlives the app's process.
    private var file: java.io.File? = null

    /** Loads the saved history once per process. */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = java.io.File(context.filesDir, "events.json")
        file = f
        eventsFlow.value = runCatching { AppJson.decodeFromString<List<NbEvent>>(f.readText()) }.getOrDefault(emptyList())
    }

    /** Adds what is new among [incoming]; returns only those. */
    @Synchronized
    fun add(incoming: List<NbEvent>): List<NbEvent> {
        val known = eventsFlow.value.mapTo(HashSet()) { it.id }
        // The daemon greets every new subscriber with its log level, under a new id each time.
        var level = eventsFlow.value.lastOrNull { it.metadata["kind"] == LEVEL_KIND }?.metadata?.get("level")
        val fresh = incoming.filter { e ->
            if (e.id.isEmpty() || e.id in known) return@filter false
            if (e.metadata["kind"] == LEVEL_KIND) {
                if (e.metadata["level"] == level) return@filter false
                level = e.metadata["level"]
            }
            true
        }
        if (fresh.isNotEmpty()) {
            eventsFlow.value = (eventsFlow.value + fresh).sortedBy { it.timestamp ?: "" }.takeLast(MAX)
            save()
        }
        return fresh
    }

    @Synchronized
    fun clear() {
        eventsFlow.value = emptyList()
        save()
    }

    private fun save() {
        val f = file ?: return
        runCatching { f.writeText(AppJson.encodeToString<List<NbEvent>>(eventsFlow.value)) }
    }

    private const val LEVEL_KIND = "log-level-changed"
}

/**
 * Publishing a local port through the account's NetBird reverse proxy. It
 * lives in the process, not in a screen: the service's foreground keeps it
 * up, and its notification stops it.
 */
object ExposeFlow {
    sealed interface State {
        data object Idle : State
        data class Starting(val port: Int) : State
        data class Live(val port: Int, val ready: NbExposeReady) : State
        data class Failed(val reason: String) : State
    }

    private val stateFlow = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = stateFlow.asStateFlow()

    private var sub: Subscription? = null
    // Which start a callback belongs to: an old stream ending must not touch a new one.
    private var generation = 0

    @Synchronized
    fun start(port: Int, protocol: String, namePrefix: String, pin: String, password: String, groups: List<String>) {
        sub?.cancel()
        val gen = ++generation
        stateFlow.value = State.Starting(port)
        sub = Netbird.expose(port, protocol, namePrefix, pin, password, groups,
            onReady = { ready -> synchronized(this) { if (gen == generation) stateFlow.value = State.Live(port, ready) } },
            onEnd = { err ->
                synchronized(this) {
                    if (gen != generation) return@synchronized
                    sub = null
                    // The daemon ending it is the address gone, not a failure to show.
                    stateFlow.value = if (err.isEmpty() || err == Netbird.STREAM_DONE) State.Idle else State.Failed(err)
                }
            })
    }

    /** Takes the port down; its stream's end is not a failure. */
    @Synchronized
    fun stop() {
        generation++
        stateFlow.value = State.Idle
        sub?.cancel()
        sub = null
    }

    fun dismiss() { if (stateFlow.value is State.Failed) stateFlow.value = State.Idle }
}
