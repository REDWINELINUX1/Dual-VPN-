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
                .put("detour", "direct")
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
                .put("address", JSONArray().put("10.0.0.2/24"))
                .put("mtu", 1500)
                .put("auto_route", false)
                .put("stack", "gvisor")
        )
    )

    val route = config.getJSONObject("route")
    val old = route.getJSONArray("rules")
    val rules = JSONArray()
    if (old.length() > 0) rules.put(old.getJSONObject(0))
    rules.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
    for (i in 1 until old.length()) rules.put(old.getJSONObject(i))
    route.put("rules", rules)
}
