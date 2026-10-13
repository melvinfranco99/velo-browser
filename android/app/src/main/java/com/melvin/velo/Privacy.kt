package com.melvin.velo

import android.content.Context
import android.net.Uri
import java.util.Locale

/** Dominios de rastreo/publicidad/malware; un dominio bloquea también sus subdominios. */
class Blocklist private constructor(private val domains: HashSet<String>) {

    val size get() = domains.size

    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host.lowercase(Locale.ROOT).trimEnd('.')
        while (true) {
            if (h in domains) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    companion object {
        private val IGNORED = setOf("0.0.0.0", "127.0.0.1", "localhost", "localhost.localdomain",
            "local", "broadcasthost")

        fun load(context: Context): Blocklist {
            val set = HashSet<String>(120_000)
            for (file in listOf("blocklist.txt", "hosts.txt")) {
                runCatching {
                    context.assets.open(file).bufferedReader().useLines { lines ->
                        for (raw in lines) {
                            val line = raw.substringBefore('#').trim()
                            if (line.isEmpty()) continue
                            val parts = line.split(Regex("\\s+"))
                            val domain = (if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1"))
                                parts[1] else parts[0]).lowercase(Locale.ROOT).trimEnd('.')
                            if ('.' in domain && domain !in IGNORED) set.add(domain)
                        }
                    }
                }
            }
            return Blocklist(set)
        }
    }
}

object UrlCleaner {
    private val PARAMS = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "mc_cid",
        "mc_eid", "yclid", "_openstat", "igshid", "igsh", "twclid", "ttclid", "li_fat_id",
        "epik", "srsltid", "_ga", "_gl", "mkt_tok", "oly_anon_id", "oly_enc_id",
        "rb_clickid", "s_cid", "vero_conv", "vero_id", "wickedid", "ref_src", "__s", "sc_cid",
    )
    private val PREFIXES = listOf("utm_", "pk_", "mtm_", "hsa_", "_hs", "__hs", "matomo_", "piwik_")

    private fun isTracking(key: String): Boolean {
        val k = Uri.decode(key).lowercase(Locale.ROOT)
        return k in PARAMS || PREFIXES.any { k.startsWith(it) }
    }

    /** Devuelve la URL sin parámetros de seguimiento, o null si no tenía ninguno. */
    fun strip(url: String): String? {
        val hashIdx = url.indexOf('#')
        val base = if (hashIdx >= 0) url.substring(0, hashIdx) else url
        val fragment = if (hashIdx >= 0) url.substring(hashIdx) else ""
        val q = base.indexOf('?')
        if (q < 0) return null
        val parts = base.substring(q + 1).split('&').filter { it.isNotEmpty() }
        val kept = parts.filterNot { isTracking(it.substringBefore('=')) }
        if (kept.size == parts.size) return null
        return base.substring(0, q) + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }

    /** http:// → https:// salvo .onion (Tor ya cifra de extremo a extremo). */
    fun upgrade(url: String): String? {
        val uri = Uri.parse(url)
        if (uri.scheme != "http") return null
        val host = uri.host ?: return null
        if (host.endsWith(".onion")) return null
        return "https" + url.substring(4)
    }

    /** Convierte lo escrito en la barra en una URL (o en una búsqueda). */
    fun fromUserInput(text: String): String {
        val t = text.trim()
        if (t.contains("://")) return t
        val looksLikeUrl = !t.contains(' ') && t.contains('.') && !t.endsWith('.')
        return if (looksLikeUrl) "https://$t" else SEARCH_URL + Uri.encode(t)
    }

    const val SEARCH_URL = "https://duckduckgo.com/?q="
}
