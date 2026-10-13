package com.melvin.velo

import android.net.Uri

object UrlInput {
    const val SEARCH_URL = "https://duckduckgo.com/?q="

    /** Convierte lo escrito en la barra en una URL (o en una búsqueda). */
    fun toUrl(text: String): String {
        val t = text.trim()
        if (t.contains("://")) return t
        val looksLikeUrl = !t.contains(' ') && t.contains('.') && !t.endsWith('.')
        return if (looksLikeUrl) "https://$t" else SEARCH_URL + Uri.encode(t)
    }
}
