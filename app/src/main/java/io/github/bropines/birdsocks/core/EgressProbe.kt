package io.github.bropines.birdsocks.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Whether the internet answers through BirdSocks' own proxy: a SOCKS5 CONNECT
 * to a few well-known addresses, the way an app behind the proxy would make
 * one. An exit node the admin applies can be up as a peer and still forward
 * nothing — then every app behind the proxy hangs, and only peers answer.
 */
object EgressProbe {
    /** By address, so a dead resolver does not read as a dead exit node; one per region. */
    private val TARGETS = listOf("1.1.1.1", "8.8.8.8", "77.88.8.8")
    private const val TIMEOUT_MS = 8_000

    /** True when any target accepts a connection through the proxy. */
    suspend fun internetThroughProxy(context: Context): Boolean = coroutineScope {
        val host = if (GlobalSettings.isSocksLanShared(context)) NetAddr.LOOPBACK_V4 else GlobalSettings.getSocksHost(context)
        val port = GlobalSettings.getSocksPort(context)
        val user = GlobalSettings.getSocksUser(context)
        val pass = GlobalSettings.getSocksPass(context)
        TARGETS.map { target -> async(Dispatchers.IO) { runCatching { connect(host, port, user, pass, target) }.getOrDefault(false) } }
            .awaitAll().any { it }
    }

    private suspend fun connect(host: String, port: Int, user: String, pass: String, target: String): Boolean =
        withContext(Dispatchers.IO) {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                s.soTimeout = TIMEOUT_MS
                val out = s.getOutputStream()
                val inp = DataInputStream(s.getInputStream())
                val auth = user.isNotEmpty()
                out.write(byteArrayOf(5, 1, if (auth) 2 else 0))
                if (inp.readByte().toInt() != 5) return@withContext false
                when (inp.readByte().toInt()) {
                    0 -> Unit
                    2 -> {
                        val u = user.toByteArray()
                        val p = pass.toByteArray()
                        out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                        inp.readByte()
                        if (inp.readByte().toInt() != 0) return@withContext false
                    }
                    else -> return@withContext false
                }
                val ip = target.split('.').map { it.toInt().toByte() }.toByteArray()
                out.write(byteArrayOf(5, 1, 0, 1) + ip + byteArrayOf(0x01, 0xBB.toByte()))
                // The reply comes once the proxy's own connection is up, or refused.
                inp.readByte().toInt() == 5 && inp.readByte().toInt() == 0
            }
        }
}
