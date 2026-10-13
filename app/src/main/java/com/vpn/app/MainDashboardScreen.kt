package com.vpn.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Is any VPN (from any app) active right now?
private fun isVpnActive(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

@Composable
fun MainDashboardScreen(s: Strings, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var statusOverride by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var waitingForVpn by remember { mutableStateOf(false) }
    val statusText = statusOverride ?: s.ready

    // After opening Windscribe, check every 1.5 seconds whether a VPN is connected (up to 3 minutes)
    LaunchedEffect(waitingForVpn) {
        if (waitingForVpn) {
            var connected = false
            var tries = 0
            while (!connected && tries < 120) {
                connected = isVpnActive(context)
                if (!connected) {
                    delay(1500)
                    tries++
                }
            }
            statusOverride = if (connected) s.vpnConnected else s.vpnNotDetected
            waitingForVpn = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(imageVector = Icons.Default.Settings, contentDescription = s.settings)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "VPN Manager Standalone",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(32.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = s.statusLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )

                        if (isLoading || waitingForVpn) {
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        isLoading = true
                        coroutineScope.launch {
                            val success = buildConfig(context) { message ->
                                statusOverride = message
                            }
                            isLoading = false
                            if (success) {
                                Toast.makeText(context, s.configDone, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = s.buildConfig,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = {
                        try {
                            val packageName = "com.windscribe.vpn"
                            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                            if (intent != null) {
                                context.startActivity(intent)
                                statusOverride = s.waitingVpn
                                waitingForVpn = true
                            } else {
                                Toast.makeText(context, s.windscribeMissing, Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                s.openError + ": " + e.message,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = s.openWindscribe,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
