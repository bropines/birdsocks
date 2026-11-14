package io.github.bropines.birdsocks.admin.areas.keys

import io.github.bropines.birdsocks.admin.api.ApiSetupKey
import io.github.bropines.birdsocks.ui.parseRfc3339Millis

/** What a setup key is now, and the Setup keys list's filters; plain Kotlin, tested on the JVM. */
object KeyQuery {
    enum class State { VALID, EXPIRED, REVOKED, OVERUSED, INVALID }

    const val VALID = "valid"
    const val EXPIRED = "expired"
    const val REVOKED = "revoked"
    const val REUSABLE = "reusable"

    /**
     * The server's [ApiSetupKey.state] when it says one; otherwise from the
     * fields: revoked beats expired beats used up.
     */
    fun state(key: ApiSetupKey, now: Long = System.currentTimeMillis()): State = when {
        key.revoked || key.state == "revoked" -> State.REVOKED
        key.state == "expired" -> State.EXPIRED
        key.state == "overused" -> State.OVERUSED
        key.state == "valid" -> State.VALID
        parseRfc3339Millis(key.expires)?.let { it < now } == true -> State.EXPIRED
        key.usageLimit > 0 && key.usedTimes >= key.usageLimit -> State.OVERUSED
        key.valid -> State.VALID
        else -> State.INVALID
    }

    fun filters(now: Long = System.currentTimeMillis()): Map<String, (ApiSetupKey) -> Boolean> = linkedMapOf(
        VALID to { k -> state(k, now) == State.VALID },
        EXPIRED to { k -> state(k, now) == State.EXPIRED },
        REVOKED to { k -> state(k, now) == State.REVOKED },
        REUSABLE to { k -> k.isReusable },
    )
}
