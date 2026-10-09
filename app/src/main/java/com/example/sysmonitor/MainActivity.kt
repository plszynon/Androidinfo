package com.example.sysmonitor

import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvInfo: TextView
    private lateinit var tvProcs: TextView
    private lateinit var btnPerm: Button

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }
        fun header(t: String) = TextView(this).apply {
            text = t; textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        }
        root.addView(header("Informacje o urządzeniu"))
        tvInfo = TextView(this).apply { textSize = 14f; setLineSpacing(0f, 1.2f) }
        root.addView(tvInfo)

        root.addView(header("Ostatnio aktywne aplikacje (24 h)"))
        btnPerm = Button(this).apply {
            text = "Nadaj dostęp do statystyk użycia"
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        root.addView(btnPerm)
        tvProcs = TextView(this).apply { textSize = 13f; setLineSpacing(0f, 1.15f) }
        root.addView(tvProcs)

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    private fun refresh() {
        tvInfo.text = buildInfo()
        val ok = hasUsageAccess()
        btnPerm.visibility = if (ok) android.view.View.GONE else android.view.View.VISIBLE
        tvProcs.text = if (ok) buildApps()
        else "Android 8+ nie pozwala czytać cudzych procesów. Po nadaniu dostępu pokażę ostatnio używane aplikacje."
    }

    private fun buildInfo(): String {
        val sb = StringBuilder()

        // Bateria
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (scale > 0) level * 100 / scale else -1
        val status = when (i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "ładowanie"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "rozładowywanie"
            BatteryManager.BATTERY_STATUS_FULL -> "pełna"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "nie ładuje"
            else -> "nieznany"
        }
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val volt = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val health = when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "dobry"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "przegrzanie"
            BatteryManager.BATTERY_HEALTH_DEAD -> "uszkodzona"
            BatteryManager.BATTERY_HEALTH_COLD -> "zimno"
            else -> "nieznany"
        }
        sb.append("🔋 Bateria: $pct% ($status)\n")
        sb.append("   Temp.: $temp °C, napięcie: $volt mV, stan: $health\n\n")

        // System
        sb.append("🤖 Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("🛡 Poprawka bezpieczeństwa: ${Build.VERSION.SECURITY_PATCH}\n")
        sb.append("📱 Urządzenie: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("   Sprzęt: ${Build.HARDWARE}, ABI: ${Build.SUPPORTED_ABIS.firstOrNull()}\n")
        sb.append("   Kernel: ${System.getProperty("os.version")}\n\n")

        // RAM
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        sb.append("🧠 RAM: wolne ${Formatter.formatShortFileSize(this, mi.availMem)} " +
                "z ${Formatter.formatShortFileSize(this, mi.totalMem)}\n")

        // Pamięć
        val st = StatFs(Environment.getDataDirectory().path)
        sb.append("💾 Pamięć: wolne ${Formatter.formatShortFileSize(this, st.availableBytes)} " +
                "z ${Formatter.formatShortFileSize(this, st.totalBytes)}\n")

        // Uptime
        sb.append("⏱ Czas działania: ${DateUtils.formatElapsedTime(SystemClock.elapsedRealtime() / 1000)}\n")

        // Sieć
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "brak"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "komórkowa"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "inna"
        }
        sb.append("🌐 Sieć: $net")
        return sb.toString()
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun buildApps(): String {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val list = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - DateUtils.DAY_IN_MILLIS, now)
            ?.filter { it.lastTimeUsed > 0 }
            ?.sortedByDescending { it.lastTimeUsed }
            ?.take(25) ?: emptyList()
        if (list.isEmpty()) return "Brak danych."
        val pm = packageManager
        return list.joinToString("\n") { u ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(u.packageName, 0)).toString()
            } catch (e: Exception) { u.packageName }
            val ago = DateUtils.getRelativeTimeSpanString(
                u.lastTimeUsed, now, DateUtils.MINUTE_IN_MILLIS)
            val fg = DateUtils.formatElapsedTime(u.totalTimeInForeground / 1000)
            "• $label — $ago (na ekranie: $fg)"
        }
    }
}
