package io.github.bropines.birdsocks.core

import appctr.Subscription
import io.github.bropines.birdsocks.models.NbEvent
import io.github.bropines.birdsocks.models.NbExposeReady
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** Adds what is new among [incoming]; returns only those. */
    @Synchronized
    fun add(incoming: List<NbEvent>): List<NbEvent> {
        val known = eventsFlow.value.mapTo(HashSet()) { it.id }
        val fresh = incoming.filter { it.id.isNotEmpty() && it.id !in known }
        if (fresh.isNotEmpty()) {
            eventsFlow.value = (eventsFlow.value + fresh).sortedBy { it.timestamp ?: "" }.takeLast(MAX)
        }
        return fresh
    }

    fun clear() { eventsFlow.value = emptyList() }
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
