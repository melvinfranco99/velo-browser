package com.melvin.velo

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.torproject.jni.TorService
import java.util.concurrent.atomic.AtomicBoolean
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
    private var receiver: BroadcastReceiver? = null
    private var listener: Listener? = null
    @Volatile private var ready = false

    fun start(listener: Listener) {
        this.listener = listener
        ready = false
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

        // TorService anuncia "ON" cuando está autenticado y tiene su primer circuito.
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getStringExtra(TorService.EXTRA_STATUS) == TorService.STATUS_ON) service?.let { finish(it) }
            }
        }
        receiver = rx
        LocalBroadcastManager.getInstance(context).registerReceiver(rx, IntentFilter(TorService.ACTION_STATUS))

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val tor = (binder as TorService.LocalBinder).service
                service = tor
                if (waiting.compareAndSet(false, true)) {
                    thread(name = "tor-bootstrap") {
                        try { waitForBootstrap(tor) } finally { waiting.set(false) }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                main.post { this@TorManager.listener?.onError("El servicio de Tor se detuvo. Las conexiones quedan bloqueadas.") }
            }
        }
        connection = conn
        context.bindService(Intent(context, TorService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private fun finish(tor: TorService) {
        if (ready) return
        val socks = tor.socksPort
        if (socks <= 0) return
        ready = true
        val http = tor.httpTunnelPort
        main.post { listener?.onReady(socks, http) }
    }

    /**
     * Muestra el progreso. Ojo: no se consulta a Tor hasta que TorService lleva unos
     * segundos con su conexión de control abierta; si se le pregunta antes de que se
     * autentique, Tor cierra la conexión de control y se detiene.
     */
    private fun waitForBootstrap(tor: TorService) {
        val start = System.currentTimeMillis()
        var controlSince = 0L
        var lastProgressAt = start
        var last = -1
        while (!ready) {
            val now = System.currentTimeMillis()
            if (now - start > BOOTSTRAP_MAX_MS || now - lastProgressAt > STALL_TIMEOUT_MS) {
                main.post { listener?.onError("Tor no avanza al conectarse. Comprueba tu conexión e inténtalo de nuevo.") }
                return
            }
            if (tor.torControlConnection == null) {
                controlSince = 0L
                if (last < 0) main.post { listener?.onProgress(0, "Iniciando Tor…") }
            } else {
                if (controlSince == 0L) controlSince = now
                if (now - controlSince >= CONTROL_SETTLE_MS) {
                    val phase = try { tor.getInfo("status/bootstrap-phase") } catch (_: Exception) { null }
                    if (phase != null) {
                        val pct = PROGRESS.find(phase)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val summary = SUMMARY.find(phase)?.groupValues?.get(1) ?: ""
                        if (pct != last) {
                            last = pct
                            lastProgressAt = now
                            main.post { listener?.onProgress(pct, summary) }
                        }
                        if (pct >= 100) finish(tor)
                    }
                }
            }
            Thread.sleep(1000)
        }
    }

    /** Pide a Tor circuitos nuevos (otra IP de salida). */
    fun newIdentity(): Boolean = try {
        service?.torControlConnection?.signal("NEWNYM")
        true
    } catch (_: Exception) {
        false
    }

    fun stop() {
        receiver?.let { runCatching { LocalBroadcastManager.getInstance(context).unregisterReceiver(it) } }
        receiver = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
        context.stopService(Intent(context, TorService::class.java))
    }

    companion object {
        /** Un solo hilo de espera por proceso, aunque la actividad se recree. */
        private val waiting = AtomicBoolean(false)
        private const val CONTROL_SETTLE_MS = 4_000L
        private const val STALL_TIMEOUT_MS = 240_000L
        private const val BOOTSTRAP_MAX_MS = 900_000L
        private val PROGRESS = Regex("PROGRESS=(\\d+)")
        private val SUMMARY = Regex("SUMMARY=\"([^\"]*)\"")
    }
}
