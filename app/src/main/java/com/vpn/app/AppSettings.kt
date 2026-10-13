package com.vpn.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat

const val THEME_SYSTEM = 0
const val THEME_LIGHT = 1
const val THEME_DARK = 2

// ذخیره‌ی تنظیمات روی گوشی
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var themeMode: Int
        get() = prefs.getInt("theme", THEME_SYSTEM)
        set(v) = prefs.edit().putInt("theme", v).apply()

    var language: String
        get() = prefs.getString("lang", "fa") ?: "fa"
        set(v) = prefs.edit().putString("lang", v).apply()

    var notificationAsked: Boolean
        get() = prefs.getBoolean("notif_asked", false)
        set(v) = prefs.edit().putBoolean("notif_asked", v).apply()
}

// متن‌های برنامه به دو زبان
class Strings(
    val ready: String,
    val statusLabel: String,
    val buildConfig: String,
    val openWindscribe: String,
    val waitingVpn: String,
    val vpnConnected: String,
    val vpnNotDetected: String,
    val windscribeMissing: String,
    val openError: String,
    val configDone: String,
    val settings: String,
    val back: String,
    val theme: String,
    val themeSystem: String,
    val themeLight: String,
    val themeDark: String,
    val language: String,
    val notifications: String,
    val notifOn: String,
    val notifOff: String,
    val notifRequest: String,
    val notifOpenSettings: String
)

val StringsFa = Strings(
    ready = "آماده به کار - مستقل و بومی",
    statusLabel = "وضعیت سیستم:",
    buildConfig = "بررسی و اعمال کانفیگ‌های رایگان",
    openWindscribe = "اتصال به ویندسکرایب (Premium)",
    waitingVpn = "منتظر برقراری اتصال VPN...",
    vpnConnected = "اتصال VPN برقرار شد",
    vpnNotDetected = "اتصال VPN تشخیص داده نشد",
    windscribeMissing = "اپلیکیشن ویندسکرایب نصب نیست!",
    openError = "خطا در باز کردن برنامه",
    configDone = "فایل تنظیمات با موفقیت ساخته شد!",
    settings = "تنظیمات",
    back = "بازگشت",
    theme = "ظاهر برنامه",
    themeSystem = "مطابق سیستم",
    themeLight = "روشن",
    themeDark = "تاریک",
    language = "زبان",
    notifications = "اعلان‌ها",
    notifOn = "اعلان‌ها فعال است",
    notifOff = "اعلان‌ها غیرفعال است",
    notifRequest = "درخواست اجازه‌ی اعلان",
    notifOpenSettings = "باز کردن تنظیمات اعلان سیستم"
)

val StringsEn = Strings(
    ready = "Ready - standalone",
    statusLabel = "System status:",
    buildConfig = "Check and apply free configs",
    openWindscribe = "Connect to Windscribe (Premium)",
    waitingVpn = "Waiting for VPN connection...",
    vpnConnected = "VPN connected",
    vpnNotDetected = "VPN connection not detected",
    windscribeMissing = "Windscribe is not installed!",
    openError = "Could not open the app",
    configDone = "Config file created successfully!",
    settings = "Settings",
    back = "Back",
    theme = "Appearance",
    themeSystem = "Follow system",
    themeLight = "Light",
    themeDark = "Dark",
    language = "Language",
    notifications = "Notifications",
    notifOn = "Notifications are on",
    notifOff = "Notifications are off",
    notifRequest = "Allow notifications",
    notifOpenSettings = "Open system notification settings"
)

fun stringsFor(lang: String): Strings = if (lang == "en") StringsEn else StringsFa

// آیا اعلان‌ها برای این برنامه روشن است؟ (روی همه‌ی نسخه‌های اندروید جواب می‌دهد)
fun isNotificationAllowed(context: Context): Boolean {
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
}

fun openNotificationSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(fallback)
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label)
    }
}

@Composable
fun SettingsScreen(
    s: Strings,
    themeMode: Int,
    onThemeChange: (Int) -> Unit,
    lang: String,
    onLangChange: (String) -> Unit,
    resumeTick: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    var notifAllowed by remember(resumeTick) { mutableStateOf(isNotificationAllowed(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifAllowed = granted || isNotificationAllowed(context)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(text = s.back) }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = s.settings,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = s.theme,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            OptionRow(s.themeSystem, themeMode == THEME_SYSTEM) { onThemeChange(THEME_SYSTEM) }
            OptionRow(s.themeLight, themeMode == THEME_LIGHT) { onThemeChange(THEME_LIGHT) }
            OptionRow(s.themeDark, themeMode == THEME_DARK) { onThemeChange(THEME_DARK) }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = s.language,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            OptionRow("فارسی", lang == "fa") { onLangChange("fa") }
            OptionRow("English", lang == "en") { onLangChange("en") }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = s.notifications,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = if (notifAllowed) s.notifOn else s.notifOff)
            Spacer(modifier = Modifier.height(8.dp))

            if (!notifAllowed) {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openNotificationSettings(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = s.notifRequest)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            OutlinedButton(
                onClick = { openNotificationSettings(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = s.notifOpenSettings)
            }
        }
    }
}

// ریشه‌ی برنامه: تم، زبان، جهت صفحه، رفت‌وبرگشت بین صفحه‌ها و درخواست اجازه‌ی اعلان در اولین اجرا
@Composable
fun AppRoot(resumeTick: Int) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    var themeMode by remember { mutableStateOf(store.themeMode) }
    var lang by remember { mutableStateOf(store.language) }
    var showSettings by remember { mutableStateOf(false) }
    val s = stringsFor(lang)

    val firstLaunchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !store.notificationAsked &&
            !isNotificationAllowed(context)
        ) {
            store.notificationAsked = true
            firstLaunchLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val dark = when (themeMode) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val direction = if (lang == "en") LayoutDirection.Ltr else LayoutDirection.Rtl

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            if (showSettings) {
                SettingsScreen(
                    s = s,
                    themeMode = themeMode,
                    onThemeChange = {
                        themeMode = it
                        store.themeMode = it
                    },
                    lang = lang,
                    onLangChange = {
                        lang = it
                        store.language = it
                    },
                    resumeTick = resumeTick,
                    onBack = { showSettings = false }
                )
            } else {
                MainDashboardScreen(s = s, onOpenSettings = { showSettings = true })
            }
        }
    }
}
