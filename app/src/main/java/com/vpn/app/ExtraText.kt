package com.vpn.app

// Extra UI text kept outside Strings so that MainDashboardScreen.kt stays free of Persian text.

fun Strings.openNodesLabel(): String =
    if (this === StringsEn) "Connect with free nodes (sing-box)"
    else "اتصال با نودهای رایگان (sing-box)"

fun Strings.singBoxMissing(): String =
    if (this === StringsEn) "sing-box app is not installed!"
    else "اپلیکیشن sing-box نصب نیست!"

// Translates the Persian report lines produced by ConfigBuilder.kt into English.
// Lines that are not recognised are returned unchanged.
fun translateStatus(message: String): String {
    return message.lines().joinToString("\n") { translateLine(it) }
}

private val READING = Regex("^در حال خواندن (@\\S+) \\.\\.\\.$")
private val RESULT = Regex("^(@\\S+): (\\d+) لینک، (\\d+) نود قابل استفاده$")
private val CHANNEL_ERROR = Regex("^(@\\S+): خطا - (.*)$")
private val TOTAL = Regex("^مجموع نودها: (\\d+)$")
private val GENERIC_ERROR = Regex("^خطا: (.*)$")

private fun translateLine(line: String): String {
    READING.matchEntire(line)?.let { m ->
        return "Reading " + m.groupValues[1] + " ..."
    }
    RESULT.matchEntire(line)?.let { m ->
        return m.groupValues[1] + ": " + m.groupValues[2] + " links, " +
                m.groupValues[3] + " usable nodes"
    }
    CHANNEL_ERROR.matchEntire(line)?.let { m ->
        return m.groupValues[1] + ": error - " + m.groupValues[2]
    }
    TOTAL.matchEntire(line)?.let { m ->
        return "Total nodes: " + m.groupValues[1]
    }
    GENERIC_ERROR.matchEntire(line)?.let { m ->
        return "Error: " + m.groupValues[1]
    }
    return when {
        line.contains("هیچ نود") -> "No usable nodes found."
        line.contains("ویندسکرایب") && line.contains("فعال نیست") ->
            "Windscribe is not active on port 1080."
        line.contains("ویندسکرایب") && line.contains("فعال است") ->
            "Windscribe is active on port 1080."
        line.contains("dual-vpn-config.json") && line.contains("ذخیره شد") ->
            "dual-vpn-config.json saved to the Downloads folder."
        line.contains("انجام نشد") -> "Saving to Downloads failed."
        else -> line
    }
}
