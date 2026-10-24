package com.vpn.app

import android.content.Intent
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.system.OsConstants
import android.util.Log
import android.widget.Toast
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import io.nekohasekai.libbox.NetworkInterface as LbNetworkInterface

class MyVpnService : VpnService(), PlatformInterface {

    private var box: BoxService? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var ifaceListener: InterfaceUpdateListener? = null
    private var lastIface: Pair<String, Int>? = Pair("?", -2)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        worker.execute { startBox() }
        return START_STICKY
    }

    private fun startBox() {
        stopBox()
        try {
            val configFile = File(filesDir, "config.json")
            if (!configFile.exists()) {
                toast("config.json not found")
                return
            }
            val text = configFile.readText()
            if (!libReady) {
                Libbox.setup(filesDir.path, filesDir.path, cacheDir.path, false(
                libReady = true
            }
            val service = Libbox.newService(text, this)
            service.start()
            box = service
            toast("sing-box started")
        } catch (e: Throwable) {
            Log.e(TAG, "start failed", e)
            toast("Error: ${e.message}")
            stopBox()
        }
    }

    private fun stopBox() {
        try {
            box?.close()
        } catch (e: Throwable) {
            Log.e(TAG, "close failed", e)
        }
        box = null
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onRevoke() {
        worker.execute { stopBox() }
        super.onRevoke()
    }

    override fun onDestroy() {
        stopBox()
        worker.shutdown()
        super.onDestroy()
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) throw Exception("android: missing vpn permission")

        val builder = Builder()
            .setSession("Dual VPN")
            .setMtu(options.getMTU())

        val inet4 = options.getInet4Address()
        while (inet4.hasNext()) {
            val a = inet4.next()
            builder.addAddress(a.address(), a.prefix())
        }
        val inet6 = options.getInet6Address()
        while (inet6.hasNext()) {
            val a = inet6.next()
            builder.addAddress(a.address(), a.prefix())
        }

        if (options.getAutoRoute()) {
            builder.addDnsServer(options.getDNSServerAddress())

            val route4 = options.getInet4RouteAddress()
            if (route4.hasNext()) {
                while (route4.hasNext()) {
                    val a = route4.next()
                    builder.addRoute(a.address(), a.prefix())
                }
            } else if (options.getInet4Address().hasNext()) {
                builder.addRoute("0.0.0.0", 0)
            }

            val route6 = options.getInet6RouteAddress()
            if (route6.hasNext()) {
                while (route6.hasNext()) {
                    val a = route6.next()
                    builder.addRoute(a.address(), a.prefix())
                }
            } else if (options.getInet6Address().hasNext()) {
                builder.addRoute("::", 0)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val ex4 = options.getInet4RouteExcludeAddress()
                while (ex4.hasNext()) {
                    val a = ex4.next()
                    builder.excludeRoute(IpPrefix(InetAddress.getByName(a.address()), a.prefix()))
                }
                val ex6 = options.getInet6RouteExcludeAddress()
                while (ex6.hasNext()) {
                    val a = ex6.next()
                    builder.excludeRoute(IpPrefix(InetAddress.getByName(a.address()), a.prefix()))
                }
            }

            val include = options.getIncludePackage()
            while (include.hasNext()) {
                try {
                    builder.addAllowedApplication(include.next())
                } catch (e: Exception) {
                    Log.w(TAG, "include package failed", e)
                }
            }
            val exclude = options.getExcludePackage()
            while (exclude.hasNext()) {
                try {
                    builder.addDisallowedApplication(exclude.next())
                } catch (e: Exception) {
                    Log.w(TAG, "exclude package failed", e)
                }
            }
        }

        val pfd = builder.establish()
            ?: throw Exception("android: the application is not prepared or is revoked")
        return pfd.detachFd()
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun usePlatformDefaultInterfaceMonitor(): Boolean = true

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        ifaceListener = listener
        lastIface = Pair("?", -2)
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                notifyDefaultInterface()
            }

            override fun onLost(network: Network) {
                notifyDefaultInterface()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                notifyDefaultInterface()
            }
        }
        netCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, callback)
        notifyDefaultInterface()
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cm = getSystemService(ConnectivityManager::class.java)
        netCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "unregister failed", e)
            }
        }
        netCallback = null
        ifaceListener = null
    }

    @Synchronized
    private fun notifyDefaultInterface() {
        val listener = ifaceListener ?: return
        val current = currentUnderlying()
        if (current == lastIface) return
        lastIface = current
        if (current == null) {
            listener.updateDefaultInterface("", -1)
        } else {
            listener.updateDefaultInterface(current.first, current.second)
        }
    }

    @Suppress("DEPRECATION")
    private fun currentUnderlying(): Pair<String, Int>? {
        val cm = getSystemService(ConnectivityManager::class.java)
        var best: Pair<String, Int>? = null
        var bestScore = -1
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val name = cm.getLinkProperties(network)?.interfaceName ?: continue
            val index = try {
                NetworkInterface.getByName(name)?.index ?: -1
            } catch (e: Exception) {
                -1
            }
            if (index < 0) continue
            var score = 0
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 2
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 1
            if (score > bestScore) {
                bestScore = score
                best = Pair(name, index)
            }
        }
        return best
    }

    override fun usePlatformInterfaceGetter(): Boolean = true

    override fun getInterfaces(): NetworkInterfaceIterator {
        val result = ArrayList<LbNetworkInterface>()
        val all = NetworkInterface.getNetworkInterfaces()
        if (all != null) {
            for (ni in all) {
                val item = LbNetworkInterface()
                item.setIndex(ni.index)
                item.setMTU(ni.mtu)
                item.setName(ni.name)
                val addresses = ni.interfaceAddresses.map {
                    (it.address.hostAddress ?: "").substringBefore('%') + "/" + it.networkPrefixLength
                }
                item.setAddresses(StringArray(addresses))
                var flags = 0
                if (ni.isUp) flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                if (ni.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
                if (ni.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
                if (ni.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
                item.setFlags(flags)
                result.add(item)
            }
        }
        return InterfaceArray(result)
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw Exception("android: connection owner lookup needs Android 10")
        }
        val cm = getSystemService(ConnectivityManager::class.java)
        val uid = cm.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort)
        )
        if (uid == Process.INVALID_UID) throw Exception("android: connection owner not found")
        return uid
    }

    override fun packageNameByUid(uid: Int): String {
        return packageManager.getPackagesForUid(uid)?.firstOrNull()
            ?: throw Exception("android: package not found")
    }

    override fun uidByPackageName(packageName: String): Int {
        return try {
            packageManager.getPackageUid(packageName, 0)
        } catch (e: Exception) {
            throw Exception("android: package not found")
        }
    }

    override fun includeAllNetworks(): Boolean = false

    override fun underNetworkExtension(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() {
    }

    override fun sendNotification(notification: Notification) {
    }

    override fun writeLog(message: String) {
        Log.i("sing-box", message)
    }

    private class StringArray(private val items: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < items.size
        override fun len(): Int = items.size
        override fun next(): String = items[index++]
    }

    private class InterfaceArray(private val items: List<LbNetworkInterface>) : NetworkInterfaceIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < items.size
        override fun next(): LbNetworkInterface = items[index++]
    }

    companion object {
        private const val TAG = "DualVPN"

        @Volatile
        private var libReady = false
    }
}
