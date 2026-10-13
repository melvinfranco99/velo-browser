package com.melvin.velo

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.LocaleList
import android.os.Process
import android.provider.MediaStore
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.melvin.velo.databinding.ActivityMainBinding
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TorManager.Listener {

    private companion object {
        const val ASSETS = "resource://android/assets"
        const val START_URL = "$ASSETS/start.html"
    }

    private lateinit var ui: ActivityMainBinding
    private lateinit var tor: TorManager
    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null
    private var torReady = false
    private var jsEnabled = true
    private var httpsOnly = true
    private var canGoBack = false
    private var pendingUrl: String? = null
    private var blockedTotal = 0
    private var blockedBase = 0
    private var blockedDomains = 0
    private var pendingFilePrompt: Pair<PromptDelegate.FilePrompt, GeckoResult<PromptDelegate.PromptResponse>>? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val (prompt, result) = pendingFilePrompt ?: return@registerForActivityResult
        pendingFilePrompt = null
        result.complete(
            when {
                uris.isEmpty() -> prompt.dismiss()
                prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE -> prompt.confirm(this, uris.toTypedArray())
                else -> prompt.confirm(this, uris[0])
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        LocaleList.setDefault(LocaleList(Locale.US))
        super.onCreate(savedInstanceState)
        // Sin capturas de pantalla ni miniatura en "apps recientes" (solo en la versión publicada).
        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        pendingUrl = intent?.dataString
        tor = TorManager(applicationContext)
        setupToolbar()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canGoBack) session?.goBack() else finish()
            }
        })
        ui.retryButton.setOnClickListener { startTor() }
        ui.exitButton.setOnClickListener { finish() }
        startTor()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.dataString ?: return
        if (torReady) navigate(url) else pendingUrl = url
    }

    // ---------------- Tor ----------------

    private fun startTor() {
        ui.splash.visibility = View.VISIBLE
        ui.splashButtons.visibility = View.GONE
        ui.retryButton.visibility = View.VISIBLE
        ui.splashProgress.progress = 0
        ui.splashStatus.text = getString(R.string.connecting)
        tor.start(this)
    }

    override fun onProgress(percent: Int, summary: String) {
        ui.splashProgress.progress = percent
        ui.splashStatus.text = "Conectando a Tor… $percent%\n$summary"
    }

    override fun onReady(socksPort: Int, httpTunnelPort: Int) {
        if (torReady) return
        if (GeckoEngine.runtime != null && GeckoEngine.socksPort != socksPort) {
            // El motor ya se creó con otro puerto (Tor se reinició): hay que reiniciar Velo.
            showFatal("Tor se ha reiniciado. Cierra y vuelve a abrir Velo.")
            return
        }
        // El proxy de Tor se fija al crear el motor: no hay forma de cargar nada sin Tor.
        val rt = GeckoEngine.start(this, socksPort)
        runtime = rt
        torReady = true
        ui.splash.visibility = View.GONE
        installShield(rt)
        openSession()
        val url = pendingUrl
        pendingUrl = null
        if (url != null) navigate(url) else loadStartPage()
    }

    override fun onError(message: String) {
        if (torReady) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            ui.torBadge.text = "⚠"
            return
        }
        ui.splash.visibility = View.VISIBLE
        ui.splashStatus.text = "$message\n\nVelo no abre ninguna página sin Tor para no exponer tu IP."
        ui.splashButtons.visibility = View.VISIBLE
        tor.stop()
    }

    private fun showFatal(message: String) {
        ui.splash.visibility = View.VISIBLE
        ui.splashStatus.text = message
        ui.splashButtons.visibility = View.VISIBLE
        ui.retryButton.visibility = View.GONE
    }

    // ---------------- Bloqueador (extensión integrada) ----------------

    private fun installShield(rt: GeckoRuntime) {
        val controller = rt.webExtensionController
        controller.ensureBuiltIn("resource://android/assets/velo-shield/", "shield@velo.melvin")
            .then { ext -> if (ext != null) controller.setAllowedInPrivateBrowsing(ext, true) else GeckoResult.fromValue(null) }
            .accept({ ext -> ext?.setMessageDelegate(shieldMessages, "velo") },
                { err -> Toast.makeText(this, "No se pudo activar el bloqueador: ${err?.message}", Toast.LENGTH_LONG).show() })
    }

    private val shieldMessages = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            val json = message as? JSONObject ?: return null
            runOnUiThread {
                if (json.has("blocked")) blockedTotal = json.getInt("blocked")
                if (json.has("domains")) blockedDomains = json.getInt("domains")
                updateStatusLine()
            }
            return null
        }
    }

    // ---------------- Sesión Gecko (navegación privada en RAM) ----------------

    private fun openSession() {
        val rt = runtime ?: return
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(true)          // cookies, caché e historial solo en memoria
            .useTrackingProtection(false)  // el bloqueo lo hace Velo Shield, sin listas de Mozilla
            .allowJavascript(jsEnabled)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()
        val s = GeckoSession(settings)
        s.navigationDelegate = navigation
        s.progressDelegate = progress
        s.contentDelegate = content
        s.permissionDelegate = permissions
        s.promptDelegate = prompts
        s.selectionActionDelegate = BasicSelectionActionDelegate(this)
        s.open(rt)
        ui.geckoView.setSession(s)
        session = s
        canGoBack = false
        updateStatusLine()
    }

    private fun closeSession() {
        val s = session ?: return
        session = null
        ui.geckoView.releaseSession()
        s.close()
    }

    private fun loadStartPage() {
        // Gecko no muestra documentos data: abiertos así; las páginas propias van en assets.
        session?.loadUri(START_URL)
        ui.urlBar.setText("")
    }

    private fun navigate(input: String) {
        session?.loadUri(UrlInput.toUrl(input))
        ui.geckoView.requestFocus()
    }

    private val navigation = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(session: GeckoSession, url: String?,
                                      perms: MutableList<PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
            showUrl(url)
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@MainActivity.canGoBack = canGoBack
        }

        override fun onLoadRequest(session: GeckoSession,
                                   request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
            val scheme = Uri.parse(request.uri).scheme?.lowercase(Locale.ROOT)
            return when (scheme) {
                "http", "https", "about", "data", "blob", "resource", "moz-extension", null -> GeckoResult.allow()
                else -> {
                    // intent:, market:, tel:, mailto:… abrirían otra app, posiblemente fuera de Tor.
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Bloqueado: la web intentó abrir otra aplicación ($scheme:)",
                            Toast.LENGTH_SHORT).show()
                    }
                    GeckoResult.deny()
                }
            }
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            // Sin ventanas nuevas: los enlaces target=_blank se abren en la misma pestaña.
            session.loadUri(uri)
            return null
        }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String> {
            // La dirección que falló ya se ve en la barra; cada tipo de error tiene su página.
            val page = when (error.code) {
                WebRequestError.ERROR_PROXY_CONNECTION_REFUSED -> "error-proxy"
                WebRequestError.ERROR_UNKNOWN_HOST -> "error-host"
                WebRequestError.ERROR_NET_TIMEOUT -> "error-timeout"
                WebRequestError.ERROR_CONNECTION_REFUSED -> "error-refused"
                WebRequestError.ERROR_HTTPS_ONLY -> "error-https"
                else -> if (error.category == WebRequestError.ERROR_CATEGORY_SECURITY) "error-cert" else "error"
            }
            return GeckoResult.fromValue("$ASSETS/$page.html")
        }
    }

    private val progress = object : GeckoSession.ProgressDelegate {
        override fun onProgressChange(session: GeckoSession, progress: Int) {
            ui.pageProgress.progress = progress
            ui.pageProgress.visibility = if (progress in 1..99) View.VISIBLE else View.INVISIBLE
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            ui.pageProgress.visibility = View.INVISIBLE
            updateStatusLine()
        }
    }

    private val content = object : GeckoSession.ContentDelegate {
        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            ui.toolbar.visibility = if (fullScreen) View.GONE else View.VISIBLE
            ui.statusLine.visibility = if (fullScreen) View.GONE else View.VISIBLE
            if (fullScreen) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            confirmDownload(response)
        }

        override fun onCrash(session: GeckoSession) {
            Toast.makeText(this@MainActivity, "El motor web se reinició", Toast.LENGTH_SHORT).show()
            closeSession()
            openSession()
            loadStartPage()
        }

        override fun onKill(session: GeckoSession) = onCrash(session)
    }

    /** Cámara, micrófono, ubicación, notificaciones, DRM…: siempre denegados. */
    private val permissions = object : PermissionDelegate {
        override fun onContentPermissionRequest(session: GeckoSession,
                                                perm: PermissionDelegate.ContentPermission): GeckoResult<Int> =
            GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_DENY)

        override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<out String>?,
                                                 callback: PermissionDelegate.Callback) = callback.reject()

        override fun onMediaPermissionRequest(session: GeckoSession, uri: String,
                                              video: Array<out PermissionDelegate.MediaSource>?,
                                              audio: Array<out PermissionDelegate.MediaSource>?,
                                              callback: PermissionDelegate.MediaCallback) = callback.reject()
    }

    // ---------------- Diálogos de las páginas (alert, confirm, select, archivos…) ----------------

    private val prompts = object : PromptDelegate {
        private fun dialog(title: String?, message: String?) = AlertDialog.Builder(this@MainActivity).apply {
            if (!title.isNullOrBlank()) setTitle(title)
            if (!message.isNullOrBlank()) setMessage(message)
        }

        override fun onAlertPrompt(session: GeckoSession,
                                   prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptDelegate.PromptResponse> {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            dialog(prompt.title, prompt.message)
                .setPositiveButton("OK", null)
                .setOnDismissListener { result.complete(prompt.dismiss()) }
                .show()
            return result
        }

        override fun onButtonPrompt(session: GeckoSession,
                                    prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptDelegate.PromptResponse> {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            dialog(prompt.title, prompt.message)
                .setPositiveButton("Aceptar") { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
                .setNegativeButton("Cancelar") { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
            return result
        }

        override fun onTextPrompt(session: GeckoSession,
                                  prompt: PromptDelegate.TextPrompt): GeckoResult<PromptDelegate.PromptResponse> {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            val input = EditText(this@MainActivity).apply {
                setText(prompt.defaultValue ?: "")
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            dialog(prompt.title, prompt.message)
                .setView(input)
                .setPositiveButton("Aceptar") { _, _ -> result.complete(prompt.confirm(input.text.toString())) }
                .setNegativeButton("Cancelar") { _, _ -> result.complete(prompt.dismiss()) }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
            return result
        }

        override fun onChoicePrompt(session: GeckoSession,
                                    prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptDelegate.PromptResponse> {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            // Aplana los grupos (<optgroup>) y descarta separadores y opciones desactivadas.
            val choices = mutableListOf<PromptDelegate.ChoicePrompt.Choice>()
            fun collect(items: Array<out PromptDelegate.ChoicePrompt.Choice>) {
                for (c in items) {
                    val sub = c.items
                    if (sub != null) collect(sub) else if (!c.separator && !c.disabled) choices.add(c)
                }
            }
            collect(prompt.choices)
            val labels = choices.map { it.label }.toTypedArray()
            val builder = dialog(prompt.title, null).setOnCancelListener { result.complete(prompt.dismiss()) }
            if (prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
                val checked = choices.map { it.selected }.toBooleanArray()
                builder.setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
                    .setPositiveButton("Aceptar") { _, _ ->
                        result.complete(prompt.confirm(choices.filterIndexed { i, _ -> checked[i] }.toTypedArray()))
                    }
                    .setNegativeButton("Cancelar") { _, _ -> result.complete(prompt.dismiss()) }
            } else {
                builder.setSingleChoiceItems(labels, choices.indexOfFirst { it.selected }) { d, i ->
                    result.complete(prompt.confirm(choices[i]))
                    d.dismiss()
                }
            }
            builder.show()
            return result
        }

        override fun onFilePrompt(session: GeckoSession,
                                  prompt: PromptDelegate.FilePrompt): GeckoResult<PromptDelegate.PromptResponse> {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            pendingFilePrompt?.let { (p, r) -> r.complete(p.dismiss()) }
            pendingFilePrompt = prompt to result
            val types = prompt.mimeTypes?.filter { it.contains('/') }?.toTypedArray()
            pickFiles.launch(if (types.isNullOrEmpty()) arrayOf("*/*") else types)
            return result
        }

        override fun onPopupPrompt(session: GeckoSession,
                                   prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptDelegate.PromptResponse> =
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))

        override fun onBeforeUnloadPrompt(session: GeckoSession,
                                          prompt: PromptDelegate.BeforeUnloadPrompt): GeckoResult<PromptDelegate.PromptResponse> =
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))

        override fun onRepostConfirmPrompt(session: GeckoSession,
                                           prompt: PromptDelegate.RepostConfirmPrompt): GeckoResult<PromptDelegate.PromptResponse> =
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
    }

    // ---------------- Interfaz ----------------

    private fun setupToolbar() {
        ui.urlBar.setOnEditorActionListener { v, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go && torReady) {
                navigate(v.text.toString())
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
            }
            go
        }
        ui.reloadButton.setOnClickListener { session?.reload() }
        ui.torBadge.setOnClickListener {
            Toast.makeText(this, "Todo el tráfico sale por Tor", Toast.LENGTH_SHORT).show()
        }
        ui.menuButton.setOnClickListener { showMenu(it) }
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "🕶  Nueva identidad")
        menu.menu.add(0, 2, 1, "JavaScript activado").apply { isCheckable = true; isChecked = jsEnabled }
        menu.menu.add(0, 3, 2, "Solo HTTPS").apply { isCheckable = true; isChecked = httpsOnly }
        menu.menu.add(0, 4, 3, "Inicio")
        menu.menu.add(0, 5, 4, "Comprobar conexión Tor")
        menu.menu.add(0, 6, 5, "Acerca de Velo")
        menu.setOnMenuItemClickListener { item ->
            if (!torReady && item.itemId != 6) return@setOnMenuItemClickListener true
            when (item.itemId) {
                1 -> confirmNewIdentity()
                2 -> {
                    jsEnabled = !jsEnabled
                    session?.settings?.allowJavascript = jsEnabled
                    session?.reload()
                    updateStatusLine()
                    Toast.makeText(this, if (jsEnabled) "JavaScript activado" else
                        "JavaScript desactivado (modo más seguro)", Toast.LENGTH_SHORT).show()
                }
                3 -> {
                    httpsOnly = !httpsOnly
                    runtime?.settings?.allowInsecureConnections =
                        if (httpsOnly) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL
                }
                4 -> loadStartPage()
                5 -> navigate("https://check.torproject.org/")
                6 -> showAbout()
            }
            true
        }
        menu.show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("Velo ${BuildConfig.VERSION_NAME}")
            .setMessage("Navegador enfocado en privacidad y anonimato.\n\n" +
                "• Motor: GeckoView ${org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION} (Firefox)\n" +
                "• Tor integrado (${org.torproject.jni.TorService.VERSION_NAME})\n" +
                "• Navegación privada: cookies, caché e historial solo en RAM\n" +
                "• Antihuella de Tor Browser (resistFingerprinting)\n" +
                "• $blockedDomains dominios de rastreo bloqueados")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showUrl(url: String?) {
        if (ui.urlBar.hasFocus()) return
        val internal = url == null || url.startsWith("about:") || url.startsWith("data:") || url.startsWith(ASSETS)
        ui.urlBar.setText(if (internal) "" else url)
    }

    private fun updateStatusLine() {
        ui.statusLine.text = "🛡 ${blockedTotal - blockedBase} rastreadores bloqueados · " +
            (if (jsEnabled) "JS activado" else "JS desactivado") + " · 🧅 Tor · solo RAM"
    }

    // ---------------- Nueva identidad ----------------

    private fun confirmNewIdentity() {
        AlertDialog.Builder(this)
            .setTitle("Nueva identidad")
            .setMessage("Se borrarán cookies, caché, historial y datos de sesión, y Tor usará circuitos nuevos (otra IP de salida).")
            .setPositiveButton("Continuar") { _, _ -> newIdentity() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun newIdentity() {
        if (!tor.newIdentity()) Toast.makeText(this, "No se pudieron renovar los circuitos de Tor", Toast.LENGTH_LONG).show()
        // Al cerrar la única sesión privada, Gecko descarta todos sus datos en memoria.
        closeSession()
        runtime?.storageController?.clearData(StorageController.ClearFlags.ALL)
        blockedBase = blockedTotal
        openSession()
        loadStartPage()
        Toast.makeText(this, "Nueva identidad lista", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            closeSession()
            tor.stop()
            // Se termina el proceso: la memoria con los datos de navegación se libera
            // y en el próximo arranque el motor se crea de cero con el nuevo puerto de Tor.
            Process.killProcess(Process.myPid())
        }
    }

    // ---------------- Descargas (ya llegan por Tor a través de Gecko) ----------------

    private fun confirmDownload(response: WebResponse) {
        val body = response.body
        if (body == null) {
            Toast.makeText(this, "La descarga está vacía", Toast.LENGTH_SHORT).show()
            return
        }
        val name = fileName(response)
        val mime = response.headers["Content-Type"]?.substringBefore(';')?.trim() ?: "application/octet-stream"
        AlertDialog.Builder(this)
            .setTitle("Descargar archivo")
            .setMessage("$name\n\nSe guardará en la carpeta Descargas.\n" +
                "Ojo: al abrir algunos documentos, otras apps podrían conectarse a Internet fuera de Tor.")
            .setPositiveButton("Descargar") { _, _ -> save(body, name, mime) }
            .setNegativeButton("Cancelar") { _, _ -> runCatching { body.close() } }
            .setOnCancelListener { runCatching { body.close() } }
            .show()
    }

    private fun fileName(response: WebResponse): String {
        val disposition = response.headers["Content-Disposition"] ?: ""
        val fromHeader = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.get(1)?.let { Uri.decode(it) }
        val fromUrl = Uri.parse(response.uri).lastPathSegment
        return (fromHeader ?: fromUrl ?: "descarga").replace(Regex("[/\\\\:*?\"<>|]"), "_").take(120)
    }

    private fun save(body: java.io.InputStream, name: String, mime: String) {
        Toast.makeText(this, "Descargando $name…", Toast.LENGTH_SHORT).show()
        thread(name = "download") {
            val result = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("no se pudo crear el archivo")
                try {
                    body.use { input -> contentResolver.openOutputStream(uri)!!.use { input.copyTo(it) } }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
            }
            runOnUiThread {
                Toast.makeText(this,
                    result.fold({ "Descargado en Descargas: $name" }, { "Error al descargar: ${it.message}" }),
                    Toast.LENGTH_LONG).show()
            }
        }
    }
}
