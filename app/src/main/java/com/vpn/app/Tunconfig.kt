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

    val outbounds = config.getJSONArray("outbounds")
    outbounds.put(JSONObject().put("type", "dns").put("tag", "dns-out"))

    val rules = JSONArray()
        .put(JSONObject().put("protocol", "dns").put("outbound", "dns-out"))
        .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        .put(
            JSONObject()
                .put("domain_suffix", JSONArray().put(".ir"))
                .put("outbound", "direct")
        )
    config.getJSONObject("route").put("rules", rules)
}
