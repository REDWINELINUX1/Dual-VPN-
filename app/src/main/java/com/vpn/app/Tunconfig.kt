package com.vpn.app

import org.json.JSONArray
import org.json.JSONObject

fun addTunAndDns(config: JSONObject) {
    val dnsServers = JSONArray()
        .put(
            JSONObject()
                .put("type", "udp")
                .put("tag", "remote")
                .put("server", "1.1.1.1")
                .put("server_port", 53)
                .put("detour", "select")
        )
    config.put(
        "dns",
        JSONObject()
            .put("servers", dnsServers)
            .put("final", "remote")
            .put("strategy", "ipv4_only")
    )

    val inbounds = config.optJSONArray("inbounds") ?: JSONArray().also {
        config.put("inbounds", it)
    }
    val tun = (0 until inbounds.length())
        .mapNotNull(inbounds::optJSONObject)
        .firstOrNull { it.optString("type") == "tun" }
        ?: JSONObject().also {
            it.put("type", "tun")
            it.put("tag", "tun-in")
            it.put("address", JSONArray().put("172.19.0.1/30"))
            inbounds.put(it)
        }
    if (!tun.has("address")) tun.put("address", JSONArray().put("172.19.0.1/30"))
    tun.put("auto_route", true)
    tun.put("strict_route", true)

    val route = config.optJSONObject("route") ?: JSONObject()
    val existingRules = route.optJSONArray("rules") ?: JSONArray()
    var hasSniffRule = false
    var hijackRule: JSONObject? = null
    var hijackRuleIndex = -1
    for (i in 0 until existingRules.length()) {
        val rule = existingRules.optJSONObject(i) ?: continue
        when (rule.optString("action")) {
            "sniff" -> hasSniffRule = true
            "hijack-dns" -> if (hijackRule == null) {
                hijackRule = rule
                hijackRuleIndex = i
            }
        }
    }
    val rules = JSONArray()
    if (!hasSniffRule) {
        rules.put(JSONObject().put("action", "sniff"))
    }
    rules.put(
        hijackRule
            ?.also { if (it.optInt("port", -1) != 53) it.put("port", 53) }
            ?: JSONObject().put("port", 53).put("action", "hijack-dns")
    )
    for (i in 0 until existingRules.length()) {
        if (i != hijackRuleIndex) rules.put(existingRules.get(i))
    }
    route.put("rules", rules)
    route.put("auto_detect_interface", true)
    config.put("route", route)
}
