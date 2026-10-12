"""Interceptor de peticiones: bloqueo de rastreadores, HTTPS estricto,
limpieza de parámetros de seguimiento y cabecera Global Privacy Control."""

from urllib.parse import unquote

from PyQt6.QtCore import QUrl
from PyQt6.QtWebEngineCore import QWebEngineUrlRequestInfo, QWebEngineUrlRequestInterceptor

TRACKING_PARAMS = {
    "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "mc_cid",
    "mc_eid", "yclid", "_openstat", "igshid", "igsh", "twclid", "ttclid", "li_fat_id",
    "epik", "srsltid", "_ga", "_gl", "mkt_tok", "oly_anon_id", "oly_enc_id",
    "rb_clickid", "s_cid", "vero_conv", "vero_id", "wickedid", "ref_src",
    "__s", "sc_cid",
}
TRACKING_PREFIXES = ("utm_", "pk_", "mtm_", "hsa_", "_hs", "__hs", "matomo_", "piwik_")
_LOCAL_HOSTS = ("localhost", "127.0.0.1", "[::1]", "::1")
_MAIN_FRAME = QWebEngineUrlRequestInfo.ResourceType.ResourceTypeMainFrame


def _is_tracking(key):
    key = unquote(key).lower()
    return key in TRACKING_PARAMS or key.startswith(TRACKING_PREFIXES)


def strip_tracking(url):
    """Devuelve la URL sin parámetros de seguimiento, o None si no tenía."""
    text = url.toString(QUrl.ComponentFormattingOption.FullyEncoded)
    base, hash_sep, fragment = text.partition("#")
    path, q_sep, query = base.partition("?")
    if not q_sep or not query:
        return None
    parts = query.split("&")
    kept = [p for p in parts if p and not _is_tracking(p.split("=", 1)[0])]
    if len(kept) == len([p for p in parts if p]):
        return None
    new = path + ("?" + "&".join(kept) if kept else "") + (hash_sep + fragment)
    return QUrl(new, QUrl.ParsingMode.StrictMode)


class PrivacyInterceptor(QWebEngineUrlRequestInterceptor):
    def __init__(self, blocklist, parent=None):
        super().__init__(parent)
        self.blocklist = blocklist
        self.https_only = True
        self.blocked_count = 0

    def interceptRequest(self, info):
        url = info.requestUrl()
        scheme = url.scheme()
        if scheme not in ("http", "https", "ws", "wss"):
            return
        host = url.host().lower()

        # Las páginas que tú escribes se cargan; sus recursos de terceros, no.
        if info.resourceType() != _MAIN_FRAME and self.blocklist.is_blocked(host):
            self.blocked_count += 1
            info.block(True)
            return

        if (self.https_only and scheme == "http" and not host.endswith(".onion")
                and host not in _LOCAL_HOSTS):
            secure = QUrl(url)
            secure.setScheme("https")
            if secure.port() == 80:
                secure.setPort(-1)
            info.redirect(secure)
            return

        cleaned = strip_tracking(url)
        if cleaned is not None and cleaned.isValid():
            info.redirect(cleaned)
            return

        info.setHttpHeader(b"Sec-GPC", b"1")
