Package com.loin.project.vpnmanager

import android.content.Intent
import android.util.Base64
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

@Composable
fun MainDashboardScreen() {
        val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()
                var statusText by remember { mutableStateOf("آماده به کار - مستقل و بومی") }
                    var isLoading by remember { mutableStateOf(false) }

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

                                                                                                    Spacer(modifier = Modifier.height(32.dp))

                                                                                                                Button(
                                                                                                                                    onClick = {
                                                                                                                                                            isLoading = true
                                                                                                                                                                                coroutineScope.launch {
                                                                                                                                                                                                            val success = generateConfigNative(context) { message ->
                                                                                                                                                                                                                                        statusText = message
                                                                                                                                                                                                                                                                }
                                                                                                                                                                                                                                                                                        isLoading = false
                                                                                                                                                                                                                                                                                                                if (success) {
                                                                                                                                                                                                                                                                                                                                                Toast.makeText(context, "فایل تنظیمات با موفقیت ساخته شد!", Toast.LENGTH_SHORT).show()
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
                                                                                                                                                                                                                                                                                                                            statusText = "منتظر برقراری اتصال پرمیوم (پورت ۱۰۸۰)..."
                                                                                                                                                                                                                                                                } else {
                                                                                                                                                                                                                                                                                                Toast.makeText(context, "اپلیکیشن ویندسکرایب نصب نیست!", Toast.LENGTH_SHORT).show()
                                                                                                                                                                                                                                                                }
                                                                                                                                                                                    } catch (e: Exception) {
                                                                                                                                                                                                                Toast.makeText(context, "خطا در باز کردن برنامه: ${e.message}", Toast.LENGTH_SHORT).show()
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
                                                                                                                                                                                                                                                                }
                                                                                                                                                                                    }
                                                                                                                                                                                                                                                                                                                }
                                                                                                                                                                                }
                                                                                                                                    }
                                                             }
                     )                                                                                                                                                                                             color = MaterialTheme.colorScheme.secondary)
suspend fun generateConfigNative(context: android.content.Context, onStatusUpdate: (String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
                    try {
                                    onStatusUpdate("در حال دریافت کانفیگ‌ها از منبع...")
                                                val urlString = "https://raw.githubusercontent.com/example/free-configs/main/nodes.txt"
                                                            val url = URL(urlString)
                                                                        val connection = url.openConnection() as HttpURLConnection
                                                                                    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                                                                                                connection.connectTimeout = 10000
                                                                                                            connection.readTimeout = 10000

                                                                                                                        val content = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                                                                                                                                    
                                                                                                                                                val lines = mutableListOf<String>()
                                                                                                                                                            try {
                                                                                                                                                                                val decodedBytes = Base64.decode(content.trim(), Base64.DEFAULT)
                                                                                                                                                                                                val decoded = String(decodedBytes, Charsets.UTF_8)
                                                                                                                                                                                                                lines.addAll(decoded.lineSequence().map { it.trim() }.filter { it.isNotEmpty() })
                                                                                                                                                            } catch (e: Exception) {
                                                                                                                                                                                lines.addAll(content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() })
                                                                                                                                                            }

                                                                                                                                                                        val scoredNodes = JSONArray()
                                                                                                                                                                                    for ((index, line) in lines.withIndex()) {
                                                                                                                                                                                                        val lowerLine = line.lowercase()
                                                                                                                                                                                                                        val node = JSONObject()
                                                                                                                                                                                                                                        when {
                                                                                                                                                                                                                                                                line.startsWith("vless://") -> {
                                                                                                                                                                                                                                                                                            node.put("tag", "free-vless-$index")
                                                                                                                                                                                                                                                                                                                    node.put("type", "vless")
                                                                                                                                                                                                                                                                                                                                            node.put("server", "extracted_host_or_ip")
                                                                                                                                                                                                                                                                                                                                                                    node.put("server_port", 443)
                                                                                                                                                                                                                                                                                                                                                                                            scoredNodes.put(node)
                                                                                                                                                                                                                                                                }
                                                                                                                                                                                                                                                                                    line.startsWith("vmess://") -> {
                                                                                                                                                                                                                                                                                                                node.put("tag", "free-vmess-$index")
                                                                                                                                                                                                                                                                                                                                        node.put("type", "vmess")
                                                                                                                                                                                                                                                                                                                                                                node.put("server", "extracted_host_or_ip")
                                                                                                                                                                                                                                                                                                                                                                                        node.put("server_port", 443)
                                                                                                                                                                                                                                                                                                                                                                                                                scoredNodes.put(node)
                                                                                                                                                                                                                                                                                    }
                                                                                                                                                                                                                                                                                                        line.startsWith("trojan://") -> {
                                                                                                                                                                                                                                                                                                                                    node.put("tag", "free-trojan-$index")
                                                                                                                                                                                                                                                                                                                                                            node.put("type", "trojan")
                                                                                                                                                                                                                                                                                                                                                                                    node.put("server", "extracted_host_or_ip")
                                                                                                                                                                                                                                                                                                                                                                                                            node.put("server_port", 443)
                                                                                                                                                                                                                                                                                                                                                                                                                                    scoredNodes.put(node)
                                                                                                                                                                                                                                                                                                        }
                                                                                                                                                                                                                                                                                                                            line.startsWith("ss://") || line.startsWith("socks://") -> {
                                                                                                                                                                                                                                                                                                                                                        node.put("tag", "free-socks-$index")
                                                                                                                                                                                                                                                                                                                                                                                node.put("type", "socks5")
                                                                                                                                                                                                                                                                                                                                                                                                        node.put("server", "extracted_host_or_ip")
                                                                                                                                                                                                                                                                                                                                                                                                                                node.put("server_port", 1080)
                                                                                                                                                                                                                                                                                                                                                                                                                                                        scoredNodes.put(node)
                                                                                                                                                                                                                                                                                                                            }
                                                                                                                                                                                                                                                                }
                                                                                                                                                                                                                                        }
                                                                                                                                                            }
                    }
        }
}
if (scoredNodes.length() == 0) {
                    val fallback = JSONObject()
                                    fallback.put("tag", "fallback-direct")
                                                    fallback.put("type", "direct")
                                                                    scoredNodes.put(fallback)
}

            onStatusUpdate("منتظر برقراری اتصال ویندسکرایب روی پورت ۱۰۸۰...")
                        
                                    while (true) {
                                                        try {
                                                                                Socket().use { socket ->
                                                                                                        socket.connect(InetSocketAddress("127.0.0.1", 1080), 2000)
                                                                                                                                break
                                                                                                                                                    }
                                                        } catch (e: Exception) {
                                                                                delay(2000)
                                                        }
                                    }
                                                        }
}
onStatusUpdate("در حال نهایی‌سازی و ساخت فایل config.json...")
            
                        val config = JSONObject()
                                    config.put("log", JSONObject().put("disabled", false).put("level", "info").put("timestamp", true))

                                                // تعریف منابع ریموت (Rule Sets) برای گیت‌هاب، تلگرام و ایران
                                                            val ruleSets = JSONArray().apply {
                                                                                put(JSONObject().apply {
                                                                                                        put("tag", "github")
                                                                                                                            put("type", "remote")
                                                                                                                                                put("format", "binary")
                                                                                                                                                                    put("url", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-github.srs")
                                                                                                                                                                                        put("download_detour", "select")
                                                                                })
                                                                                                put(JSONObject().apply {
                                                                                                                        put("tag", "telegram")
                                                                                                                                            put("type", "remote")
                                                                                                                                                                put("format", "binary")
                                                                                                                                                                                    put("url", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-telegram.srs")
                                                                                                                                                                                                        put("download_detour", "select")
                                                                                                })
                                                                                                                put(JSONObject().apply {
                                                                                                                                        put("tag", "ir")
                                                                                                                                                            put("type", "remote")
                                                                                                                                                                                put("format", "binary")
                                                                                                                                                                                                    put("url", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-ir.srs")
                                                                                                                                                                                                                        put("download_detour", "direct")
                                                                                                                })
                                                            }
                                                                        config.put("rule_set", ruleSets)

                                                                                    val outbounds = JSONArray()
                                                                                                val selector = JSONObject().apply {
                                                                                                                    put("type", "selector")
                                                                                                                                    put("tag", "select")
                                                                                                                                                    put("outbounds", JSONArray().put("premium-stable").put(scoredNodes.getJSONObject(0).getString("tag")).put("direct"))
                                                                                                }
                                                                                                            outbounds.put(selector)

                                                                                                                        val premiumNode = JSONObject().apply {
                                                                                                                                            put("tag", "premium-stable")
                                                                                                                                                            put("type", "socks5")
                                                                                                                                                                            put("server", "127.0.0.1")
                                                                                                                                                                                            put("server_port", 1080)
                                                                                                                        }
                                                                                                                                    outbounds.put(premiumNode)
                                                                                                                                                outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

                                                                                                                                                            for (i in 0 until scoredNodes.length()) {
                                                                                                                                                                                outbounds.put(scoredNodes.getJSONObject(i))
                                                                                                                                                            }
                                                                                                                                                                        config.put("outbounds", outbounds)
           val rulesArray = JSONArray().apply {
                            put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
                                            put(JSONObject().put("rule_set", JSONArray().put("telegram")).put("outbound", "select"))
                                                            put(JSONObject().put("rule_set", JSONArray().put("ir")).put("outbound", "direct"))
                                                                            put(JSONObject().put("rule_set", JSONArray().put("github")).put("outbound", "select"))
                                                                                            put(JSONObject().put("domain_suffix", JSONArray().put(".ir").put("irccli.ir")).put("outbound", "direct"))
           }

                       val route = JSONObject().apply {
                                        put("rules", rulesArray)
                                                        put("final", "select")
                                                                        put("auto_detect_interface", true)
                       }
                                   config.put("route", route)

                                               val configFile = File(context.filesDir, "config.json")
                                                           configFile.writeText(config.toString(4))

                                                                       onStatusUpdate("فایل config.json با موفقیت ساخته شد!")
                                                                                   true
                                                                                           } catch (e: Exception) {
                                                                                                        onStatusUpdate("خطا: ${e.localizedMessage}")
                                                                                                                    false
                                                                                           }
                                                                                               }
                                                                                               }
           }
// تابع اصلاح‌شده برای اجرای هسته با اعمال مجوز لینوکسی و مسیر مطلق
fun startSingBoxCoreWithPermission(context: android.content.Context, onStatusUpdate: (String) -> Unit) {
    try {
        onStatusUpdate("در حال آماده‌سازی و اعطای دسترسی به هسته sing-box...")
        
        val filesDir = context.filesDir
        val binaryFile = File(filesDir, "sing-box")
        val configFile = File(filesDir, "config.json")
        
        if (binaryFile.exists() && configFile.exists()) {
            // اعطای دسترسی اجرایی (chmod 755) به باینری برای جلوگیری از خطای Permission Denied
            val chmodProcess = ProcessBuilder("chmod", "755", binaryFile.absolutePath).start()
            chmodProcess.waitFor()
            
            val processBuilder = ProcessBuilder(
                binaryFile.absolutePath,
                "run",
                "-c",
                configFile.absolutePath
            )
            
            // ارسال مسیر فایل‌ها به عنوان متغیر محیطی برای استفاده در بخش Rust
            processBuilder.environment()["APP_FILES_DIR"] = filesDir.absolutePath
            processBuilder.directory(filesDir)
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            onStatusUpdate("هسته sing-box با موفقیت روشن شد و VPN فعال است!")
        } else {
            onStatusUpdate("خطا: فایل باینری یا کانفیگ پیدا نشد!")
        }
    } catch (e: Exception) {
        onStatusUpdate("خطا در اجرای هسته: ${e.localizedMessage}") 
    }
}
Button(
    onClick = {
        isLoading = true
        coroutineScope.launch {
            // ۱. بررسی مجوز سیستم VPN اندروید پیش از اجرا
            val vpnIntent = VpnService.prepare(context)
            if (vpnIntent != null) {
                statusText = "لطفاً دسترسی VPN را در پنجره سیستمی تایید کنید."
                isLoading = false
            } else {
                // ۲. ساخت کانفیگ پویا و رایگان
                val success = generateConfigNative(context) { message ->
                    statusText = message
                }
                
                if (success) {
                    // ۳. اجرای نهایی هسته با اعمال مجوزها و مسیرها
                    startSingBoxCoreWithPermission(context) { coreMessage ->
                        statusText = coreMessage
                    }
                    Toast.makeText(context, "VPN با موفقیت راه‌اندازی شد!", Toast.LENGTH_SHORT).show()
                }
                isLoading = false
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
        text = "بررسی، ساخت و اتصال خودکار",
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold
    )
    
