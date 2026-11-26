package com.vpn.app

import org.json.JSONArray
import org.json.JSONObject

fun addTunAndDns(config: JSONObject) {
    val dnsServers = JSONArray()
        .put(
            JSONObject()
                .put("tag", "remote")
                .put("address", "https://1.1.1.1/dns-query")
                .put("detour", "select")
        )
        .put(
            JSONObject()
                .put("tag", "local")
                .put("address", "8.8.8.8")
        )
    val dnsRules = JSONArray()
        .put(JSONObject().put("outbound", "any").put("server", "local"))
    config.put(
        "dns",
        JSONObject()
            .put("servers", dnsServers)
            .put("rules", dnsRules)
            .put("final", "remote")
    )

    config.put(
        "inbounds",
        JSONArray().put(
            JSONObject()
                .put("type", "tun")
                .put("tag", "tun-in")
                .put("address", JSONArray().put("172.19.0.1/30"))
                .put("mtu", 9000)
                .put("auto_route", true)
                .put("stack", "mixed")
                .put("sniff", true)
        )
    )

    val route = config.optJSONObject("route") ?: JSONObject()
    val existingRules = route.optJSONArray("rules") ?: JSONArray()
    val rules = JSONArray()
    var hasDnsHijackRule = false
    for (i in 0 until existingRules.length()) {
        if (existingRules.optJSONObject(i)?.optString("action") == "hijack-dns") {
            hasDnsHijackRule = true
        }
    }
    if (!hasDnsHijackRule) {
        rules.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
    }
    for (i in 0 until existingRules.length()) {
        rules.put(existingRules.get(i))
    }
    route.put("rules", rules)
    config.put("route", route)
}
