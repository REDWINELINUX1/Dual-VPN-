package com.vpn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
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
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.AutoRedirectHandler
import io.nekohasekai.libbox.AutoRedirectSession
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import io.nekohasekai.libbox.NetworkInterface as LbNetworkInterface

class MyVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    private var box: CommandServer? = null
    @Volatile
    private var activeConfig: String? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var ifaceListener: InterfaceUpdateListener? = null
    private var lastIface: Pair<String, Int>? = Pair("?", -2)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundService()) {
            publishStatus(STATUS_FAILED)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        publishStatus(STATUS_CONNECTING)
        worker.execute { startBox() }
        return START_STICKY
    }

    private fun startForegroundService(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Dual VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    1,
                    buildForegroundNotification("Starting VPN"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    1,
                    buildForegroundNotification("Starting VPN"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(1, buildForegroundNotification("Starting VPN"))
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            val detail = throwableMessage(e)
            toast("Could not start VPN foreground service: $detail")
            publishStatus(STATUS_FAILED, detail)
            return false
        }
    }

    private fun startBox() {
        stopBox()
        try {
            listOf("geoip.db", "geosite.db").forEach { fileName ->
                val outFile = File(filesDir, fileName)
                if (!outFile.exists()) {
                    try {
                        assets.open(fileName).use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to copy $fileName", e)
                    }
                }
            }

            val configFile = File(filesDir, "config.json")
            if (!configFile.exists()) {
                val detail = "config.json not found"
                toast(detail)
                publishStatus(STATUS_FAILED, detail)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            val text = ConfigNormalizer.normalizeConfig(
                JSONObject(configFile.readText())
            ).toString(4)
            if (!libReady) {
                val options = SetupOptions().apply {
                    basePath = filesDir.path
                    workingPath = filesDir.path
                    tempPath = cacheDir.path
                }
                Libbox.setup(options)
                libReady = true
            }
            activeConfig = text
            val server = CommandServer(this, this)
            box = server
            server.start()
            server.startOrReloadService(text, OverrideOptions())
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildForegroundNotification("Connected"))
            publishStatus(STATUS_CONNECTED)
            toast("sing-box started")
        } catch (e: Throwable) {
            val detail = throwableMessage(e)
            Log.e(TAG, "start failed: $detail", e)
            toast("Error: $detail")
            publishStatus(STATUS_FAILED, detail)
            stopBox()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopBox() {
        val service = box
        box = null
        try {
            service?.closeService()
        } catch (e: Throwable) {
            Log.e(TAG, "close service failed", e)
        }
        try {
            service?.close()
        } catch (e: Throwable) {
            Log.e(TAG, "close command server failed", e)
        }
        activeConfig = null
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

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

        val autoRoute = options.getAutoRoute()
        val dnsMode = options.getDNSMode().getValue()
        if (dnsMode != Libbox.DNSModeDisabled && (autoRoute || dnsMode.isNotEmpty())) {
            val dnsServers = options.getDNSServerAddress()
            while (dnsServers.hasNext()) {
                val address = dnsServers.next()
                builder.addDnsServer(address)
                if (!autoRoute) {
                    builder.addRoute(address, if (address.contains(':')) 128 else 32)
                }
            }
        }

        if (autoRoute) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val route4 = options.getInet4RouteAddress()
                if (route4.hasNext()) {
                    while (route4.hasNext()) {
                        val address = route4.next()
                        builder.addRoute(address.address(), address.prefix())
                    }
                } else if (options.getInet4Address().hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }

                val route6 = options.getInet6RouteAddress()
                if (route6.hasNext()) {
                    while (route6.hasNext()) {
                        val address = route6.next()
                        builder.addRoute(address.address(), address.prefix())
                    }
                } else if (options.getInet6Address().hasNext()) {
                    builder.addRoute("::", 0)
                }

                addExcludedRoutes(builder, options.getInet4RouteExcludeAddress())
                addExcludedRoutes(builder, options.getInet6RouteExcludeAddress())
            } else {
                addRoutes(builder, options.getInet4RouteRange())
                addRoutes(builder, options.getInet6RouteRange())
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
            listener.updateDefaultInterface("", -1, false, false)
        } else {
            listener.updateDefaultInterface(current.first, current.second, false, false)
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

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
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
        val packages = packageManager.getPackagesForUid(uid)
            ?: throw Exception("android: package not found")
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull().orEmpty()
            setAndroidPackageNames(StringArray(packages.toList()))
        }
    }

    override fun includeAllNetworks(): Boolean = false

    override fun underNetworkExtension(): Boolean = false

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() {
    }

    override fun sendNotification(notification: Notification) {
        getSystemService(NotificationManager::class.java)?.notify(
            notification.typeID,
            NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(notification.title)
                .setContentText(notification.body)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()
        )
    }

    override fun cancelNotification(identifier: String, typeID: Int) {
        getSystemService(NotificationManager::class.java)?.cancel(typeID)
    }

    override fun startNeighborMonitor(listener: NeighborUpdateListener) {
        Log.w(TAG, "Neighbor monitoring is not supported on this platform")
    }

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) {
    }

    override fun registerMyInterface(name: String) {
    }

    override fun usePlatformAutoRedirect(): Boolean = false

    override fun createAutoRedirect(
        options: ByteArray,
        handler: AutoRedirectHandler
    ): AutoRedirectSession = throw UnsupportedOperationException("Auto redirect is not supported")

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions): BridgeSession =
        throw UnsupportedOperationException("Bridge mode is not supported")

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        throw UnsupportedOperationException("Platform shell is not supported")
    }

    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int
    ): ShellSession = throw UnsupportedOperationException("Shell sessions are not supported")

    override fun lookupSFTPServer(): String =
        throw UnsupportedOperationException("SFTP is not supported")

    override fun lookupUser(username: String): PlatformUser =
        throw UnsupportedOperationException("Platform users are not supported")

    override fun readSystemSSHHostKey(): String =
        throw UnsupportedOperationException("SSH is not supported")

    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun getSystemProxyStatus(): SystemProxyStatus =
        SystemProxyStatus().apply { available = false }

    override fun serviceReload() {
        try {
            val config = activeConfig ?: throw IllegalStateException("No active VPN configuration")
            val normalized = ConfigNormalizer.normalizeJson(config)
            activeConfig = normalized
            box?.startOrReloadService(normalized, OverrideOptions())
                ?: throw IllegalStateException("VPN command server is not running")
        } catch (e: Throwable) {
            val detail = throwableMessage(e)
            Log.e(TAG, "reload failed: $detail", e)
            publishStatus(STATUS_FAILED, detail)
            toast("Reload error: $detail")
            throw e
        }
    }

    override fun serviceStop() {
        publishStatus(STATUS_FAILED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun setSystemProxyEnabled(enabled: Boolean) {
        if (enabled) throw UnsupportedOperationException("System proxy mode is not supported")
    }

    override fun writeDebugMessage(message: String) {
        Log.d(TAG, message)
    }

    override fun connectSSHAgent(): Int =
        throw UnsupportedOperationException("SSH agent is not supported")

    override fun triggerNativeCrash() {
        throw UnsupportedOperationException("Native crash testing is not supported")
    }

    private fun addRoutes(builder: Builder, routes: RoutePrefixIterator) {
        while (routes.hasNext()) {
            val route = routes.next()
            builder.addRoute(route.address(), route.prefix())
        }
    }

    private fun addExcludedRoutes(builder: Builder, routes: RoutePrefixIterator) {
        while (routes.hasNext()) {
            val route = routes.next()
            builder.excludeRoute(IpPrefix(InetAddress.getByName(route.address()), route.prefix()))
        }
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
        private const val NOTIFICATION_CHANNEL_ID = "dual_vpn_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STATUS = "com.vpn.app.VPN_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ERROR = "error"
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_FAILED = "failed"

        @Volatile
        private var libReady = false
    }

    private fun throwableMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.toString()

    private fun publishStatus(status: String, error: String? = null) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, status)
                .apply {
                    if (!error.isNullOrBlank()) putExtra(EXTRA_ERROR, error)
                }
        )
    }

    private fun buildForegroundNotification(text: String) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Dual VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
}
