package com.melvin.velo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.torproject.jni.TorService
import kotlin.concurrent.thread

/**
 * Tor embebido en la propia app (tor-android de The Guardian Project).
 * No necesita Orbot: el proceso de Tor vive dentro de Velo y su puerto de
 * control es un socket Unix privado de la app.
 */
class TorManager(private val context: Context) {

    interface Listener {
        fun onProgress(percent: Int, summary: String)
        fun onReady(socksPort: Int, httpTunnelPort: Int)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var service: TorService? = null
    private var connection: ServiceConnection? = null
    @Volatile private var ready = false

    fun start(listener: Listener) {
        // Configuración propia: puertos aleatorios y un circuito por destino.
        TorService.getTorrc(context).writeText(
            """
            SocksPort auto IsolateDestAddr
            HTTPTunnelPort auto
            ClientOnly 1
            AvoidDiskWrites 1
            SafeLogging 1
            """.trimIndent() + "\n"
        )
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val tor = (binder as TorService.LocalBinder).service
                service = tor
                thread(name = "tor-bootstrap") { waitForBootstrap(tor, listener) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                main.post { listener.onError("El servicio de Tor se detuvo. Las conexiones quedan bloqueadas.") }
            }
        }
        connection = conn
        context.bindService(Intent(context, TorService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private fun waitForBootstrap(tor: TorService, listener: Listener) {
        val deadline = System.currentTimeMillis() + BOOTSTRAP_TIMEOUT_MS
        var last = -1
        while (System.currentTimeMillis() < deadline) {
            val phase = try { tor.getInfo("status/bootstrap-phase") } catch (_: Exception) { null }
            if (phase != null) {
                val pct = PROGRESS.find(phase)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val summary = SUMMARY.find(phase)?.groupValues?.get(1) ?: ""
                if (pct != last) {
                    last = pct
                    main.post { listener.onProgress(pct, summary) }
                }
                if (pct >= 100) {
                    val socks = tor.socksPort
                    val http = tor.httpTunnelPort
                    if (socks > 0) {
                        ready = true
                        main.post { listener.onReady(socks, http) }
                        return
                    }
                }
            }
            Thread.sleep(400)
        }
        main.post { listener.onError("Tor no terminó de conectarse a tiempo. Comprueba tu conexión e inténtalo de nuevo.") }
    }

    /** Pide a Tor circuitos nuevos (otra IP de salida). */
    fun newIdentity(): Boolean = try {
        service?.torControlConnection?.signal("NEWNYM")
        true
    } catch (_: Exception) {
        false
    }

    fun stop() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
        context.stopService(Intent(context, TorService::class.java))
    }

    companion object {
        private const val BOOTSTRAP_TIMEOUT_MS = 180_000L
        private val PROGRESS = Regex("PROGRESS=(\\d+)")
        private val SUMMARY = Regex("SUMMARY=\"([^\"]*)\"")
    }
}
