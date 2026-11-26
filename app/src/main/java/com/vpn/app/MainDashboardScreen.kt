package com.vpn.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@Composable
fun MainDashboardScreen(s: Strings, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var statusOverride by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var manualLink by remember { mutableStateOf("") }
    val statusText = statusOverride ?: s.ready

    DisposableEffect(context, s) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                statusOverride = when (intent?.getStringExtra(MyVpnService.EXTRA_STATUS)) {
                    MyVpnService.STATUS_CONNECTING -> s.waitingVpn
                    MyVpnService.STATUS_CONNECTED -> s.vpnConnected
                    MyVpnService.STATUS_FAILED -> s.vpnNotDetected
                    else -> statusOverride
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(MyVpnService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    fun startVpnService() {
        val serviceIntent = Intent(context, MyVpnService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
        statusOverride = s.waitingVpn
    }

    // راه‌انداز درخواست مجوز سیستم‌عامل
    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(context, "VPN Permission Denied", Toast.LENGTH_SHORT).show()
            statusOverride = "Permission Denied"
        }
    }

    fun triggerVpnConnection() {
        val intent = VpnService.prepare(context)
        if (intent != null) {
            vpnLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    // دیالوگ دریافت کانفیگ دستی
    if (showManualDialog) {
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text("Add Manual Config") },
            text = {
                Column {
                    Text("Paste your VLESS, VMess, Trojan, or SS link below:", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualLink,
                        onValueChange = { manualLink = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("vless://...") },
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (manualLink.isNotBlank()) {
                            isLoading = true
                            showManualDialog = false
                            coroutineScope.launch {
                                val success = addManualConfig(context, manualLink)
                                isLoading = false
                                if (success) {
                                    Toast.makeText(context, "Manual config applied!", Toast.LENGTH_SHORT).show()
                                    statusOverride = "Manual Config Active"
                                    triggerVpnConnection()
                                } else {
                                    Toast.makeText(context, "Invalid link format!", Toast.LENGTH_SHORT).show()
                                }
                                manualLink = ""
                            }
                        }
                    }
                ) {
                    Text("Save & Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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
                    text = "Dual VPN Manager",
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

                        if (isLoading) {
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        isLoading = true
                        coroutineScope.launch {
                            val success = buildConfig(context) { message ->
                                statusOverride = translateStatus(message)
                            }
                            isLoading = false
                            if (success) {
                                Toast.makeText(context, s.configDone, Toast.LENGTH_SHORT).show()
                                triggerVpnConnection()
                            }
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = s.buildConfig,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { showManualDialog = true },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "Add Manual Config",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
