package com.vpn.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object ConfigNormalizer {

    fun normalizeConfig(config: JSONObject): JSONObject {
        normalizeDnsServers(config.optJSONObject("dns"))
        normalizeArrayObjects(config.optJSONArray("inbounds"), "inbound")
        normalizeArrayObjects(config.optJSONArray("outbounds"), "outbound")
        normalizeRoute(config.optJSONObject("route"))
        normalizeExperimental(config.optJSONObject("experimental"))
        return config
    }

    fun normalize(json: JSONObject): JSONObject = normalizeConfig(json)

    fun normalizeJson(json: String): String =
        JSONObject(json).let { normalizeConfig(it).toString(4) }

    private fun normalizeDnsServers(dns: JSONObject?) {
        val servers = dns?.optJSONArray("servers") ?: return
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            if (server.has("type")) continue

            val address = server.optString("address").trim()
            if (address.isEmpty()) continue

            when {
                address.equals("local", ignoreCase = true) ->
                    server.put("type", "local")

                address.startsWith("udp://", ignoreCase = true) ->
                    setUdpServer(server, address.substringAfter("://"))

                address.startsWith("tcp://", ignoreCase = true) ->
                    setHostServer(server, "tcp", address.substringAfter("://"))

                address.startsWith("tls://", ignoreCase = true) ->
                    setHostServer(server, "tls", address.substringAfter("://"))

                address.startsWith("quic://", ignoreCase = true) ->
                    setHostServer(server, "quic", address.substringAfter("://"))

                address.startsWith("h3://", ignoreCase = true) ->
                    setHostServer(server, "h3", address.substringAfter("://"))

                address.startsWith("https://", ignoreCase = true) -> {
                    val uri = runCatching { URI(address) }.getOrNull()
                    server.put("type", "https")
                    if (uri != null) {
                        server.put("server", uri.host)
                        val path = uri.rawPath
                        if (!path.isNullOrBlank() && path != "/") server.put("path", path)
                    } else {
                        server.put("server", address.substringAfter("://").substringBefore('/'))
                    }
                }

                address.startsWith("http://", ignoreCase = true) -> {
                    val uri = runCatching { URI(address) }.getOrNull()
                    server.put("type", "https")
                    if (uri != null) {
                        server.put("server", uri.host)
                        val path = uri.rawPath
                        if (!path.isNullOrBlank() && path != "/") server.put("path", path)
                    } else {
                        server.put("server", address.substringAfter("://").substringBefore('/'))
                    }
                }

                else -> setUdpServer(server, address)
            }
            server.remove("address")
        }
    }

    private fun setUdpServer(server: JSONObject, address: String) {
        val hostPort = address.substringBefore('/')
        val colon = hostPort.lastIndexOf(':')
        if (colon > 0 && hostPort.indexOf(':') == colon) {
            val port = hostPort.substring(colon + 1).toIntOrNull()
            if (port != null) {
                server.put("type", "udp")
                server.put("server", hostPort.substring(0, colon).trim('[', ']'))
                server.put("server_port", port)
                return
            }
        }
        server.put("type", "udp")
        server.put("server", hostPort.trim('[', ']'))
    }

    private fun setHostServer(server: JSONObject, type: String, address: String) {
        val uriAddress = if (address.contains("://")) address else "$type://$address"
        val uri = runCatching { URI(uriAddress) }.getOrNull()
        if (uri?.host != null) {
            server.put("type", type)
            server.put("server", uri.host)
            if (uri.port > 0) server.put("server_port", uri.port)
        } else {
            setUdpServer(server, address)
            if (type != "udp") server.put("type", type)
        }
    }

    private fun normalizeArrayObjects(array: JSONArray?, section: String) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (!item.has("type") && item.has("protocol")) {
                item.put("type", item.optString("protocol"))
            }

            if (section == "inbound") normalizeInbound(item)
            if (section == "outbound") normalizeOutbound(item)
        }
    }

    private fun normalizeInbound(inbound: JSONObject) {
        val inet4 = inbound.opt("inet4_address")
        val inet6 = inbound.opt("inet6_address")
        if (inet4 != null || inet6 != null) {
            val addresses = inbound.optJSONArray("address") ?: JSONArray()
            if (inet4 != null) appendAddress(addresses, inet4)
            if (inet6 != null) appendAddress(addresses, inet6)
            inbound.put("address", addresses)
            inbound.remove("inet4_address")
            inbound.remove("inet6_address")
        }

        if (inbound.has("listen_port") && inbound.opt("listen_port") is Number) {
            inbound.put("listen_port", inbound.optInt("listen_port"))
        }
    }

    private fun appendAddress(addresses: JSONArray, value: Any) {
        if (value is JSONArray) {
            for (i in 0 until value.length()) addresses.put(value.get(i))
        } else if (!addresses.toString().contains(value.toString())) {
            addresses.put(value)
        }
    }

    private fun normalizeOutbound(outbound: JSONObject) {
        val transport = outbound.optJSONObject("transport")
        if (transport == null) {
            val grpcServiceName = outbound.optString("grpc_service_name")
            val wsPath = outbound.optString("ws_path")
            val httpHost = outbound.optString("http_host")
            val httpPath = outbound.optString("http_path")
            if (outbound.has("grpc_service_name")) {
                outbound.put("transport", JSONObject()
                    .put("type", "grpc")
                    .put("service_name", grpcServiceName))
                outbound.remove("grpc_service_name")
            } else if (outbound.has("ws_path") || outbound.has("ws_headers")) {
                val ws = JSONObject().put("type", "ws")
                if (outbound.has("ws_path")) ws.put("path", wsPath)
                if (outbound.has("ws_headers")) ws.put("headers", outbound.opt("ws_headers"))
                outbound.put("transport", ws)
                outbound.remove("ws_path")
                outbound.remove("ws_headers")
            } else if (outbound.has("http_host") || outbound.has("http_path")) {
                val http = JSONObject().put("type", "http")
                if (outbound.has("http_host")) http.put("host", httpHost)
                if (outbound.has("http_path")) http.put("path", httpPath)
                outbound.put("transport", http)
                outbound.remove("http_host")
                outbound.remove("http_path")
            }
        }

        renameKey(outbound, "plugin", "plugin")
    }

    private fun normalizeRoute(route: JSONObject?) {
        if (route == null) return
        val rules = route.optJSONArray("rules") ?: return
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue

            renameKey(rule, "ip", "ip_cidr")
            renameKey(rule, "source_ip", "source_ip_cidr")
            renameKey(rule, "inbound_tag", "inbound")
            renameKey(rule, "outbound_tag", "outbound")

            if (rule.has("geoip")) {
                val geoip = rule.opt("geoip")
                appendRuleSet(rule, geoip, "geoip")
                rule.remove("geoip")
            }
            if (rule.has("geosite")) {
                val geosite = rule.opt("geosite")
                appendRuleSet(rule, geosite, "geosite")
                rule.remove("geosite")
            }
        }
    }

    private fun appendRuleSet(rule: JSONObject, value: Any, prefix: String) {
        val ruleSets = rule.optJSONArray("rule_set") ?: JSONArray()
        fun add(item: Any) {
            val name = item.toString().trim()
            if (name.isNotEmpty()) {
                val normalized = if (name.contains('/')) name else "$prefix-$name"
                ruleSets.put(normalized)
            }
        }
        if (value is JSONArray) for (i in 0 until value.length()) add(value.get(i))
        else add(value)
        rule.put("rule_set", ruleSets)
    }

    private fun normalizeExperimental(experimental: JSONObject?) {
        if (experimental == null) return
        val clashApi = experimental.optJSONObject("clash_api") ?: return
        val cache = experimental.optJSONObject("cache_file") ?: JSONObject()
        for (oldKey in listOf("store_selected", "store_fakeip", "cache_id")) {
            if (clashApi.has(oldKey)) {
                cache.put(oldKey, clashApi.opt(oldKey))
                clashApi.remove(oldKey)
            }
        }
        if (cache.length() > 0) experimental.put("cache_file", cache)
    }

    private fun renameKey(obj: JSONObject, oldKey: String, newKey: String) {
        if (obj.has(oldKey) && !obj.has(newKey)) {
            obj.put(newKey, obj.opt(oldKey))
            obj.remove(oldKey)
        }
    }
}
