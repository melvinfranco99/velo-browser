package com.melvin.velo

import android.content.Context
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File

/**
 * Motor Gecko (Firefox) configurado al estilo de Tor Browser.
 * Solo puede existir un GeckoRuntime por proceso, y su proxy se fija al crearlo.
 */
object GeckoEngine {

    var runtime: GeckoRuntime? = null
        private set
    var socksPort = -1
        private set

    fun start(context: Context, socksPort: Int): GeckoRuntime {
        runtime?.let { return it }
        val config = File(context.filesDir, "geckoview-config.yaml")
        config.writeText(prefsYaml(socksPort))

        val settings = GeckoRuntimeSettings.Builder()
            .configFilePath(config.absolutePath)
            .javaScriptEnabled(true)
            .remoteDebuggingEnabled(false)
            .consoleOutput(false)
            .aboutConfigEnabled(false)
            .webManifest(false)
            .extensionsWebAPIEnabled(false)
            .locales(arrayOf("en-US"))
            .allowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY)
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    // Sin Safe Browsing ni listas de rastreo de Mozilla (contactarían con sus servidores).
                    .safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
                    .antiTracking(ContentBlocking.AntiTracking.NONE)
                    // Protección total de cookies: cada sitio tiene su propio bote de cookies.
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                    .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                    .build()
            )
            .build()

        val rt = GeckoRuntime.create(context.applicationContext, settings)
        runtime = rt
        this.socksPort = socksPort
        return rt
    }

    /**
     * Preferencias de Gecko. Muchas vienen de Tor Browser / arkenfox:
     * - todo por el SOCKS de Tor con DNS remoto, sin salida directa ni DNS local;
     * - resistFingerprinting: la protección antihuella de Tor Browser;
     * - nada de navegación privada en disco (IndexedDB, Cache API, service workers).
     */
    private fun prefsYaml(port: Int): String {
        val prefs = linkedMapOf<String, Any>(
            // --- Red: Tor obligatorio ---
            "network.proxy.type" to 1,
            "network.proxy.socks" to "127.0.0.1",
            "network.proxy.socks_port" to port,
            "network.proxy.socks_version" to 5,
            "network.proxy.socks_remote_dns" to true,
            "network.proxy.failover_direct" to false,
            "network.proxy.allow_hijacking_localhost" to true,
            "network.proxy.no_proxies_on" to "",
            "network.dns.disabled" to true,
            "network.trr.mode" to 5,
            "network.http.http3.enable" to false,
            "network.dns.disablePrefetch" to true,
            "network.prefetch-next" to false,
            "network.predictor.enabled" to false,
            "network.http.speculative-parallel-limit" to 0,
            "network.connectivity-service.enabled" to false,
            "network.captive-portal-service.enabled" to false,
            "network.IDN_show_punycode" to true,
            // --- Huella digital (como Tor Browser) ---
            "privacy.resistFingerprinting" to true,
            "privacy.resistFingerprinting.letterboxing" to true,
            "privacy.spoof_english" to 2,
            "intl.accept_languages" to "en-US, en",
            "webgl.disabled" to true,
            "dom.webgpu.enabled" to false,
            "media.peerconnection.enabled" to false,
            "media.peerconnection.ice.proxy_only" to true,
            "media.navigator.enabled" to false,
            "media.video_stats.enabled" to false,
            "dom.battery.enabled" to false,
            "dom.gamepad.enabled" to false,
            "dom.netinfo.enabled" to false,
            "device.sensors.enabled" to false,
            "geo.enabled" to false,
            "layout.css.visited_links_enabled" to false,
            // --- Rastreo ---
            "beacon.enabled" to false,
            "browser.send_pings" to false,
            "network.http.referer.XOriginTrimmingPolicy" to 2,
            "privacy.partition.network_state" to true,
            "privacy.globalprivacycontrol.enabled" to true,
            "privacy.globalprivacycontrol.functionality.enabled" to true,
            "privacy.query_stripping.enabled" to true,
            "privacy.query_stripping.enabled.pbmode" to true,
            "privacy.query_stripping.strip_list" to STRIP_LIST,
            // --- Solo RAM en navegación privada ---
            "browser.cache.disk.enable" to false,
            "browser.privatebrowsing.forceMediaMemoryCache" to true,
            "dom.indexedDB.privateBrowsing.enabled" to false,
            "dom.cache.privateBrowsing.enabled" to false,
            "dom.serviceWorkers.privateBrowsing.enabled" to false,
            "browser.formfill.enable" to false,
            "signon.rememberSignons" to false,
            // --- Sin servicios de Mozilla, Google ni terceros ---
            "browser.safebrowsing.malware.enabled" to false,
            "browser.safebrowsing.phishing.enabled" to false,
            "browser.safebrowsing.downloads.enabled" to false,
            "browser.safebrowsing.downloads.remote.enabled" to false,
            "media.gmp-provider.enabled" to false,
            "media.gmp-gmpopenh264.enabled" to false,
            "media.eme.enabled" to false,
            "dom.push.enabled" to false,
            "dom.webnotifications.enabled" to false,
            "extensions.update.enabled" to false,
            "security.webauth.webauthn" to false,
            "toolkit.telemetry.enabled" to false,
            "datareporting.healthreport.uploadEnabled" to false,
        )
        return buildString {
            append("prefs:\n")
            for ((k, v) in prefs) {
                val value = if (v is String) "\"" + v.replace("\"", "\\\"") + "\"" else v.toString()
                append("  ").append(k).append(": ").append(value).append('\n')
            }
        }
    }

    private const val STRIP_LIST =
        "fbclid gclid gclsrc dclid gbraid wbraid msclkid mc_cid mc_eid yclid _openstat igshid igsh " +
        "twclid ttclid li_fat_id epik srsltid _ga _gl mkt_tok oly_anon_id oly_enc_id rb_clickid s_cid " +
        "vero_conv vero_id wickedid ref_src __s sc_cid utm_source utm_medium utm_campaign utm_term " +
        "utm_content utm_id utm_name utm_reader utm_referrer utm_social utm_brand pk_campaign pk_kwd " +
        "pk_source pk_medium mtm_campaign mtm_source mtm_medium hsa_acc hsa_cam hsa_grp hsa_ad hsa_src " +
        "hsa_tgt hsa_kw hsa_mt hsa_net hsa_ver _hsenc _hsmi __hssc __hstc __hsfp"
}
