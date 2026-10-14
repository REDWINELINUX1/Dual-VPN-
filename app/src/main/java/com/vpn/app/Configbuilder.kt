package com.vpn.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Html
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

private val CHANNELS = listOf("BugFreeNet", "Eag1e_YT", "persianvpnhub", "ConfigWireguard")
private const val MAX_NODES = 40
private val LINK_REGEX = Regex("(vless|vmess|trojan|ss)://[^\\s<>\"']+")

private val SS_METHODS = setOf(
    "aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
    "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
    "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm",
    "2022-blake3-chacha20-poly1305"
)

private class Parts(
    val user: String,
    val host: String,
    val port: Int,
    val params: Map<String, String>
)

private fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.setRequestProperty(
        "User-Agent",
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
    )
    c.connectTimeout = 15000
    c.readTimeout = 15000
    try {
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}

private fun extractLinks(html: String): List<String> {
    val text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
    return LINK_REGEX.findAll(text).map { it.value.trimEnd('.', ',', ';') }.toList()
}

private fun decode(s: String): String {
    return try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }
}

private fun b64(s: String): String {
    val clean = s.trim().replace('-', '+').replace('_', '/')
    val padded = clean.padEnd((clean.length + 3) / 4 * 4, '=')
    return String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
}

private fun queryMap(q: String): Map<String, String> {
    val map = HashMap<String, String>()
    for (pair in q.split("&")) {
        val i = pair.indexOf('=')
        if (i > 0) map[pair.substring(0, i)] = decode(pair.substring(i + 1))
    }
    return map
}

private fun splitHostPort(hp: String): Pair<String, Int>? {
    val idx = hp.lastIndexOf(':')
    if (idx <= 0) return null
    val host = hp.substring(0, idx).trim('[', ']')
    val port = hp.substring(idx + 1).trim('/').toIntOrNull() ?: return null
    if (host.isEmpty()) return null
    return Pair(host, port)
}

private fun splitUri(link: String): Parts? {
    val body = link.substringAfter("://")
    val noFrag = body.substringBefore('#')
    val query = noFrag.substringAfter('?', "")
    val main = noFrag.substringBefore('?')
    val at = main.lastIndexOf('@')
    if (at < 0) return null
    val user = decode(main.substring(0, at))
    val hp = splitHostPort(main.substring(at + 1)) ?: return null
    return Parts(user, hp.first, hp.second, queryMap(query))
}

private fun applyTlsAndTransport(
    o: JSONObject,
    p: Map<String, String>,
    host: String,
    defaultTls: Boolean
): Boolean {
    val security = p["security"] ?: (if (defaultTls) "tls" else "none")
    if (security == "tls" || security == "reality") {
        val tls = JSONObject()
        tls.put("enabled", true)
        tls.put("server_name", p["sni"] ?: p["peer"] ?: p["host"] ?: host)
        if (p["allowInsecure"] == "1" || p["insecure"] == "1") tls.put("insecure", true)
        val alpn = p["alpn"]
        if (!alpn.isNullOrEmpty()) tls.put("alpn", JSONArray(alpn.split(",")))
        val fp = p["fp"]
        if (!fp.isNullOrEmpty() || security == "reality") {
            val utls = JSONObject()
            utls.put("enabled", true)
            utls.put("fingerprint", if (fp.isNullOrEmpty()) "chrome" else fp)
            tls.put("utls", utls)
        }
        if (security == "reality") {
            val pbk = p["pbk"]
            if (pbk.isNullOrEmpty()) return false
            val reality = JSONObject()
            reality.put("enabled", true)
            reality.put("public_key", pbk)
            reality.put("short_id", p["sid"] ?: "")
            tls.put("reality", reality)
        }
        o.put("tls", tls)
    } else if (security != "none") {
        return false
    }

    if (p["headerType"] == "http") return false

    when (p["type"]) {
        null, "", "tcp" -> {}
        "ws" -> {
            val t = JSONObject()
            t.put("type", "ws")
            t.put("path", p["path"] ?: "/")
            val h = p["host"]
            if (!h.isNullOrEmpty()) t.put("headers", JSONObject().put("Host", h))
            o.put("transport", t)
        }
        "grpc" -> {
            val t = JSONObject()
            t.put("type", "grpc")
            t.put("service_name", p["serviceName"] ?: "")
            o.put("transport", t)
        }
        "httpupgrade" -> {
            val t = JSONObject()
            t.put("type", "httpupgrade")
            t.put("path", p["path"] ?: "/")
            val h = p["host"]
            if (!h.isNullOrEmpty()) t.put("host", h)
            o.put("transport", t)
        }
        else -> return false
    }
    return true
}

private fun parseVless(link: String, tag: String): JSONObject? {
    val u = splitUri(link) ?: return null
    if (u.user.isEmpty() || u.port !in 1..65535) return null
    val o = JSONObject()
    o.put("type", "vless")
    o.put("tag", tag)
    o.put("server", u.host)
    o.put("server_port", u.port)
    o.put("uuid", u.user)
    val flow = u.params["flow"]
    if (!flow.isNullOrEmpty()) o.put("flow", flow)
    if (!applyTlsAndTransport(o, u.params, u.host, false)) return null
    return o
}

private fun parseTrojan(link: String, tag: String): JSONObject? {
    val u = splitUri(link) ?: return null
    if (u.user.isEmpty() || u.port !in 1..65535) return null
    val o = JSONObject()
    o.put("type", "trojan")
    o.put("tag", tag)
    o.put("server", u.host)
    o.put("server_port", u.port)
    o.put("password", u.user)
    if (!applyTlsAndTransport(o, u.params, u.host, true)) return null
    return o
}

private fun parseVmess(link: String, tag: String): JSONObject? {
    val j = JSONObject(b64(link.removePrefix("vmess://").substringBefore('#')))
    val server = j.optString("add")
    val port = j.optString("port").toIntOrNull() ?: return null
    val id = j.optString("id")
    if (server.isEmpty() || id.isEmpty() || port !in 1..65535) return null

    val p = HashMap<String, String>()
    p["security"] = if (j.optString("tls") == "tls") "tls" else "none"
    p["sni"] = j.optString("sni")
    p["type"] = j.optString("net")
    p["host"] = j.optString("host")
    p["path"] = j.optString("path")
    p["serviceName"] = j.optString("path")
    p["fp"] = j.optString("fp")
    p["alpn"] = j.optString("alpn")
    p["headerType"] = j.optString("type")
    p.entries.removeAll { it.value.isEmpty() }

    val o = JSONObject()
    o.put("type", "vmess")
    o.put("tag", tag)
    o.put("server", server)
    o.put("server_port", port)
    o.put("uuid", id)
    o.put("security", j.optString("scy").ifEmpty { "auto" })
    o.put("alter_id", j.optString("aid").toIntOrNull() ?: 0)
    if (!applyTlsAndTransport(o, p, server, false)) return null
    return o
}

private fun parseSs(link: String, tag: String): JSONObject? {
    val body = link.removePrefix("ss://").substringBefore('#')
    if (body.contains("plugin=")) return null
    val main = body.substringBefore('?')

    val userInfo: String
    val hostPort: String
    if (main.contains("@")) {
        val at = main.lastIndexOf('@')
        val up = main.substring(0, at)
        userInfo = if (up.contains(":")) decode(up) else b64(up)
        hostPort = main.substring(at + 1)
    } else {
        val d = b64(main)
        val at = d.lastIndexOf('@')
        if (at < 0) return null
        userInfo = d.substring(0, at)
        hostPort = d.substring(at + 1)
    }

    val method = userInfo.substringBefore(':')
    val password = userInfo.substringAfter(':', "")
    if (method !in SS_METHODS || password.isEmpty()) return null
    val hp = splitHostPort(hostPort) ?: return null
    if (hp.second !in 1..65535) return null

    val o = JSONObject()
    o.put("type", "shadowsocks")
    o.put("tag", tag)
    o.put("server", hp.first)
    o.put("server_port", hp.second)
    o.put("method", method)
    o.put("password", password)
    return o
}

private fun parseLink(link: String, tag: String): JSONObject? {
    return try {
        when {
            link.startsWith("vless://") -> parseVless(link, tag)
            link.startsWith("vmess://") -> parseVmess(link, tag)
            link.startsWith("trojan://") -> parseTrojan(link, tag)
            link.startsWith("ss://") -> parseSs(link, tag)
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

private fun saveToDownloads(context: Context, name: String, text: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    return try {
        val resolver = context.contentResolver
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            MediaStore.MediaColumns.DISPLAY_NAME + "=?",
            arrayOf(name)
        )
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            false
        } else {
            val stream = resolver.openOutputStream(uri)
            if (stream == null) {
                false
            } else {
                stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                true
            }
        }
    } catch (e: Exception) {
        false
    }
}

suspend fun buildConfig(
    context: Context,
    onStatusUpdate: (String) -> Unit
): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val report = StringBuilder()
            val nodes = JSONArray()
            val nodeTags = JSONArray()
            val seen = HashSet<String>()

            for (channel in CHANNELS) {
                if (nodes.length() >= MAX_NODES) break
                report.appendLine("در حال خواندن @$channel ...")
                onStatusUpdate(report.toString())

                val links: List<String> = try {
                    extractLinks(httpGet("https://t.me/s/$channel"))
                } catch (e: Exception) {
                    report.appendLine("@$channel: خطا - ${e.localizedMessage}")
                    emptyList()
                }

                var added = 0
                for (link in links) {
                    if (nodes.length() >= MAX_NODES) break
                    val tag = "${channel}_n${nodes.length()}"
                    val node = parseLink(link, tag) ?: continue
                    
                    // ثبت نام کانال مبدأ به عنوان کامنت درون نود در فایل JSON
                    node.put("comment", "Sourced from Telegram Channel: @$channel")

                    val key = node.getString("type") + "|" + node.getString("server") + "|" +
                            node.getInt("server_port") + "|" +
                            node.optString("uuid") + node.optString("password")
                    if (!seen.add(key)) continue
                    nodes.put(node)
                    nodeTags.put(tag)
                    added++
                }
                report.appendLine("@$channel: ${links.size} لینک، $added نود قابل استفاده")
                onStatusUpdate(report.toString())
            }

            if (nodes.length() == 0) {
                report.appendLine("هیچ نود قابل استفاده‌ای پیدا نشد.")
                onStatusUpdate(report.toString())
                return@withContext false
            }

            val config = JSONObject()
            config.put(
                "log",
                JSONObject().put("disabled", false).put("level", "info").put("timestamp", true)
            )

            val outbounds = JSONArray()

            val selectorList = JSONArray()
            selectorList.put("auto")
            selectorList.put("direct")
            for (i in 0 until nodeTags.length()) {
                selectorList.put(nodeTags.getString(i))
            }

            val selector = JSONObject()
            selector.put("type", "selector")
            selector.put("tag", "select")
            selector.put("outbounds", selectorList)
            selector.put("default", "auto")
            outbounds.put(selector)

            val auto = JSONObject()
            auto.put("type", "urltest")
            auto.put("tag", "auto")
            auto.put("outbounds", nodeTags)
            auto.put("url", "https://www.gstatic.com/generate_204")
            auto.put("interval", "5m")
            outbounds.put(auto)

            outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

            for (i in 0 until nodes.length()) {
                outbounds.put(nodes.getJSONObject(i))
            }

            val fixedRules = JSONArray()
            fixedRules.put(JSONObject().put("action", "sniff"))
            fixedRules.put(
                JSONObject()
                    .put("ip_is_private", true)
                    .put("action", "route")
                    .put("outbound", "direct")
            )
            fixedRules.put(
                JSONObject()
                    .put("domain_suffix", JSONArray().put(".ir"))
                    .put("action", "route")
                    .put("outbound", "direct")
            )
            val fixedRoute = JSONObject()
            fixedRoute.put("rules", fixedRules)
            fixedRoute.put("final", "select")
            fixedRoute.put("auto_detect_interface", true)
            config.put("route", fixedRoute)
            config.put("outbounds", outbounds)

            val text = config.toString(4)
            // ذخیره در مسیر داخلی برای استفاده هسته VPN
            File(context.filesDir, "config.json").writeText(text)

            val saved = saveToDownloads(context, "standalone-vpn-config.json", text)
            report.appendLine("مجموع نودها: ${nodes.length()}")
            report.appendLine(
                if (saved) "فایل کانفیگ ذخیره شد و آماده استفاده به عنوان VPN است."
                else "ذخیره در Downloads انجام نشد."
            )
            onStatusUpdate(report.toString())
            true
        } catch (e: Exception) {
            onStatusUpdate("خطا: ${e.localizedMessage}")
            false
        }
    }
}

suspend fun addManualConfig(context: Context, link: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val node = parseLink(link.trim(), "manual_node") ?: return@withContext false
            node.put("comment", "Sourced from Manual Input")
            
            val config = JSONObject()
            config.put("log", JSONObject().put("disabled", false).put("level", "info").put("timestamp", true))
            
            val outbounds = JSONArray()
            
            val selectorList = JSONArray()
            selectorList.put("manual_node")
            selectorList.put("direct")
            
            val selector = JSONObject()
            selector.put("type", "selector")
            selector.put("tag", "select")
            selector.put("outbounds", selectorList)
            selector.put("default", "manual_node")
            outbounds.put(selector)
            
            outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
            outbounds.put(node)
            
            val fixedRules = JSONArray()
            fixedRules.put(JSONObject().put("action", "sniff"))
            fixedRules.put(
                JSONObject()
                    .put("ip_is_private", true)
                    .put("action", "route")
                    .put("outbound", "direct")
            )
            fixedRules.put(
                JSONObject()
                    .put("domain_suffix", JSONArray().put(".ir"))
                    .put("action", "route")
                    .put("outbound", "direct")
            )
            val fixedRoute = JSONObject()
            fixedRoute.put("rules", fixedRules)
            fixedRoute.put("final", "select")
            fixedRoute.put("auto_detect_interface", true)
            
            config.put("route", fixedRoute)
            config.put("outbounds", outbounds)
            
            val text = config.toString(4)
            File(context.filesDir, "config.json").writeText(text)
            saveToDownloads(context, "manual-vpn-config.json", text)
            true
        } catch (e: Exception) {
            false
        }
    }
}
