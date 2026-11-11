package com.vpn.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.util.Base64

object ConfigManager {

    fun generateAndSaveConfig(context: Context, rawContent: String) {
        try {
            // اصلاح نحوه‌ی تعریف متغیر و بررسی Base64
            val decodedContent = if (isBase64(rawContent)) {
                String(Base64.getDecoder().decode(rawContent.trim()), Charsets.UTF_8)
            } else {
                rawContent
            }

            val lines = decodedContent.split("\n", "\r\n")
            val outboundsArray = JSONArray()
            val outboundTags = JSONArray()

            var serverIndex = 1
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("vless://")) {
                    val parsedOutbound = parseVlessLink(trimmed, "server-$serverIndex")
                    if (parsedOutbound != null) {
                        outboundsArray.put(parsedOutbound)
                        outboundTags.put("server-$serverIndex")
                        serverIndex++
                    }
                }
            }

            if (outboundTags.length() == 0) return

            // ایجاد گروه urltest برای جابجایی خودکار بین سرورهای سالم
            val urlTestOutbound = JSONObject().apply {
                put("type", "urltest")
                put("tag", "auto-select")
                put("outbounds", outboundTags)
                put("url", "https://www.gstatic.com/generate_204")
                put("interval", "3m")
                put("tolerance", 50)
            }

            // ترکیب خروجی‌ها (ابتدا auto-select، سپس سرورها، و در نهایت direct و block)
            val finalOutbounds = JSONArray().apply {
                put(urlTestOutbound)
                for (i in 0 until outboundsArray.length()) {
                    put(outboundsArray.getJSONObject(i))
                }
                put(JSONObject().apply {
                    put("type", "direct")
                    put("tag", "direct")
                })
                put(JSONObject().apply {
                    put("type", "block")
                    put("tag", "block")
                })
            }

            // ساختار کامل فایل config.json برای sing-box
            val configJson = JSONObject().apply {
                put("log", JSONObject().put("level", "info").put("timestamp", true))
                put("dns", JSONObject().apply {
                    put("servers", JSONArray().put(JSONObject().put("tag", "google-dns").put("address", "8.8.8.8").put("detour", "auto-select")))
                    put("strategy", "ipv4_only")
                })
                put("inbounds", JSONArray().put(JSONObject().apply {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("inet4_address", "172.19.0.1/30")
                    put("auto_route", true)
                    put("strict_route", true)
                    put("stack", "system")
                    put("sniff", true)
                }))
                put("outbounds", finalOutbounds)
                put("route", JSONObject().apply {
                    put("rules", JSONArray().put(JSONObject().put("protocol", "dns").put("action", "hijack-dns")))
                    put("auto_detect_interface", true)
                })
            }

            // ذخیره نهایی در حافظه داخلی برنامه (filesDir)
            val configFile = File(context.filesDir, "config.json")
            configFile.writeText(configJson.toString(2))

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseVlessLink(link: String, tag: String): JSONObject? {
        try {
            val uriPart = link.substringAfter("vless://")
            val atIndex = uriPart.indexOf('@')
            val colonIndex = uriPart.indexOf(':', atIndex)
            val questionIndex = uriPart.indexOf('?', colonIndex)
            val hashIndex = uriPart.indexOf('#', if (questionIndex != -1) questionIndex else colonIndex)

            val uuid = uriPart.substring(0, atIndex)
            val host = uriPart.substring(atIndex + 1, colonIndex)

            val portStr = when {
                questionIndex != -1 -> uriPart.substring(colonIndex + 1, questionIndex)
                hashIndex != -1 -> uriPart.substring(colonIndex + 1, hashIndex)
                else -> uriPart.substring(colonIndex + 1)
            }
            val port = portStr.toInt()

            val outbound = JSONObject().apply {
                put("type", "vless")
                put("tag", tag)
                put("server", host)
                put("server_port", port)
                put("uuid", uuid)
            }

            if (questionIndex != -1) {
                val queryEnd = if (hashIndex != -1) hashIndex else uriPart.length
                val queryStr = uriPart.substring(questionIndex + 1, queryEnd)
                val params = queryStr.split("&")
                val queryMap = mutableMapOf<String, String>()
                for (param in params) {
                    val kv = param.split("=")
                    if (kv.size == 2) {
                        queryMap[kv[0]] = URLDecoder.decode(kv[1], "UTF-8")
                    }
                }

                if (queryMap["security"] == "reality" || queryMap["security"] == "tls") {
                    val tlsObj = JSONObject().apply {
                        put("enabled", true)
                        if (queryMap.containsKey("sni")) {
                            put("server_name", queryMap["sni"])
                        }
                    }
                    if (queryMap["security"] == "reality") {
                        val realityObj = JSONObject().apply {
                            put("enabled", true)
                            put("public_key", queryMap["pbk"] ?: "")
                            if (queryMap.containsKey("sid")) {
                                put("short_id", queryMap["sid"])
                            }
                        }
                        tlsObj.put("reality", realityObj)
                    }
                    outbound.put("tls", tlsObj)
                }

                if (queryMap.containsKey("flow")) {
                    outbound.put("flow", queryMap["flow"])
                }
            }

            return outbound
        } catch (e: Exception) {
            return null
        }
    }

    private fun isBase64(str: String): Boolean {
        return try {
            Base64.getDecoder().decode(str.trim())
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
