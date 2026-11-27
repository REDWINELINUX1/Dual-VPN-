package com.vpn.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object ConfigNormalizer {

    fun normalizeConfig(config: JSONObject): JSONObject {
        ensureLogging(config)
        normalizeDnsServers(config.optJSONObject("dns"))
        val inboundRules = normalizeArrayObjects(config.optJSONArray("inbounds"), "inbound")
        normalizeArrayObjects(config.optJSONArray("outbounds"), "outbound")
        ensureTun(config)
        val route = config.optJSONObject("route") ?: JSONObject()
        normalizeRoute(route)
        ensureProxyRoute(config, route)
        ensureDns(config, route)
        ensureDnsHijackRule(route)
        ensureUdp443RejectRule(route)
        route.put("auto_detect_interface", true)
        config.put("route", route)
        prependInboundRules(config, inboundRules)
        normalizeExperimental(config.optJSONObject("experimental"))
        return config
    }

    private fun ensureLogging(config: JSONObject) {
        val log = config.optJSONObject("log") ?: JSONObject()
        log.put("disabled", false)
        if (log.optString("level").isBlank()) log.put("level", "info")
        if (!log.has("timestamp")) log.put("timestamp", true)
        config.put("log", log)
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

    private fun ensureTun(config: JSONObject) {
        val inbounds = config.optJSONArray("inbounds") ?: JSONArray().also {
            config.put("inbounds", it)
        }
        val tun = (0 until inbounds.length())
            .mapNotNull(inbounds::optJSONObject)
            .firstOrNull { it.optString("type") == "tun" }
            ?: JSONObject()
                .put("type", "tun")
                .put("tag", "tun-in")
                .put("address", JSONArray().put("172.19.0.1/30"))
                .also(inbounds::put)
        if (!tun.has("address")) tun.put("address", JSONArray().put("172.19.0.1/30"))
        tun.put("auto_route", true)
        tun.put("strict_route", true)
    }

    private fun ensureProxyRoute(config: JSONObject, route: JSONObject) {
        val outbounds = config.optJSONArray("outbounds") ?: JSONArray().also {
            config.put("outbounds", it)
        }
        val tags = mutableSetOf<String>()
        val proxyTags = mutableListOf<String>()
        val nonProxyTags = mutableSetOf<String>()

        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            val type = outbound.optString("type")
            if (type.isBlank()) continue
            var tag = outbound.optString("tag").trim()
            if (tag.isEmpty()) {
                tag = "outbound-$i"
                outbound.put("tag", tag)
            }
            require(tags.add(tag)) { "Duplicate outbound tag: $tag" }
            if (type !in setOf("direct", "block", "dns", "selector", "urltest", "url")) {
                proxyTags.add(tag)
            } else if (type in setOf("direct", "block", "dns")) {
                nonProxyTags.add(tag)
            }
        }

        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            if (outbound.optString("type") in setOf("selector", "urltest")) {
                normalizeGroup(outbound, tags, proxyTags, nonProxyTags)
            }
        }

        if (proxyTags.isEmpty()) return

        fun canProxy(tag: String, visiting: MutableSet<String> = mutableSetOf()): Boolean {
            if (tag in proxyTags) return true
            if (!visiting.add(tag)) return false
            val group = (0 until outbounds.length())
                .asSequence()
                .mapNotNull(outbounds::optJSONObject)
                .firstOrNull { it.optString("tag") == tag } ?: return false
            if (group.optString("type") !in setOf("selector", "urltest")) return false
            val members = group.optJSONArray("outbounds") ?: return false
            return (0 until members.length()).any {
                canProxy(members.optString(it), visiting)
            }
        }

        val currentFinal = route.optString("final")
        if (currentFinal.isNotBlank() && canProxy(currentFinal)) return

        val groups = (0 until outbounds.length())
            .mapNotNull(outbounds::optJSONObject)
            .filter { it.optString("type") in setOf("selector", "urltest") }
        val preferredGroup = groups.firstOrNull {
            it.optString("tag") == currentFinal && canProxy(currentFinal)
        } ?: groups.firstOrNull {
            it.optString("type") == "selector" && canProxy(it.optString("tag"))
        } ?: groups.firstOrNull {
            it.optString("type") == "urltest" && canProxy(it.optString("tag"))
        }

        if (preferredGroup != null) {
            route.put("final", preferredGroup.optString("tag"))
        } else if (proxyTags.size == 1) {
            route.put("final", proxyTags.first())
        } else {
            var groupTag = "auto-select"
            var suffix = 1
            while (groupTag in tags) groupTag = "auto-select-${suffix++}"
            val members = JSONArray()
            proxyTags.forEach(members::put)
            outbounds.put(
                JSONObject()
                    .put("type", "urltest")
                    .put("tag", groupTag)
                    .put("outbounds", members)
                    .put("url", "https://www.gstatic.com/generate_204")
                    .put("interval", "3m")
                    .put("tolerance", 50)
            )
            route.put("final", groupTag)
        }
    }

    private fun normalizeGroup(
        group: JSONObject,
        existingTags: Set<String>,
        proxyTags: List<String>,
        nonProxyTags: Set<String>
    ) {
        val members = group.optJSONArray("outbounds") ?: JSONArray()
        val validMembers = JSONArray()
        val seen = mutableSetOf<String>()
        for (i in 0 until members.length()) {
            val tag = members.optString(i)
            if (tag in existingTags && seen.add(tag)) validMembers.put(tag)
        }
        val hasProxyMember = (0 until validMembers.length())
            .any { validMembers.optString(it) in proxyTags }
        if (!hasProxyMember) proxyTags.forEach(validMembers::put)
        group.put("outbounds", validMembers)

        if (group.optString("type") == "selector") {
            val default = group.optString("default")
            val defaultIsMember = (0 until validMembers.length())
                .any { validMembers.optString(it) == default }
            if (!defaultIsMember || default in nonProxyTags) {
                proxyTags.firstOrNull()?.let { group.put("default", it) }
            }
        }
    }

    private fun ensureDns(config: JSONObject, route: JSONObject) {
        val dns = config.optJSONObject("dns") ?: JSONObject().also {
            config.put("dns", it)
        }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also {
            dns.put("servers", it)
        }
        if (servers.length() == 0) {
            servers.put(
                JSONObject()
                    .put("type", "udp")
                    .put("tag", "remote-dns")
                    .put("server", "1.1.1.1")
                    .put("server_port", 53)
            )
        }
        if (!dns.has("strategy")) dns.put("strategy", "ipv4_only")

        val serverTags = mutableSetOf<String>()
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            if (!server.has("tag")) server.put("tag", "dns-$i")
            serverTags.add(server.optString("tag"))
        }
        if (serverTags.isEmpty()) {
            throw IllegalArgumentException("DNS configuration has no valid servers")
        }
        if (dns.optString("final") !in serverTags) {
            dns.put("final", serverTags.first())
        }

        val outbounds = config.optJSONArray("outbounds") ?: return
        val outboundTypesByTag = (0 until outbounds.length())
            .mapNotNull(outbounds::optJSONObject)
            .associate { it.optString("tag") to it.optString("type") }
        val proxyFinal = route.optString("final")
            .takeIf { tag ->
                tag.isNotBlank() &&
                    outboundTypesByTag[tag]?.let {
                        it !in setOf("direct", "block", "dns", "url")
                    } == true
            }
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val detour = server.optString("detour")
            if (server.optString("type") == "local") continue

            val detourType = outboundTypesByTag[detour]
            if (detour.isBlank() || detourType == null) {
                if (proxyFinal != null) server.put("detour", proxyFinal)
                else server.remove("detour")
            }
        }
    }

    private fun ensureDnsHijackRule(route: JSONObject) {
        val rules = route.optJSONArray("rules") ?: JSONArray().also {
            route.put("rules", it)
        }
        var hijackRule: JSONObject? = null
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rule.optString("action") == "hijack-dns") {
                hijackRule = rule
                break
            }
        }
        if (hijackRule == null) {
            rules.put(0, JSONObject().put("port", 53).put("action", "hijack-dns"))
        } else if (hijackRule.optInt("port", -1) != 53) {
            hijackRule.put("port", 53)
        }
    }

    private fun ensureUdp443RejectRule(route: JSONObject) {
        val rules = route.optJSONArray("rules") ?: JSONArray().also {
            route.put("rules", it)
        }
        val retainedRules = JSONArray()
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i)
            if (rule == null) {
                retainedRules.put(rules.get(i))
                continue
            }
            val isUdp443Reject = rule.optString("network") == "udp" &&
                rule.optInt("port", -1) == 443 &&
                rule.optString("action") == "reject"
            if (!isUdp443Reject) retainedRules.put(rule)
        }

        var insertAt = 0
        while (insertAt < retainedRules.length()) {
            val action = retainedRules.optJSONObject(insertAt)?.optString("action")
            if (action !in setOf("sniff", "hijack-dns")) break
            insertAt++
        }

        val normalizedRules = JSONArray()
        for (i in 0 until retainedRules.length()) {
            if (i == insertAt) {
                normalizedRules.put(
                    JSONObject()
                        .put("network", "udp")
                        .put("port", 443)
                        .put("action", "reject")
                )
            }
            normalizedRules.put(retainedRules.get(i))
        }
        if (insertAt == retainedRules.length()) {
            normalizedRules.put(
                JSONObject()
                    .put("network", "udp")
                    .put("port", 443)
                    .put("action", "reject")
            )
        }
        route.put("rules", normalizedRules)
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

    private fun normalizeArrayObjects(array: JSONArray?, section: String): JSONArray {
        val generatedRules = JSONArray()
        if (array == null) return generatedRules

        val usedInboundTags = mutableSetOf<String>()
        if (section == "inbound") {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.optString("tag")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(usedInboundTags::add)
            }
        }

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (!item.has("type") && item.has("protocol")) {
                item.put("type", item.optString("protocol"))
            }

            if (section == "inbound") {
                generatedRules.putAll(normalizeInbound(item, i, usedInboundTags))
            }
            if (section == "outbound") normalizeOutbound(item)
        }
        return generatedRules
    }

    private fun normalizeInbound(
        inbound: JSONObject,
        index: Int,
        usedTags: MutableSet<String>
    ): JSONArray {
        val generatedRules = JSONArray()
        val type = inbound.optString("type")
        if (type == "tun") {
            inbound.remove("stack")
            mergeLegacyAddressFields(
                inbound,
                "address",
                listOf("inet4_address", "inet6_address")
            )
            mergeLegacyAddressFields(
                inbound,
                "route_address",
                listOf("inet4_route_address", "inet6_route_address")
            )
            mergeLegacyAddressFields(
                inbound,
                "route_exclude_address",
                listOf("inet4_route_exclude_address", "inet6_route_exclude_address")
            )
            inbound.remove("gso")
            inbound.remove("endpoint_independent_nat")
        }

        val sniff = inbound.optBoolean("sniff", false)
        val sniffOverride = inbound.optBoolean("sniff_override_destination", false)
        val sniffTimeout = (inbound.opt("sniff_timeout") as? String)
            ?.takeIf { it.isNotBlank() }
        val domainStrategy = (inbound.opt("domain_strategy") as? String)
            ?.takeIf { it.isNotBlank() }
        val udpDisableDomainUnmapping = inbound.optBoolean("udp_disable_domain_unmapping", false)

        inbound.remove("sniff")
        inbound.remove("sniff_override_destination")
        inbound.remove("sniff_timeout")
        inbound.remove("domain_strategy")
        inbound.remove("udp_disable_domain_unmapping")

        val hasLegacyOptions = sniff || sniffOverride ||
            domainStrategy != null ||
            udpDisableDomainUnmapping
        if (hasLegacyOptions) {
            val tag = inbound.optString("tag").takeIf { it.isNotBlank() }
                ?: createInboundTag(inbound, index, usedTags)

            if (domainStrategy != null) {
                generatedRules.put(
                    JSONObject()
                        .put("inbound", tag)
                        .put("action", "resolve")
                        .put("strategy", domainStrategy)
                )
            }
            if (sniff || sniffOverride) {
                val sniffRule = JSONObject()
                    .put("inbound", tag)
                    .put("action", "sniff")
                if (sniffTimeout != null) {
                    sniffRule.put("timeout", sniffTimeout)
                }
                generatedRules.put(sniffRule)
            }
            if (udpDisableDomainUnmapping) {
                generatedRules.put(
                    JSONObject()
                        .put("inbound", tag)
                        .put("action", "route-options")
                        .put("udp_disable_domain_unmapping", true)
                )
            }
        }

        if (inbound.has("listen_port") && inbound.opt("listen_port") is Number) {
            inbound.put("listen_port", inbound.optInt("listen_port"))
        }
        return generatedRules
    }

    private fun createInboundTag(
        inbound: JSONObject,
        index: Int,
        usedTags: MutableSet<String>
    ): String {
        var tag = "inbound-$index"
        var suffix = 1
        while (!usedTags.add(tag)) {
            tag = "inbound-$index-${suffix++}"
        }
        inbound.put("tag", tag)
        return tag
    }

    private fun mergeLegacyAddressFields(
        inbound: JSONObject,
        currentKey: String,
        legacyKeys: List<String>
    ) {
        val addresses = JSONArray()
        inbound.optJSONArray(currentKey)?.let { appendAddress(addresses, it) }
            ?: inbound.opt(currentKey)?.let { appendAddress(addresses, it) }
        var foundLegacyField = false
        for (legacyKey in legacyKeys) {
            val value = inbound.opt(legacyKey) ?: continue
            foundLegacyField = true
            appendAddress(addresses, value)
            inbound.remove(legacyKey)
        }
        if (foundLegacyField) inbound.put(currentKey, addresses)
    }

    private fun appendAddress(addresses: JSONArray, value: Any) {
        if (value is JSONArray) {
            for (i in 0 until value.length()) appendAddress(addresses, value.get(i))
        } else if (value != JSONObject.NULL && (0 until addresses.length())
                .none { addresses.opt(it) == value }) {
            addresses.put(value)
        }
    }

    private fun JSONArray.putAll(values: JSONArray) {
        for (i in 0 until values.length()) put(values.get(i))
    }

    private fun prependInboundRules(config: JSONObject, inboundRules: JSONArray) {
        if (inboundRules.length() == 0) return
        val route = config.optJSONObject("route") ?: JSONObject()
        val existingRules = route.optJSONArray("rules") ?: JSONArray()
        val rules = JSONArray()
        rules.putAll(inboundRules)
        rules.putAll(existingRules)
        route.put("rules", rules)
        config.put("route", route)
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

    private fun normalizeRoute(route: JSONObject) {
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
