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
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
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
) {
    val security = (p["security"] ?: (if (defaultTls) "tls" else "none"))
        .trim()
        .lowercase()
    if (security == "tls" || security == "reality") {
        val tls = JSONObject()
        tls.put("enabled", true)
        val serverName = sequenceOf(p["sni"], p["peer"], p["host"], host)
            .firstOrNull { !it.isNullOrBlank() }
        if (serverName != null) tls.put("server_name", serverName)
        if (
            listOf(p["allowInsecure"], p["allow_insecure"], p["insecure"])
                .any { it == "1" || it.equals("true", ignoreCase = true) }
        ) {
            tls.put("insecure", true)
        }
        val alpn = p["alpn"]
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
        if (!alpn.isNullOrEmpty()) tls.put("alpn", JSONArray(alpn))
        val fp = p["fp"]?.takeIf(String::isNotBlank)?.trim()
        if (!fp.isNullOrEmpty() || security == "reality") {
            val utls = JSONObject()
            utls.put("enabled", true)
            utls.put("fingerprint", if (fp.isNullOrEmpty()) "chrome" else fp)
            tls.put("utls", utls)
        }
        if (security == "reality") {
            val pbk = p["pbk"] ?: p["publicKey"] ?: p["public_key"]
            require(!pbk.isNullOrBlank()) {
                "Reality security requires the pbk (public key) parameter"
            }
            val reality = JSONObject()
            reality.put("enabled", true)
            reality.put("public_key", pbk)
            reality.put("short_id", p["sid"] ?: p["shortId"] ?: p["short_id"] ?: "")
            tls.put("reality", reality)
        }
        o.put("tls", tls)
    } else if (security != "none") {
        throw IllegalArgumentException("Unsupported security type: $security")
    } else {
        require(
            sequenceOf(
                "sni", "peer", "fp", "alpn", "pbk", "publicKey", "public_key",
                "sid", "shortId", "short_id",
                "allowInsecure", "allow_insecure", "insecure"
            )
                .none { !p[it].isNullOrBlank() }
        ) { "TLS parameters were provided while security is set to none" }
    }

    require(!p["headerType"].equals("http", ignoreCase = true)) {
        "Legacy HTTP header obfuscation is unsupported by sing-box"
    }

    when (p["type"]?.trim()?.lowercase()) {
        null, "", "tcp", "raw" -> {}
        "ws" -> {
            val t = JSONObject()
            t.put("type", "ws")
            t.put("path", p["path"] ?: "/")
            val h = p["host"]
            if (!h.isNullOrEmpty()) t.put("headers", JSONObject().put("Host", h))
            o.put("transport", t)
        }
        "http", "h2" -> {
            val t = JSONObject()
            t.put("type", "http")
            t.put("path", p["path"] ?: "/")
            val h = p["host"]?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
            if (!h.isNullOrEmpty()) t.put("host", JSONArray(h))
            o.put("transport", t)
        }
        "grpc" -> {
            val t = JSONObject()
            t.put("type", "grpc")
            t.put("service_name", p["serviceName"] ?: p["service_name"] ?: "")
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
        else -> throw IllegalArgumentException("Unsupported transport type: ${p["type"]}")
    }
}

private fun parseVless(link: String, tag: String): JSONObject? {
    val u = splitUri(link)
        ?: throw IllegalArgumentException("Malformed VLESS link: expected vless://uuid@host:port")
    if (u.user.isEmpty()) throw IllegalArgumentException("VLESS UUID is missing")
    if (u.port !in 1..65535) {
        throw IllegalArgumentException("VLESS server port must be between 1 and 65535")
    }
    val encryption = u.params["encryption"]
    if (encryption != null && !encryption.equals("none", ignoreCase = true)) {
        throw IllegalArgumentException("Unsupported VLESS encryption: $encryption (only none is supported)")
    }
    val o = JSONObject()
    o.put("type", "vless")
    o.put("tag", tag)
    o.put("server", u.host)
    o.put("server_port", u.port)
    o.put("uuid", u.user)
    val flow = u.params["flow"]?.takeIf(String::isNotBlank)
    if (flow != null) {
        require(flow == "xtls-rprx-vision") { "Unsupported VLESS flow: $flow" }
        require(u.params["security"]?.trim()?.lowercase() in setOf("tls", "reality")) {
            "VLESS flow $flow requires TLS or Reality security"
        }
        o.put("flow", flow)
    }
    applyTlsAndTransport(o, u.params, u.host, false)
    return o
}

private fun parseTrojan(link: String, tag: String): JSONObject? {
    val u = splitUri(link)
        ?: throw IllegalArgumentException("Malformed Trojan link: expected trojan://password@host:port")
    require(u.user.isNotEmpty()) { "Trojan password is missing" }
    require(u.port in 1..65535) { "Trojan server port must be between 1 and 65535" }
    require(u.params["security"]?.trim()?.lowercase() != "none") {
        "Trojan requires TLS; security=none is invalid"
    }
    val o = JSONObject()
    o.put("type", "trojan")
    o.put("tag", tag)
    o.put("server", u.host)
    o.put("server_port", u.port)
    o.put("password", u.user)
    applyTlsAndTransport(o, u.params, u.host, true)
    return o
}

private fun parseVmess(link: String, tag: String): JSONObject? {
    val j = try {
        JSONObject(b64(link.substringAfter("://").substringBefore('#')))
    } catch (e: Exception) {
        throw IllegalArgumentException(
            "Malformed VMess link: expected vmess:// followed by base64-encoded JSON",
            e
        )
    }
    val server = j.optString("add")
    val port = j.optString("port").toIntOrNull()
        ?: throw IllegalArgumentException("VMess server port is missing or invalid")
    val id = j.optString("id")
    require(server.isNotEmpty()) { "VMess server address is missing" }
    require(id.isNotEmpty()) { "VMess UUID is missing" }
    require(port in 1..65535) { "VMess server port must be between 1 and 65535" }

    val p = HashMap<String, String>()
    p["security"] = if (j.optString("tls").equals("tls", ignoreCase = true)) "tls" else "none"
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
    applyTlsAndTransport(o, p, server, false)
    return o
}

private fun parseSs(link: String, tag: String): JSONObject? {
    val body = link.substringAfter("://").substringBefore('#')
    require(!body.contains("plugin=")) {
        "Shadowsocks plugin parameters are unsupported"
    }
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
        require(at >= 0) { "Malformed Shadowsocks link: missing server address" }
        userInfo = d.substring(0, at)
        hostPort = d.substring(at + 1)
    }

    val method = userInfo.substringBefore(':')
    val password = userInfo.substringAfter(':', "")
    require(method in SS_METHODS) { "Unsupported Shadowsocks cipher: $method" }
    require(password.isNotEmpty()) { "Shadowsocks password is missing" }
    val hp = splitHostPort(hostPort)
        ?: throw IllegalArgumentException("Malformed Shadowsocks link: expected host:port")
    require(hp.second in 1..65535) {
        "Shadowsocks server port must be between 1 and 65535"
    }

    val o = JSONObject()
    o.put("type", "shadowsocks")
    o.put("tag", tag)
    o.put("server", hp.first)
    o.put("server_port", hp.second)
    o.put("method", method)
    o.put("password", password)
    return o
}

internal fun parseProxyLink(link: String, tag: String): JSONObject? {
    return try {
        when {
            link.startsWith("vless://", ignoreCase = true) -> parseVless(link, tag)
            link.startsWith("vmess://", ignoreCase = true) -> parseVmess(link, tag)
            link.startsWith("trojan://", ignoreCase = true) -> parseTrojan(link, tag)
            link.startsWith("ss://", ignoreCase = true) -> parseSs(link, tag)
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

internal fun parseManualProxyLink(link: String, tag: String): JSONObject {
    require(link.isNotBlank()) { "Proxy link is empty" }
    val protocol = link.substringBefore("://").lowercase()
    val schemeSeparator = link.indexOf("://")
    val normalizedLink = if (schemeSeparator >= 0) {
        link.substring(0, schemeSeparator).lowercase() + link.substring(schemeSeparator)
    } else {
        link
    }
    val node = when (protocol) {
        "vless" -> parseVless(normalizedLink, tag)
        "vmess" -> parseVmess(normalizedLink, tag)
        "trojan" -> parseTrojan(normalizedLink, tag)
        "ss" -> parseSs(normalizedLink, tag)
        else -> throw IllegalArgumentException("Unsupported proxy link: $protocol")
    }
    return node ?: throw IllegalArgumentException("Invalid $protocol link or unsupported parameters")
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
                    val node = parseProxyLink(link, tag) ?: continue

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

            addTunAndDns(config)

            ConfigNormalizer.normalizeConfig(config)
            val text = config.toString(4)
            File(context.filesDir, "config.json").writeText(text)

            val saved = saveToDownloads(context, "standalone-vpn-config.json", text)
            report.appendLine("مجموع نودها: ${nodes.length()}")
            report.appendLine(
                if (saved) "فایل کانفیگ ذخیره شد."
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

suspend fun addManualConfig(context: Context, link: String): Result<Unit> {
    return withContext(Dispatchers.IO) {
        try {
            val input = link.trim()
            if (input.startsWith("{")) {
                val config = ConfigNormalizer.normalizeConfig(JSONObject(input))
                val outbounds = config.optJSONArray("outbounds") ?: JSONArray().also {
                    config.put("outbounds", it)
                }
                val directOutbound = (0 until outbounds.length())
                    .mapNotNull(outbounds::optJSONObject)
                    .firstOrNull { it.optString("tag") == "direct" }
                if (directOutbound == null) {
                    outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
                } else {
                    require(directOutbound.optString("type") == "direct") {
                        "Outbound tag 'direct' must have type 'direct'"
                    }
                }
                val text = config.toString(4)
                File(context.filesDir, "config.json").writeText(text)
                saveToDownloads(context, "manual-vpn-config.json", text)
                return@withContext Result.success(Unit)
            }

            val node = parseManualProxyLink(input, "manual_node")

            val config = JSONObject()
            config.put("log", JSONObject().put("disabled", false).put("level", "info").put("timestamp", true))

            val outbounds = JSONArray()
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
            fixedRoute.put("final", "manual_node")
            fixedRoute.put("auto_detect_interface", true)

            config.put("route", fixedRoute)
            config.put("outbounds", outbounds)

            addTunAndDns(config, dnsDetour = "manual_node")

            ConfigNormalizer.normalizeConfig(config)
            val text = config.toString(4)
            File(context.filesDir, "config.json").writeText(text)
            saveToDownloads(context, "manual-vpn-config.json", text)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
