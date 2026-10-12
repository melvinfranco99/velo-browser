"""Ventana principal de Velo."""

import re
import secrets
from pathlib import Path

from PyQt6 import sip
from PyQt6.QtCore import QBuffer, QByteArray, QIODevice, QStandardPaths, Qt, QTimer, QUrl
from PyQt6.QtGui import QAction, QIcon, QKeySequence
from PyQt6.QtWebEngineCore import (
    QWebEngineLoadingInfo, QWebEnginePage, QWebEngineProfile, QWebEngineScript, QWebEngineSettings,
    QWebEngineUrlScheme, QWebEngineUrlSchemeHandler,
)
from PyQt6.QtWebEngineWidgets import QWebEngineView
from PyQt6.QtWidgets import (
    QFileDialog, QInputDialog, QLabel, QLineEdit, QMainWindow, QMessageBox,
    QTabWidget, QToolBar, QToolButton, QMenu,
)

from . import APP_NAME, __version__

DATA = Path(__file__).resolve().parent / "data"
HOME_URL = QUrl("velo:inicio")
SEARCH_URL = "https://duckduckgo.com/?q="
TOR_CHECK_URL = "https://check.torproject.org/"
ACCEPT_LANGUAGE = "en-US,en;q=0.9"
ERR_NETWORK_CHANGED = -21


def register_scheme():
    """Debe llamarse antes de crear QApplication."""
    scheme = QWebEngineUrlScheme(b"velo")
    scheme.setSyntax(QWebEngineUrlScheme.Syntax.Path)
    scheme.setFlags(QWebEngineUrlScheme.Flag.SecureScheme)
    QWebEngineUrlScheme.registerScheme(scheme)


def generic_user_agent():
    """User-Agent de Chrome para Linux sin la marca 'QtWebEngine/x.y.z',
    que de otro modo identificaría a los usuarios de navegadores Qt."""
    ua = QWebEngineProfile.defaultProfile().httpUserAgent()
    m = re.search(r"Chrome/(\d+)", ua)
    major = m.group(1) if m else "140"
    return (f"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
            f"Chrome/{major}.0.0.0 Safari/537.36"), major


class StartPageHandler(QWebEngineUrlSchemeHandler):
    def __init__(self, window):
        super().__init__(window)
        self.window = window

    def requestStarted(self, job):
        if job.requestUrl().path() != "inicio":
            job.fail(job.Error.UrlNotFound)
            return
        tor = self.window.tor is not None
        html = (DATA / "start.html").read_text(encoding="utf-8")
        html = (html.replace("{{STATUS_CLASS}}", "tor" if tor else "notor")
                    .replace("{{STATUS_TEXT}}", "Conectado a través de Tor" if tor
                             else "SIN TOR: los sitios ven tu dirección IP real")
                    .replace("{{BLOCKED_DOMAINS}}", f"{len(self.window.interceptor.blocklist):,}".replace(",", ".")))
        buf = QBuffer(job)
        buf.setData(QByteArray(html.encode("utf-8")))
        buf.open(QIODevice.OpenModeFlag.ReadOnly)
        job.reply(b"text/html", buf)


class PrivatePage(QWebEnginePage):
    def __init__(self, profile, window, parent):
        super().__init__(profile, parent)
        self.window = window
        # Cámara, micrófono, ubicación, notificaciones, portapapeles, etc.: siempre denegados.
        self.permissionRequested.connect(lambda permission: permission.deny())
        self.fullScreenRequested.connect(lambda request: request.accept())

    def createWindow(self, _type):
        return self.window.add_tab(None).page()

    def javaScriptConsoleMessage(self, *_args):
        pass


class MainWindow(QMainWindow):
    def __init__(self, interceptor, tor, js_enabled=True):
        super().__init__()
        self.interceptor = interceptor
        self.tor = tor
        self.js_enabled = js_enabled
        self.user_agent, self.chrome_major = generic_user_agent()
        self.profile = None
        self.scheme_handler = StartPageHandler(self)

        self.setWindowTitle(APP_NAME)
        self.setWindowIcon(QIcon(str(DATA / "velo.svg")))
        # Tamaño estándar: el tamaño de la ventana también forma parte de la huella.
        self.resize(1200, 800)

        self.tabs = QTabWidget(documentMode=True, tabsClosable=True, movable=True)
        self.tabs.tabCloseRequested.connect(self.close_tab)
        self.tabs.currentChanged.connect(self._on_tab_changed)
        self.setCentralWidget(self.tabs)
        new_tab_button = QToolButton(text="+", autoRaise=True)
        new_tab_button.clicked.connect(lambda: self.add_tab(HOME_URL))
        self.tabs.setCornerWidget(new_tab_button, Qt.Corner.TopRightCorner)

        self._build_toolbar()
        self._build_shortcuts()
        self.profile = self._create_profile()

        self.status_timer = QTimer(self, interval=1000)
        self.status_timer.timeout.connect(self._update_status)
        self.status_timer.start()

    # ---------- Perfil efímero ----------
    def _create_profile(self):
        profile = QWebEngineProfile(self)  # sin nombre = "off the record": nada en disco
        profile.setHttpUserAgent(self.user_agent)
        profile.setHttpAcceptLanguage(ACCEPT_LANGUAGE)
        profile.setHttpCacheType(QWebEngineProfile.HttpCacheType.MemoryHttpCache)
        profile.setPersistentCookiesPolicy(QWebEngineProfile.PersistentCookiesPolicy.NoPersistentCookies)
        profile.setPersistentPermissionsPolicy(QWebEngineProfile.PersistentPermissionsPolicy.AskEveryTime)
        profile.setSpellCheckEnabled(False)
        profile.setPushServiceEnabled(False)
        profile.setUrlRequestInterceptor(self.interceptor)
        profile.installUrlSchemeHandler(b"velo", self.scheme_handler)
        profile.downloadRequested.connect(self._on_download)
        # Cookies de terceros bloqueadas (las usan los rastreadores entre sitios).
        profile.cookieStore().setCookieFilter(lambda request: not request.thirdParty)

        hints = profile.clientHints()
        hints.setAllClientHintsEnabled(False)
        hints.setFullVersionList({"Chromium": f"{self.chrome_major}.0.0.0",
                                  "Not=A?Brand": "24.0.0.0"})

        s = profile.settings()
        A = QWebEngineSettings.WebAttribute
        for attr, value in {
            A.JavascriptEnabled: self.js_enabled,
            A.JavascriptCanOpenWindows: False,
            A.JavascriptCanAccessClipboard: False,
            A.JavascriptCanPaste: False,
            A.AllowWindowActivationFromJavaScript: False,
            A.WebGLEnabled: False,                 # WebGL revela tu GPU exacta
            A.WebRTCPublicInterfacesOnly: True,    # evita fugas de IP local por WebRTC
            A.DnsPrefetchEnabled: False,
            A.HyperlinkAuditingEnabled: False,     # sin <a ping>
            A.PluginsEnabled: False,
            A.ScreenCaptureEnabled: False,
            A.LocalContentCanAccessRemoteUrls: False,
            A.LocalContentCanAccessFileUrls: False,
            A.AllowRunningInsecureContent: False,
            A.AllowGeolocationOnInsecureOrigins: False,
            A.PlaybackRequiresUserGesture: True,
            A.NavigateOnDropEnabled: False,
            A.XSSAuditingEnabled: True,
            A.FullScreenSupportEnabled: True,
        }.items():
            s.setAttribute(attr, value)

        script = QWebEngineScript()
        script.setName("velo-hardening")
        script.setInjectionPoint(QWebEngineScript.InjectionPoint.DocumentCreation)
        script.setWorldId(QWebEngineScript.ScriptWorldId.MainWorld)
        script.setRunsOnSubFrames(True)
        source = (DATA / "hardening.js").read_text(encoding="utf-8")
        script.setSourceCode(source.replace("__VELO_SEED__", str(secrets.randbits(31))))
        profile.scripts().insert(script)
        return profile

    # ---------- Interfaz ----------
    def _build_toolbar(self):
        bar = QToolBar(movable=False)
        self.addToolBar(bar)

        def action(text, tip, slot, icon=None):
            act = QAction(QIcon.fromTheme(icon) if icon else QIcon(), text, self, toolTip=tip)
            act.triggered.connect(slot)
            bar.addAction(act)
            return act

        action("◀", "Atrás (Alt+←)", lambda: self.current_view() and self.current_view().back(), "go-previous")
        action("▶", "Adelante (Alt+→)", lambda: self.current_view() and self.current_view().forward(), "go-next")
        action("⟳", "Recargar (F5)", lambda: self.current_view() and self.current_view().reload(), "view-refresh")
        action("⌂", "Inicio", lambda: self.navigate(HOME_URL), "go-home")

        self.url_bar = QLineEdit(placeholderText="Buscar o escribir una dirección")
        self.url_bar.returnPressed.connect(lambda: self.navigate(self.url_bar.text()))
        bar.addWidget(self.url_bar)

        self.security_label = QLabel()
        self.security_label.setContentsMargins(8, 0, 8, 0)
        bar.addWidget(self.security_label)

        self.js_action = action("", "Activar/desactivar JavaScript (Ctrl+Shift+J)", self.toggle_js)
        self.js_action.setCheckable(True)
        self.js_action.setChecked(self.js_enabled)
        self._refresh_js_action()

        action("🕶 Nueva identidad", "Cierra todo, borra la sesión y usa nuevos circuitos de Tor (Ctrl+Shift+U)",
               self.new_identity)

        self.tor_label = QLabel()
        self.tor_label.setContentsMargins(8, 0, 8, 0)
        if self.tor:
            self.tor_label.setText("🧅 Tor")
            self.tor_label.setStyleSheet("color:#2e9e57;font-weight:bold")
            self.tor_label.setToolTip("Todo el tráfico sale por tu instancia privada de Tor")
        else:
            self.tor_label.setText("⚠ SIN TOR")
            self.tor_label.setStyleSheet("color:#d23c4b;font-weight:bold")
            self.tor_label.setToolTip("Modo --sin-tor: los sitios ven tu IP real")
        bar.addWidget(self.tor_label)

        menu = QMenu(self)
        self.https_action = menu.addAction("Solo HTTPS (recomendado)")
        self.https_action.setCheckable(True)
        self.https_action.setChecked(True)
        self.https_action.toggled.connect(lambda on: setattr(self.interceptor, "https_only", on))
        menu.addAction("Comprobar conexión Tor", lambda: self.add_tab(QUrl(TOR_CHECK_URL)))
        menu.addAction("Buscar en la página (Ctrl+F)", self.find_in_page)
        menu.addSeparator()
        menu.addAction(f"Acerca de {APP_NAME}", self.about)
        menu_button = QToolButton(text="☰", popupMode=QToolButton.ToolButtonPopupMode.InstantPopup)
        menu_button.setMenu(menu)
        bar.addWidget(menu_button)

        self.statusBar().setSizeGripEnabled(False)
        self.blocked_label = QLabel()
        self.statusBar().addPermanentWidget(self.blocked_label)

    def _build_shortcuts(self):
        shortcuts = {
            "Ctrl+T": lambda: self.add_tab(HOME_URL),
            "Ctrl+W": lambda: self.close_tab(self.tabs.currentIndex()),
            "Ctrl+L": lambda: (self.url_bar.setFocus(), self.url_bar.selectAll()),
            "F6": lambda: (self.url_bar.setFocus(), self.url_bar.selectAll()),
            "F5": lambda: self.current_view() and self.current_view().reload(),
            "Ctrl+R": lambda: self.current_view() and self.current_view().reload(),
            "Alt+Left": lambda: self.current_view() and self.current_view().back(),
            "Alt+Right": lambda: self.current_view() and self.current_view().forward(),
            "Ctrl+Shift+U": self.new_identity,
            "Ctrl+Shift+J": lambda: self.js_action.trigger(),
            "Ctrl+F": self.find_in_page,
            "Ctrl+Tab": lambda: self.tabs.setCurrentIndex((self.tabs.currentIndex() + 1) % max(1, self.tabs.count())),
            "Ctrl+Shift+Tab": lambda: self.tabs.setCurrentIndex((self.tabs.currentIndex() - 1) % max(1, self.tabs.count())),
            "Ctrl++": lambda: self._zoom(0.1),
            "Ctrl+=": lambda: self._zoom(0.1),
            "Ctrl+-": lambda: self._zoom(-0.1),
            "Ctrl+0": lambda: self.current_view() and self.current_view().setZoomFactor(1.0),
            "F11": lambda: self.showNormal() if self.isFullScreen() else self.showFullScreen(),
        }
        for keys, slot in shortcuts.items():
            act = QAction(self, shortcut=QKeySequence(keys))
            act.triggered.connect(slot)
            self.addAction(act)

    # ---------- Pestañas ----------
    def current_view(self):
        return self.tabs.currentWidget()

    def add_tab(self, url=HOME_URL):
        view = QWebEngineView()
        view.setPage(PrivatePage(self.profile, self, view))
        view.titleChanged.connect(lambda title, v=view: self._set_tab_title(v, title))
        view.iconChanged.connect(lambda icon, v=view: self.tabs.setTabIcon(self.tabs.indexOf(v), icon))
        view.urlChanged.connect(lambda u, v=view: self._on_url_changed(v, u))
        view.page().linkHovered.connect(lambda link: self.statusBar().showMessage(link, 3000))
        view.page().fullScreenRequested.connect(
            lambda req: self.showFullScreen() if req.toggleOn() else self.showNormal())
        view.page().loadingChanged.connect(lambda info, v=view: self._on_loading_changed(v, info))
        index = self.tabs.addTab(view, "Nueva pestaña")
        self.tabs.setCurrentIndex(index)
        if url is not None:
            view.load(url)
            if url == HOME_URL:
                self.url_bar.setFocus()
        return view

    def _on_loading_changed(self, view, info):
        # Chromium aborta a veces con ERR_NETWORK_CHANGED mientras Tor crea
        # circuitos; se reintenta automáticamente hasta dos veces.
        status = info.status()
        if status == QWebEngineLoadingInfo.LoadStatus.LoadSucceededStatus:
            view.setProperty("retries", 0)
        elif (status == QWebEngineLoadingInfo.LoadStatus.LoadFailedStatus
              and info.errorCode() == ERR_NETWORK_CHANGED):
            retries = view.property("retries") or 0
            if retries < 2:
                view.setProperty("retries", retries + 1)
                url = info.url()
                QTimer.singleShot(800, lambda: not sip.isdeleted(view) and view.load(url))

    def close_tab(self, index):
        view = self.tabs.widget(index)
        if view is None:
            return
        self.tabs.removeTab(index)
        sip.delete(view)  # borra también su página
        if self.tabs.count() == 0:
            self.close()

    def _set_tab_title(self, view, title):
        index = self.tabs.indexOf(view)
        if index >= 0:
            self.tabs.setTabText(index, (title[:28] + "…") if len(title) > 29 else (title or "Sin título"))
            self.tabs.setTabToolTip(index, title)
        if view is self.current_view():
            self.setWindowTitle(f"{title} — {APP_NAME}" if title else APP_NAME)

    def _on_url_changed(self, view, url):
        if view is self.current_view():
            self._show_url(url)

    def _on_tab_changed(self, _index):
        view = self.current_view()
        if view:
            self._show_url(view.url())
            title = view.title()
            self.setWindowTitle(f"{title} — {APP_NAME}" if title else APP_NAME)

    def _show_url(self, url):
        self.url_bar.setText("" if url == HOME_URL else url.toDisplayString())
        self.url_bar.setCursorPosition(0)
        if url.scheme() == "https":
            self.security_label.setText("🔒")
            self.security_label.setToolTip("Conexión cifrada (HTTPS)")
        elif url.host().endswith(".onion"):
            self.security_label.setText("🧅")
            self.security_label.setToolTip("Servicio onion: cifrado de extremo a extremo dentro de Tor")
        elif url.scheme() == "http":
            self.security_label.setText("⚠")
            self.security_label.setToolTip("Conexión NO cifrada")
        else:
            self.security_label.setText("")

    def navigate(self, target):
        if isinstance(target, QUrl):
            url = target
        else:
            text = target.strip()
            if not text:
                return
            looks_like_url = (("." in text or text.startswith("localhost")) and " " not in text) or "://" in text
            if looks_like_url:
                url = QUrl.fromUserInput(text)
                if "://" not in text and url.scheme() == "http":
                    url.setScheme("https")
            else:
                url = QUrl(SEARCH_URL + QUrl.toPercentEncoding(text).data().decode())
        view = self.current_view() or self.add_tab(None)
        view.load(url)
        view.setFocus()

    def _zoom(self, delta):
        view = self.current_view()
        if view:
            view.setZoomFactor(max(0.25, min(5.0, view.zoomFactor() + delta)))

    # ---------- Funciones de privacidad ----------
    def _refresh_js_action(self):
        on = self.js_action.isChecked()
        self.js_action.setText("JS ✓" if on else "JS ✗")
        self.js_action.setToolTip(
            ("JavaScript ACTIVADO. " if on else "JavaScript DESACTIVADO (modo más seguro). ")
            + "Pulsa para cambiar (Ctrl+Shift+J)")

    def toggle_js(self):
        self.js_enabled = self.js_action.isChecked()
        self.profile.settings().setAttribute(QWebEngineSettings.WebAttribute.JavascriptEnabled, self.js_enabled)
        self._refresh_js_action()
        view = self.current_view()
        if view:
            view.reload()

    def new_identity(self):
        answer = QMessageBox.question(
            self, "Nueva identidad",
            "Se cerrarán todas las pestañas y se borrarán cookies, caché y datos de sesión.\n"
            "Tor usará circuitos nuevos (otra IP de salida). ¿Continuar?")
        if answer != QMessageBox.StandardButton.Yes:
            return
        if self.tor:
            try:
                self.tor.new_identity()
            except Exception as exc:  # noqa: BLE001
                QMessageBox.warning(self, "Tor", f"No se pudieron renovar los circuitos: {exc}")
        self._destroy_session()
        self.profile = self._create_profile()
        self.add_tab(HOME_URL)
        self.statusBar().showMessage("Nueva identidad lista.", 4000)

    def _destroy_session(self):
        self.tabs.blockSignals(True)
        while self.tabs.count():
            view = self.tabs.widget(0)
            self.tabs.removeTab(0)
            sip.delete(view)
        self.tabs.blockSignals(False)
        if self.profile is not None:
            sip.delete(self.profile)  # las páginas ya no existen: se puede borrar sin fugas
            self.profile = None

    def _on_download(self, download):
        folder = QStandardPaths.writableLocation(QStandardPaths.StandardLocation.DownloadLocation)
        path, _ = QFileDialog.getSaveFileName(self, "Guardar descarga", str(Path(folder) / download.downloadFileName()))
        if not path:
            download.cancel()
            return
        download.setDownloadDirectory(str(Path(path).parent))
        download.setDownloadFileName(Path(path).name)
        download.accept()
        download.isFinishedChanged.connect(
            lambda: self.statusBar().showMessage(f"Descarga completada: {path}", 6000))
        self.statusBar().showMessage(
            "Descargando… Ojo: abrir documentos descargados puede conectarse a Internet fuera de Tor.", 6000)

    def find_in_page(self):
        view = self.current_view()
        if not view:
            return
        text, ok = QInputDialog.getText(self, "Buscar en la página", "Texto:")
        if ok:
            view.findText(text)

    def _update_status(self):
        self.blocked_label.setText(f"🛡 {self.interceptor.blocked_count} rastreadores bloqueados")

    def about(self):
        QMessageBox.about(
            self, f"Acerca de {APP_NAME}",
            f"<b>{APP_NAME} {__version__}</b><br>Navegador enfocado en privacidad y anonimato.<br><br>"
            f"Motor: Chromium {self.chrome_major} (QtWebEngine)<br>"
            f"Tor: {'activo (instancia privada)' if self.tor else 'desactivado'}<br>"
            f"Dominios bloqueados: {len(self.interceptor.blocklist)}")

    def closeEvent(self, event):
        self._destroy_session()
        super().closeEvent(event)
