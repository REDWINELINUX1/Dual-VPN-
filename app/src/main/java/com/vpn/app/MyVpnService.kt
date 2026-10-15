package com.vpn.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.File

class MyVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startVpnTunnel()
        return START_STICKY
    }

    private fun startVpnTunnel() {
        try {
            vpnInterface?.close()
            val builder = Builder()
                .setSession("Dual VPN")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
            
            // ایجاد تونل سیستم‌عامل برای نمایش آیکون[span_2](start_span)[span_2](end_span)
            vpnInterface = builder.establish()

            // بررسی وجود فایل کانفیگ ساخته شده[span_3](start_span)[span_3](end_span)
            val configFile = File(filesDir, "config.json")
            if (configFile.exists() && vpnInterface != null) {
                val fd = vpnInterface!!.fileDescriptor
                // در این قسمت باید متد اجرایی کتابخانه sing-box برای پردازش فایل کانفیگ و fd فراخوانی شود
                // مثال: LibBox.run(configFile.absolutePath, fd)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
