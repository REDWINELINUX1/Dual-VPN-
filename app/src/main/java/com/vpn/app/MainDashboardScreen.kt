package com.vpn.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

// آیا الان یک VPN (هر برنامه‌ای) فعال است؟
private fun isVpnActive(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

@Composable
fun MainDashboardScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf("آماده به کار - مستقل و بومی") }
    var isLoading by remember { mutableStateOf(false) }
    var waitingForVpn by remember { mutableStateOf(false) }

    // بعد از باز کردن ویندسکرایب، هر ۱.۵ ثانیه چک می‌کند VPN وصل شده یا نه (حداکثر ۳ دقیقه)
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
            statusText = if (connected) {
                "اتصال VPN برقرار شد"
            } else {
                "اتصال VPN تشخیص داده نشد"
            }
            waitingForVpn = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
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
                        text = "وضعیت سیستم:",
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
                            statusText = message
                        }
                        isLoading = false
                        if (success) {
                            Toast.makeText(
                                context,
                                "فایل تنظیمات با موفقیت ساخته شد!",
                                Toast.LENGTH_SHORT
                            ).show()
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
                    text = "بررسی و اعمال کانفیگ‌های رایگان",
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
                            statusText = "منتظر برقراری اتصال VPN..."
                            waitingForVpn = true
                        } else {
                            Toast.makeText(
                                context,
                                "اپلیکیشن ویندسکرایب نصب نیست!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "خطا در باز کردن برنامه: ${e.message}",
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
                    text = "اتصال به ویندسکرایب (Premium)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
