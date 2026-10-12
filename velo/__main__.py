"""Punto de entrada de Velo.

El orden importa: los parámetros de Chromium (proxy de Tor, política WebRTC,
etc.) se fijan al crear QApplication, ANTES de que se inicialice QtWebEngine.
"""

import argparse
import os
import signal
import sys
from pathlib import Path

from . import APP_NAME, __version__


def _xdg(var, default):
    return Path(os.environ.get(var) or Path.home() / default) / "velo"


CONFIG_DIR = _xdg("XDG_CONFIG_HOME", ".config")
DATA_DIR = _xdg("XDG_DATA_HOME", ".local/share")


def chromium_flags(socks_port):
    flags = [
        # Sin tráfico "de fondo" hacia Google ni otros servicios.
        "--disable-background-networking",
        "--disable-component-update",
        "--disable-domain-reliability",
        "--disable-breakpad",
        "--disable-sync",
        "--no-pings",
        "--no-default-browser-check",
        "--disable-features=" + ",".join([
            "AutofillServerCommunication", "MediaRouter", "OptimizationHints",
            "Translate", "InterestFeedContentSuggestions", "PrivacySandboxSettings4",
            "BrowsingTopics", "FledgeInterestGroups", "AttributionReportingCrossAppWeb",
            "NetworkTimeServiceQuerying", "SpareRendererForSitePerProcess",
        ]),
        # Aislamiento estricto de sitios (mitiga Spectre y fugas entre pestañas).
        "--site-per-process",
        # WebRTC no puede usar UDP fuera del proxy: sin fugas de IP real ni local.
        "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
        "--webrtc-ip-handling-policy=disable_non_proxied_udp",
        "--lang=en-US",
    ]
    if socks_port:
        flags += [
            f"--proxy-server=socks5://127.0.0.1:{socks_port}",
            # Ninguna resolución DNS local: los nombres se resuelven dentro de Tor.
            "--host-resolver-rules=MAP * ~NOTFOUND , EXCLUDE 127.0.0.1",
            # Incluso localhost pasa por el proxy (impide escanear tu red local).
            "--proxy-bypass-list=<-loopback>",
        ]
    return flags


def main():
    parser = argparse.ArgumentParser(prog="velo", description=f"{APP_NAME}: navegador privado y anónimo")
    parser.add_argument("url", nargs="?", help="dirección a abrir")
    parser.add_argument("--sin-tor", "--no-tor", dest="no_tor", action="store_true",
                        help="no usar Tor (los sitios verán tu IP real)")
    parser.add_argument("--sin-js", "--no-js", dest="no_js", action="store_true",
                        help="arrancar con JavaScript desactivado (modo más seguro)")
    parser.add_argument("--version", action="version", version=f"{APP_NAME} {__version__}")
    args = parser.parse_args()

    from .tor import free_port

    socks_port = None if args.no_tor else free_port()
    control_port = None if args.no_tor else free_port()

    # Zona horaria UTC para todos: oculta tu ubicación aproximada.
    os.environ["TZ"] = "UTC"
    # Los parámetros de Chromium se pasan como argumentos de QApplication (sin
    # problemas de comillas); se descarta cualquier variable que pudiera debilitarlos.
    for leaky in ("QTWEBENGINE_CHROMIUM_FLAGS", "QTWEBENGINE_REMOTE_DEBUGGING", "QTWEBENGINE_DISABLE_SANDBOX"):
        os.environ.pop(leaky, None)

    from PyQt6.QtCore import Qt, QTimer, QUrl
    from PyQt6.QtGui import QIcon
    from PyQt6.QtNetwork import QNetworkProxy
    from PyQt6.QtWebEngineWidgets import QWebEngineView  # noqa: F401  (debe importarse antes de QApplication)
    from PyQt6.QtWidgets import QApplication, QMessageBox, QProgressDialog

    from .blocklist import Blocklist
    from .browser import DATA, HOME_URL, MainWindow, register_scheme
    from .interceptor import PrivacyInterceptor
    from .tor import TorProcess

    register_scheme()
    # Qt 6 solo reenvía a Chromium los argumentos que siguen a --webEngineArgs.
    app = QApplication([sys.argv[0] or "velo", "--webEngineArgs", *chromium_flags(socks_port)])
    if socks_port:
        # Segunda barrera: proxy de aplicación de Qt apuntando también a Tor.
        QNetworkProxy.setApplicationProxy(
            QNetworkProxy(QNetworkProxy.ProxyType.Socks5Proxy, "127.0.0.1", socks_port))
    app.setApplicationName(APP_NAME)
    app.setDesktopFileName("velo")
    # Ctrl+C / SIGTERM: salida ordenada (cierra pestañas y detiene Tor).
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: app.quit())
    heartbeat = QTimer(interval=300)  # permite a Python atender señales
    heartbeat.timeout.connect(lambda: None)
    heartbeat.start()
    app.setWindowIcon(QIcon(str(DATA / "velo.svg")))

    blocklist = Blocklist([DATA / "blocklist.txt", DATA_DIR / "blocklist-extra.txt", CONFIG_DIR / "blocklist.txt"])
    interceptor = PrivacyInterceptor(blocklist)
    start_url = QUrl.fromUserInput(args.url) if args.url else HOME_URL
    state = {}

    def open_browser(tor):
        window = MainWindow(interceptor, tor, js_enabled=not args.no_js)
        window.add_tab(start_url)
        window.show()
        app.aboutToQuit.connect(window._destroy_session)
        state["window"] = window

    if args.no_tor:
        open_browser(None)
        return app.exec()

    tor = TorProcess(socks_port, control_port, DATA_DIR / "tor", CONFIG_DIR / "torrc-extra")
    dialog = QProgressDialog("Conectando a la red Tor…", "Salir", 0, 100)
    dialog.setWindowTitle(APP_NAME)
    dialog.setWindowModality(Qt.WindowModality.ApplicationModal)
    dialog.setMinimumDuration(0)
    dialog.setMinimumWidth(420)
    dialog.canceled.connect(app.quit)
    tor.progress.connect(lambda pct, msg: (dialog.setValue(pct), dialog.setLabelText(f"Conectando a Tor… {pct}%\n{msg}")))

    def on_ready():
        dialog.canceled.disconnect()
        dialog.close()
        open_browser(tor)

    def on_failed(message):
        if "window" in state:
            QMessageBox.critical(state["window"], APP_NAME, message)
            return
        dialog.hide()
        box = QMessageBox(QMessageBox.Icon.Critical, APP_NAME,
                          message + "\n\nVelo no abre ninguna página sin Tor para no exponer tu IP.\n"
                          "Si quieres navegar sin Tor, ejecuta:  velo --sin-tor")
        retry = box.addButton("Reintentar", QMessageBox.ButtonRole.AcceptRole)
        box.addButton("Salir", QMessageBox.ButtonRole.RejectRole)
        box.exec()
        if box.clickedButton() is retry:
            dialog.setValue(0)
            dialog.show()
            tor.start()
        else:
            app.quit()

    tor.ready.connect(on_ready)
    tor.failed.connect(on_failed)
    app.aboutToQuit.connect(tor.stop)
    dialog.show()
    tor.start()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
