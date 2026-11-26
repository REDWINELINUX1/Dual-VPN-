package com.vpn.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

object ConfigManager {

    fun generateAndSaveConfig(context: Context, rawContent: String) {
        try {
            val content = decodeIfBase64(rawContent.trim()).trim()
            val config = if (content.startsWith("{")) {
                ConfigNormalizer.normalizeConfig(JSONObject(content))
            } else {
                createConfigFromLinks(content)
            }
            File(context.filesDir, "config.json").writeText(config.toString(4))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse or save VPN configuration", e)
        }
    }

    private fun createConfigFromLinks(content: String): JSONObject {
        val nodes = JSONArray()
        val nodeTags = JSONArray()
        var index = 1
        for (line in content.lines()) {
            val link = line.trim()
            if (link.isEmpty()) continue
            val tag = "server-$index"
            val node = parseProxyLink(link, tag) ?: continue
            nodes.put(node)
            nodeTags.put(tag)
            index++
        }
        require(nodes.length() > 0) { "No supported proxy links found" }

        val members = JSONArray().put("auto-select")
        for (i in 0 until nodeTags.length()) members.put(nodeTags.getString(i))
        val outbounds = JSONArray()
            .put(
                JSONObject()
                    .put("type", "selector")
                    .put("tag", "select")
                    .put("outbounds", members)
                    .put("default", "auto-select")
            )
            .put(
                JSONObject()
                    .put("type", "urltest")
                    .put("tag", "auto-select")
                    .put("outbounds", nodeTags)
                    .put("url", "https://www.gstatic.com/generate_204")
                    .put("interval", "3m")
                    .put("tolerance", 50)
            )
        for (i in 0 until nodes.length()) outbounds.put(nodes.getJSONObject(i))

        val config = JSONObject()
            .put(
                "log",
                JSONObject().put("disabled", false).put("level", "info").put("timestamp", true)
            )
            .put("outbounds", outbounds)
            .put(
                "route",
                JSONObject()
                    .put("rules", JSONArray().put(JSONObject().put("action", "sniff")))
                    .put("final", "select")
                    .put("auto_detect_interface", true)
            )
        addTunAndDns(config)
        return ConfigNormalizer.normalizeConfig(config)
    }

    private fun decodeIfBase64(value: String): String {
        val decoded = runCatching {
            val normalized = value.replace('-', '+').replace('_', '/')
            val padded = normalized.padEnd((normalized.length + 3) / 4 * 4, '=')
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull() ?: return value

        return if (
            decoded.trimStart().startsWith("{") ||
            listOf("vless://", "vmess://", "trojan://", "ss://")
                .any { decoded.trimStart().startsWith(it) }
        ) decoded else value
    }

    private const val TAG = "ConfigManager"
}
