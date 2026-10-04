package io.github.bropines.birdsocks.core

import android.content.Context
import android.util.Log
import appctr.Appctr
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket

/**
 * ByeDPI (byedpi's ciadpi, JNI, in the app's process): a SOCKS5 listener on a
 * random loopback address that desyncs what passes through it, so DPI that
 * blocks the NetBird server by its TLS handshake lets it by. The daemon
 * reaches its control plane through it (Settings → Server connection);
 * nothing else does.
 */
object ByeDpiProxy {
    private const val TAG = "ByeDpiProxy"

    /** Used when the user's flags are empty or none survive [ByeDpiFlags]. */
    const val DEFAULT_FLAGS = "-o1 -a1 -r-5+se"

    @Volatile
    private var isRunning = false
    private var proxyThread: Thread? = null

    init {
        try {
            System.loadLibrary("byedpi")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load libbyedpi.so", e)
        }
    }

    @JvmStatic
    private external fun jniStartProxy(args: Array<String>): Int
    @JvmStatic
    private external fun jniStopProxy(): Int
    @JvmStatic
    private external fun jniForceClose(): Int
    @JvmStatic
    private external fun jniSetLogPath(path: String?)

    private var logReaderThread: Thread? = null
    @Volatile
    private var stopLogReader = false

    /** Where it listens while it runs; read from composition, written by start/stop and the proxy thread. */
    @Volatile
    var activeAddress: Pair<String, Int>? = null
        private set

    /**
     * Starts it on a random 127.x.x.x address and free port and returns that
     * address, or null when it already runs. [ipv4Only] passes `-X`.
     */
    @Synchronized
    fun start(context: Context, customFlags: String, ipv4Only: Boolean): Pair<String, Int>? {
        if (isRunning) return null
        // A previous run may still be winding down natively: a flags change
        // (stop, then start) must not overlap it.
        proxyThread?.let { if (it.isAlive) it.join(1500) }
        isRunning = true

        // Not 127.0.0.1: an app scanning the usual loopback ports does not find it.
        val ip = "127.${(2..254).random()}.${(2..254).random()}.${(2..254).random()}"
        val port = try {
            ServerSocket(0).use { it.localPort }
        } catch (e: Exception) {
            (30000..65000).random()
        }

        // socks5://: byedpi answers SOCKS5 there and nothing else.
        val args = mutableListOf("byedpi", "-i", "socks5://$ip", "-p", port.toString())
        if (ipv4Only) args.add("-X")
        // Only desync and tuning options come from the user's string; anything
        // that binds, forks or touches files is dropped (see ByeDpiFlags).
        val checked = ByeDpiFlags.sanitize(customFlags.ifBlank { DEFAULT_FLAGS })
        if (checked.rejected.isNotEmpty()) {
            Log.w(TAG, "Ignoring ByeDPI flags outside the allow-list: ${checked.rejected}")
            Appctr.logAndroid("WARN", "CORE", "ByeDPI: ignored flags ${checked.rejected.joinToString(" ")}; only desync and tuning options are accepted")
        }
        args.addAll(checked.accepted.ifEmpty { ByeDpiFlags.sanitize(DEFAULT_FLAGS).accepted })

        val logFile = File(context.cacheDir, "byedpi.log")
        runCatching { jniSetLogPath(logFile.absolutePath) }.onFailure { Log.e(TAG, "Failed to set log path", it) }

        val address = ip to port
        activeAddress = address
        val t = Thread {
            Appctr.logAndroid("INFO", "CORE", "ByeDPI: starting on $ip:$port with ${args.drop(5).joinToString(" ")}")
            startLogReader(logFile)
            val code = runCatching { jniStartProxy(args.toTypedArray()) }.getOrElse {
                Appctr.logAndroid("ERROR", "CORE", "ByeDPI: ${it.message}")
                -1
            }
            Appctr.logAndroid("INFO", "CORE", "ByeDPI: stopped ($code)")
            stopLogReader()
            activeAddress = null
            isRunning = false
        }
        proxyThread = t
        t.start()
        return address
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        // Cleared now, so a start() right after is not refused while the
        // native side winds down.
        isRunning = false
        activeAddress = null
        stopLogReader()
        Thread {
            runCatching { jniStopProxy(); jniForceClose() }
        }.start()
    }

    /** byedpi writes its log to a file; its lines go on to the app's log. */
    private fun startLogReader(file: File) {
        stopLogReader = false
        logReaderThread = Thread {
            try {
                var pos = 0L
                while (!stopLogReader) {
                    if (file.exists()) {
                        val len = file.length()
                        if (len > pos) {
                            RandomAccessFile(file, "r").use { raf ->
                                raf.seek(pos)
                                var line = raf.readLine()
                                while (line != null) {
                                    val text = String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8).trim()
                                    if (text.isNotEmpty()) {
                                        val bad = text.contains("error", true) || text.contains("fail", true)
                                        Appctr.logAndroid(if (bad) "WARN" else "INFO", "CORE", "ByeDPI: $text")
                                    }
                                    line = raf.readLine()
                                }
                                pos = raf.filePointer
                            }
                        } else if (len < pos) {
                            pos = 0L
                        }
                    }
                    Thread.sleep(500)
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Log.e(TAG, "Log reader error", e)
            }
        }.apply { start() }
    }

    private fun stopLogReader() {
        stopLogReader = true
        logReaderThread?.interrupt()
        logReaderThread = null
    }
}
