package com.melvin.velo

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.LocaleList
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.webkit.ProfileStore
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.melvin.velo.databinding.ActivityMainBinding
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TorManager.Listener {

    private companion object {
        /** Versión de Chromium a partir de la que WebView deja de enviar X-Requested-With. */
        const val MIN_SAFE_WEBVIEW = 120
    }

    private lateinit var ui: ActivityMainBinding
    private lateinit var tor: TorManager
    private var webView: WebView? = null
    private var blocklist: Blocklist? = null
    private var profileName: String? = null
    private var httpTunnelPort = -1
    private var torReady = false
    private var jsEnabled = true
    private var httpsOnly = true
    private var pendingUrl: String? = null
    private var outdatedWebView = false
    private var warnedOutdated = false
    private val blockedCount = AtomicInteger(0)
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var userAgent: String
    private lateinit var chromeMajor: String

    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Idioma neutro para la web (Accept-Language): no revela que hablas español.
        LocaleList.setDefault(LocaleList(Locale.US))
        super.onCreate(savedInstanceState)
        // Sin capturas de pantalla ni miniatura en "apps recientes" (solo en la versión
        // publicada; la de depuración las permite para poder probarla).
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
                when {
                    customView != null -> customViewCallback?.onCustomViewHidden()
                    webView?.canGoBack() == true -> webView?.goBack()
                    else -> finish()
                }
            }
        })
        ui.retryButton.setOnClickListener { startTor() }
        ui.exitButton.setOnClickListener { finish() }

        wipeLeftovers()
        thread(name = "blocklist") {
            val list = Blocklist.load(applicationContext)
            runOnUiThread { blocklist = list }
        }
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
        this.httpTunnelPort = httpTunnelPort
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            showFatal("Tu versión de «Android System WebView» no permite forzar el proxy de Tor.\n" +
                "Actualízala desde la tienda de aplicaciones. Velo no navegará sin Tor.")
            return
        }
        // Todo el tráfico del WebView por Tor. Sin regla DIRECT: si Tor cae, nada sale.
        // removeImplicitRules(): incluso localhost pasa por Tor (impide escanear tu red).
        val config = ProxyConfig.Builder()
            .addProxyRule("socks5://127.0.0.1:$socksPort")
            .removeImplicitRules()
            .build()
        ProxyController.getInstance().setProxyOverride(config, ContextCompat.getMainExecutor(this)) {
            torReady = true
            ui.splash.visibility = View.GONE
            createWebView()
            val url = pendingUrl
            pendingUrl = null
            if (url != null) navigate(url) else loadStartPage()
        }
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

    // ---------------- WebView ----------------

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun createWebView() {
        val wv = WebView(this)

        // Perfil efímero propio: se borra entero con "Nueva identidad" y al salir.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val name = "s" + SecureRandom().nextInt(Int.MAX_VALUE)
            ProfileStore.getInstance().getOrCreateProfile(name)
            WebViewCompat.setProfile(wv, name)
            profileName = name
        }

        val defaultUa = wv.settings.userAgentString
        chromeMajor = Regex("Chrome/(\\d+)").find(defaultUa)?.groupValues?.get(1) ?: "140"
        // User-Agent reducido de Chrome: sin modelo de móvil, versión de Android ni la marca "wv".
        userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$chromeMajor.0.0.0 Mobile Safari/537.36"

        wv.settings.apply {
            userAgentString = userAgent
            javaScriptEnabled = jsEnabled
            domStorageEnabled = true
            databaseEnabled = false
            setGeolocationEnabled(false)
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            saveFormData = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(wv.settings, false)
        }
        // Sin cabecera X-Requested-With (revelaría el nombre del paquete de la app).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(wv.settings, emptySet())
        }
        // Comprobado en un emulador: WebView 113 dice admitir lo anterior pero sigue enviando
        // la cabecera. Por eso se avisa según la versión real del motor.
        outdatedWebView = (chromeMajor.toIntOrNull() ?: 0) < MIN_SAFE_WEBVIEW ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)
        // Client Hints coherentes con el User-Agent (sin "Android WebView" ni modelo).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            fun brand(name: String, v: String) = UserAgentMetadata.BrandVersion.Builder()
                .setBrand(name).setMajorVersion(v).setFullVersion("$v.0.0.0").build()
            WebSettingsCompat.setUserAgentMetadata(wv.settings, UserAgentMetadata.Builder()
                .setBrandVersionList(listOf(brand("Chromium", chromeMajor), brand("Google Chrome", chromeMajor),
                    brand("Not=A?Brand", "24")))
                .setFullVersion("$chromeMajor.0.0.0")
                .setPlatform("Android").setPlatformVersion("10.0.0")
                .setModel("").setMobile(true).setArchitecture("").setBitness(0).setWow64(false)
                .build())
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ATTRIBUTION_REGISTRATION_BEHAVIOR)) {
            WebSettingsCompat.setAttributionRegistrationBehavior(
                wv.settings, WebSettingsCompat.ATTRIBUTION_BEHAVIOR_DISABLED)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, hardeningScript(), setOf("*"))
        }

        wv.webViewClient = PrivacyClient()
        wv.webChromeClient = PrivacyChromeClient()
        wv.setDownloadListener { url, ua, disposition, mime, _ -> download(url, ua, disposition, mime) }

        ui.webContainer.addView(wv)
        webView = wv
        updateStatusLine()
        if (outdatedWebView && !warnedOutdated) {
            warnedOutdated = true
            AlertDialog.Builder(this)
                .setTitle("Actualiza Android System WebView")
                .setMessage("Tu versión del motor web (Chromium $chromeMajor) es antigua y envía a las webs la " +
                    "cabecera «X-Requested-With», que revela que usas Velo. Tu IP sigue oculta por Tor, pero " +
                    "para el máximo anonimato actualiza «Android System WebView» (o Chrome) desde la tienda.")
                .setPositiveButton("Entendido", null)
                .show()
        }
    }

    private fun hardeningScript(): String {
        val seed = SecureRandom().nextInt(Int.MAX_VALUE)
        return assets.open("hardening.js").bufferedReader().readText().replace("__VELO_SEED__", seed.toString())
    }

    private fun loadStartPage() {
        val count = blocklist?.size ?: 0
        val html = assets.open("start.html").bufferedReader().readText()
            .replace("{{STATUS_CLASS}}", "tor")
            .replace("{{STATUS_TEXT}}", "Conectado a través de Tor")
            .replace("{{BLOCKED_DOMAINS}}", String.format(Locale.GERMANY, "%,d", count))
        webView?.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        ui.urlBar.setText("")
    }

    private fun navigate(input: String) {
        var url = UrlCleaner.fromUserInput(input)
        if (httpsOnly) UrlCleaner.upgrade(url)?.let { url = it }
        UrlCleaner.strip(url)?.let { url = it }
        webView?.loadUrl(url)
        webView?.requestFocus()
    }

    private inner class PrivacyClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!request.isForMainFrame && blocklist?.isBlocked(request.url.host) == true) {
                blockedCount.incrementAndGet()
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(),
                    ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            when (request.url.scheme) {
                "http", "https" -> {
                    val upgraded = if (httpsOnly) UrlCleaner.upgrade(url) else null
                    val target = UrlCleaner.strip(upgraded ?: url) ?: upgraded ?: return false
                    view.loadUrl(target)
                    return true
                }
                "about", "data", "blob", "javascript" -> return false
                else -> {
                    // intent:, market:, tel:, mailto:… abrirían otra app, posiblemente fuera de Tor.
                    Toast.makeText(this@MainActivity,
                        "Bloqueado: la web intentó abrir otra aplicación (${request.url.scheme}:)",
                        Toast.LENGTH_SHORT).show()
                    return true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            showUrl(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            showUrl(url)
            updateStatusLine()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            showUrl(url)
        }
    }

    private inner class PrivacyChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            ui.pageProgress.progress = newProgress
            ui.pageProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            if (newProgress == 100) updateStatusLine()
        }

        // Cámara, micrófono, MIDI, contenido protegido…: siempre denegado.
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                                       params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            val type = params.acceptTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
            pickFiles.launch(if (type.contains('/')) type else "*/*")
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customView = view
            customViewCallback = callback
            ui.fullscreenContainer.addView(view)
            ui.fullscreenContainer.visibility = View.VISIBLE
        }

        override fun onHideCustomView() {
            ui.fullscreenContainer.removeAllViews()
            ui.fullscreenContainer.visibility = View.GONE
            customView = null
            customViewCallback = null
        }
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
        ui.reloadButton.setOnClickListener { webView?.reload() }
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
            when (item.itemId) {
                1 -> confirmNewIdentity()
                2 -> {
                    jsEnabled = !jsEnabled
                    webView?.settings?.javaScriptEnabled = jsEnabled
                    webView?.reload()
                    Toast.makeText(this, if (jsEnabled) "JavaScript activado" else
                        "JavaScript desactivado (modo más seguro)", Toast.LENGTH_SHORT).show()
                }
                3 -> httpsOnly = !httpsOnly
                4 -> if (torReady) loadStartPage()
                5 -> if (torReady) navigate("https://check.torproject.org/")
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
                "• Tor integrado (${org.torproject.jni.TorService.VERSION_NAME})\n" +
                "• Motor: Android System WebView (Chromium ${if (::chromeMajor.isInitialized) chromeMajor else "?"})\n" +
                "• ${blocklist?.size ?: 0} dominios de rastreo bloqueados\n" +
                "• Perfiles efímeros: ${if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) "sí" else "no (se borra todo al salir)"}")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showUrl(url: String?) {
        if (ui.urlBar.hasFocus()) return
        ui.urlBar.setText(if (url == null || url.startsWith("about:") || url.startsWith("data:")) "" else url)
    }

    private fun updateStatusLine() {
        ui.statusLine.text = "🛡 ${blockedCount.get()} rastreadores bloqueados · " +
            (if (jsEnabled) "JS activado" else "JS desactivado") + " · 🧅 Tor" +
            (if (outdatedWebView) " · ⚠ WebView antiguo" else "")
    }

    // ---------------- Nueva identidad y borrado ----------------

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
        destroyWebView()
        blockedCount.set(0)
        createWebView()
        loadStartPage()
        Toast.makeText(this, "Nueva identidad lista", Toast.LENGTH_SHORT).show()
    }

    private fun destroyWebView() {
        val wv = webView ?: return
        webView = null
        ui.webContainer.removeView(wv)
        wv.stopLoading()
        wv.clearHistory()
        wv.clearCache(true)
        wv.clearFormData()
        wv.destroy()
        profileName = null  // el perfil ya no está en uso: se borra también
        wipeData()
    }

    /** Borra todos los datos web: perfiles efímeros y el perfil por defecto. */
    @SuppressLint("RequiresFeature")
    private fun wipeData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val store = ProfileStore.getInstance()
            for (name in store.allProfileNames) {
                if (name == androidx.webkit.Profile.DEFAULT_PROFILE_NAME || name == profileName) continue
                runCatching {
                    store.getProfile(name)?.let { p ->
                        p.cookieManager.removeAllCookies(null)
                        p.webStorage.deleteAllData()
                    }
                    store.deleteProfile(name)
                }
            }
        }
        profileName = null
    }

    /** Si la app se cerró de golpe la última vez, borra lo que quedara. */
    private fun wipeLeftovers() {
        wipeData()
        runCatching { cacheDir.listFiles()?.filter { it.name.contains("WebView", true) }?.forEach { it.deleteRecursively() } }
    }

    override fun onDestroy() {
        destroyWebView()
        if (isFinishing) {
            tor.stop()
            // El proxy queda configurado hacia un puerto muerto: si el proceso
            // sobrevive, ninguna petición sale sin Tor.
        }
        super.onDestroy()
    }

    // ---------------- Descargas por Tor ----------------

    private fun download(url: String, ua: String, disposition: String?, mime: String?) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            Toast.makeText(this, "Este tipo de descarga no está soportado", Toast.LENGTH_SHORT).show()
            return
        }
        val target = UrlCleaner.upgrade(url) ?: url
        if (!target.startsWith("https://")) {
            Toast.makeText(this, "Solo se permiten descargas por HTTPS", Toast.LENGTH_SHORT).show()
            return
        }
        val name = URLUtil.guessFileName(target, disposition, mime)
        AlertDialog.Builder(this)
            .setTitle("Descargar archivo")
            .setMessage("$name\n\nSe descargará a través de Tor en la carpeta Descargas.\n" +
                "Ojo: al abrir algunos documentos, otras apps podrían conectarse a Internet fuera de Tor.")
            .setPositiveButton("Descargar") { _, _ -> downloadViaTor(target, name, mime) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun downloadViaTor(url: String, name: String, mime: String?) {
        if (httpTunnelPort <= 0) {
            Toast.makeText(this, "Tor no está listo para descargas", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Descargando $name…", Toast.LENGTH_SHORT).show()
        thread(name = "download") {
            val result = runCatching {
                // Proxy HTTP CONNECT de Tor: el nombre del host se resuelve dentro de Tor.
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpTunnelPort))
                val conn = URL(url).openConnection(proxy) as HttpURLConnection
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                conn.connectTimeout = 60_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime ?: conn.contentType ?: "application/octet-stream")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("no se pudo crear el archivo")
                try {
                    conn.inputStream.use { input ->
                        contentResolver.openOutputStream(uri)!!.use { output -> input.copyTo(output) }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                } finally {
                    conn.disconnect()
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
