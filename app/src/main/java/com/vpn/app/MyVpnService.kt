package com.vpn.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

class MyVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startVpnTunnel()
        return START_STICKY
    }

    private fun startVpnTunnel() {
        try {
            // بستن اتصال قبلی در صورت وجود
            vpnInterface?.close()

            val builder = Builder()
                .setSession("Dual VPN")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
            
            // ایجاد تونل سیستم‌عامل
            vpnInterface = builder.establish()
            
            if (vpnInterface == null) {
                Log.e("MyVpnService", "Failed to establish VPN. Check if another VPN is running.")
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
