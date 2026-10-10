package com.vpn.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

class DualVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        // TODO: start sing-box and establish the tunnel here
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun stopVpn() {
        tun?.close()
        tun = null
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.vpn.app.STOP"
    }
}
