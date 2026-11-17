package app.dumpkinplus.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import app.dumpkinplus.server.ServerManager
import java.io.File
import java.util.Locale

enum class Thermal(val label: String) { Cool("Cool"), Warm("Warm"), Hot("Hot") }

enum class WifiBand { None, Ghz24, Ghz5, Ghz6, Unknown }

/** [cores] cores that all top out at [maxKhz]. Phones group cores into clusters (prime, performance, efficiency). */
data class CpuCluster(val cores: Int, val maxKhz: Long)

/** Everything the Device tuning tab shows, read in one go so the screen (and its tests) work from plain data. */
data class DeviceSnapshot(
    val model: String,
    val soc: String,
    val cores: Int,
    val abi: String,
    val clusters: List<CpuCluster>,
    val androidVersion: String,
    val batteryPercent: Int,
    val charging: Boolean,
    val thermal: Thermal?,
    val totalMb: Int,
    val availMb: Int,
    val address: String?,
    val wifi: WifiBand,
    val batteryUnrestricted: Boolean,
    val notificationsAllowed: Boolean,
    /** Doze (and with it, the battery-optimisation allow-list) only exists from Android 6.0. */
    val dozeAvailable: Boolean = true,
)

/** What a tuning card's button does, and which system shortcuts the tab offers. */
enum class TuningAction {
    None, RequestBattery, BatterySettings, NotificationSettings, WifiSettings, DeveloperOptions, AppInfo
}

data class TuningItem(
    val id: String,
    val title: String,
    /** True when nothing needs doing. */
    val ok: Boolean,
    val summary: String,
    val detail: String,
    val action: TuningAction = TuningAction.None,
    val actionLabel: String = "",
)

/** How much RAM is sensible to give servers on a phone with [totalMb] of memory. */
data class MemoryAdvice(val maxMb: Int, val cautionAboveMb: Int, val comfortableMb: Int, val maxPercent: Int)

object DeviceTuning {
    const val MAX_PERCENT = 75
    private const val CAUTION_PERCENT = 60
    private const val COMFORTABLE_PERCENT = 40

    /** Rounded down to 128 MB steps so the numbers line up with the memory-limit slider. */
    fun memoryAdvice(totalMb: Int): MemoryAdvice {
        fun share(percent: Int) = (totalMb * percent / 100) / 128 * 128
        return MemoryAdvice(
            maxMb = share(MAX_PERCENT).coerceAtLeast(512),
            cautionAboveMb = share(CAUTION_PERCENT),
            comfortableMb = share(COMFORTABLE_PERCENT),
            maxPercent = MAX_PERCENT,
        )
    }

    /** Groups per-core maximum frequencies (kHz) into clusters, fastest first. Unreadable cores are skipped. */
    fun groupClusters(maxKhz: List<Long>): List<CpuCluster> =
        maxKhz.filter { it > 0 }
            .groupingBy { it }.eachCount()
            .map { (khz, n) -> CpuCluster(n, khz) }
            .sortedByDescending { it.maxKhz }

    fun formatClusters(clusters: List<CpuCluster>): String =
        clusters.joinToString(" + ") { "${it.cores}x ${ghz(it.maxKhz)}" }

    private fun ghz(khz: Long) = String.format(Locale.US, "%.1f GHz", khz / 1_000_000.0)

    private val socNames = mapOf(
        // Older chips first: these are what Android 5-8 phones report (via /proc/cpuinfo, since Build.SOC_MODEL is 12+).
        "MSM8916" to "Snapdragon 410", "MSM8939" to "Snapdragon 615", "MSM8952" to "Snapdragon 617",
        "MSM8953" to "Snapdragon 625", "MSM8992" to "Snapdragon 808", "MSM8994" to "Snapdragon 810",
        "MSM8996" to "Snapdragon 820", "MSM8998" to "Snapdragon 835", "SDM660" to "Snapdragon 660",
        "SDM845" to "Snapdragon 845", "MSM8974" to "Snapdragon 800/801", "APQ8084" to "Snapdragon 805",
        "SM8150" to "Snapdragon 855", "SM8250" to "Snapdragon 865", "SM8350" to "Snapdragon 888",
        "SM8450" to "Snapdragon 8 Gen 1", "SM8475" to "Snapdragon 8+ Gen 1", "SM8550" to "Snapdragon 8 Gen 2",
        "SM8650" to "Snapdragon 8 Gen 3", "SM8750" to "Snapdragon 8 Elite", "SM7325" to "Snapdragon 778G",
        "SM7475" to "Snapdragon 7+ Gen 2", "SM7550" to "Snapdragon 7 Gen 3", "SM6375" to "Snapdragon 695",
    )

    /** "Snapdragon 8+ Gen 1 (SM8475)" when the code is known, otherwise whatever the phone reports. */
    fun socName(manufacturer: String?, model: String?, hardware: String?): String {
        val code = model?.trim().orEmpty()
        socNames[code.uppercase()]?.let { return "$it ($code)" }
        val raw = listOf(manufacturer, code).filter { !it.isNullOrBlank() && !it.equals("unknown", true) }
            .joinToString(" ").trim()
        if (raw.isNotEmpty()) return raw
        val hw = hardware?.trim().orEmpty()
        // "Qualcomm Technologies, Inc MSM8994" and similar /proc/cpuinfo lines still contain the chip code.
        socNames.entries.firstOrNull { hw.contains(it.key, ignoreCase = true) }?.let { (key, name) ->
            return "$name ($key)"
        }
        return hw.ifEmpty { "Unknown processor" }
    }

    fun deviceName(manufacturer: String, model: String): String {
        val maker = manufacturer.trim().replaceFirstChar { it.uppercase() }
        val m = model.trim()
        return if (m.startsWith(maker, ignoreCase = true) || maker.isEmpty()) m else "$maker $m"
    }

    /** From [PowerManager.getCurrentThermalStatus]: 0-1 none/light, 2 moderate, 3+ severe or worse. */
    fun thermalFromStatus(status: Int): Thermal = when {
        status <= 1 -> Thermal.Cool
        status == 2 -> Thermal.Warm
        else -> Thermal.Hot
    }

    /** Fallback for Android 9 and older, which have no thermal status API. */
    fun thermalFromBatteryTemp(celsius: Double): Thermal = when {
        celsius < 38.0 -> Thermal.Cool
        celsius < 43.0 -> Thermal.Warm
        else -> Thermal.Hot
    }

    fun wifiBand(onWifi: Boolean, frequencyMhz: Int?): WifiBand = when {
        !onWifi -> WifiBand.None
        frequencyMhz == null || frequencyMhz <= 0 -> WifiBand.Unknown
        frequencyMhz < 3000 -> WifiBand.Ghz24
        frequencyMhz < 5925 -> WifiBand.Ghz5
        else -> WifiBand.Ghz6
    }

    /** The address other devices most likely use: home networks first, mobile-carrier ranges last. */
    fun pickAddress(addresses: List<String>): String? =
        addresses.firstOrNull { it.startsWith("192.168.") }
            ?: addresses.firstOrNull { it.startsWith("10.") }
            ?: addresses.firstOrNull { a -> a.startsWith("172.") && a.split('.').getOrNull(1)?.toIntOrNull() in 16..31 }
            ?: addresses.firstOrNull()

    /** The recommendations behind the "N settings could keep your server from running smoothly" banner. */
    fun items(s: DeviceSnapshot): List<TuningItem> = buildList {
        add(
            if (!s.dozeAvailable) TuningItem(
                id = "battery",
                title = "Battery optimisation",
                ok = true,
                summary = "Nothing to set on this Android version",
                detail = "Android 5 has no Doze mode, so it won't freeze Dumpkin+ in the background. Some phone makers add their own " +
                    "app-killer (\"power saver\", \"protected apps\"). If servers drop out with the screen off, allow Dumpkin+ there.",
            ) else TuningItem(
                id = "battery",
                title = "Battery optimisation",
                ok = s.batteryUnrestricted,
                summary = if (s.batteryUnrestricted) "Unrestricted, you're all set" else "Android may freeze Dumpkin+ when the screen is off",
                detail = "Android freezes background apps to save battery, which makes a server drop out and players time out. " +
                    "Letting Dumpkin+ ignore battery optimisation keeps your servers running with the screen off.",
                action = if (s.batteryUnrestricted) TuningAction.None else TuningAction.RequestBattery,
                actionLabel = "Allow",
            )
        )
        add(
            TuningItem(
                id = "notifications",
                title = "Notifications",
                ok = s.notificationsAllowed,
                summary = if (s.notificationsAllowed) "Allowed" else "Blocked, so you can't see or stop running servers",
                detail = "While servers run, Dumpkin+ shows a notification with a Stop all button. With notifications blocked you " +
                    "can't see that servers are running or stop them from the shade.",
                action = if (s.notificationsAllowed) TuningAction.None else TuningAction.NotificationSettings,
                actionLabel = "Open settings",
            )
        )
        val noAddress = s.address == null
        add(
            when {
                s.wifi == WifiBand.Ghz24 -> TuningItem(
                    "wifi", "Wi-Fi", ok = false,
                    summary = "You're on 2.4 GHz, 5 GHz gives players lower ping",
                    detail = "2.4 GHz Wi-Fi is crowded and slower, which shows up as lag spikes for players. If your router has a " +
                        "5 GHz network, connect this phone to it and have players do the same.",
                    action = TuningAction.WifiSettings, actionLabel = "Wi-Fi settings",
                )
                noAddress -> TuningItem(
                    "wifi", "Wi-Fi", ok = false,
                    summary = "No network address, players can't reach this phone",
                    detail = "This phone has no local network address. Connect it to Wi-Fi (or turn on its hotspot) so other " +
                        "devices can join your servers.",
                    action = TuningAction.WifiSettings, actionLabel = "Wi-Fi settings",
                )
                s.wifi == WifiBand.None -> TuningItem(
                    "wifi", "Wi-Fi", ok = true,
                    summary = "Not on Wi-Fi (hotspot or cable)",
                    detail = "Players can reach this phone at ${s.address}. A 5 GHz Wi-Fi connection usually gives the lowest ping.",
                )
                else -> TuningItem(
                    "wifi", "Wi-Fi", ok = true,
                    summary = when (s.wifi) {
                        WifiBand.Ghz5 -> "5 GHz, good for low ping"
                        WifiBand.Ghz6 -> "6 GHz, good for low ping"
                        else -> "Connected"
                    },
                    detail = "Players can reach this phone at ${s.address}.",
                )
            }
        )
        add(
            TuningItem(
                id = "heat",
                title = "Temperature",
                ok = s.thermal != Thermal.Hot,
                summary = if (s.thermal == Thermal.Hot) "Running hot, the phone may slow down" else "Running cool",
                detail = "Hot phones throttle their CPU, which makes the server stutter. Take the case off, keep it out of the sun " +
                    "and off soft surfaces, and avoid fast-charging while hosting.",
            )
        )
        val lowFree = s.availMb < maxOf(600, s.totalMb / 10)
        add(
            TuningItem(
                id = "memory",
                title = "Free memory",
                ok = !lowFree,
                summary = if (lowFree) "Only ${mb(s.availMb)} free, close other apps" else "${mb(s.availMb)} free",
                detail = "When free RAM runs out Android closes background apps, and that can include Dumpkin+. " +
                    "Close apps you're not using, or lower a server's memory limit in its settings.",
            )
        )
    }

    private fun mb(v: Int) = if (v >= 1024) String.format(Locale.US, "%.1f GB", v / 1024.0) else "$v MB"
}

/** Reads the phone's current state. Cheap, but it touches /sys, so call it off the main thread. */
@Suppress("DEPRECATION")
fun readDeviceSnapshot(ctx: Context): DeviceSnapshot {
    val mem = deviceMemory(ctx)
    val cores = Runtime.getRuntime().availableProcessors()
    val freqs = (0 until cores).mapNotNull { i ->
        runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
    }

    val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE

    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val thermal = when {
        Build.VERSION.SDK_INT >= 29 -> DeviceTuning.thermalFromStatus(pm.currentThermalStatus)
        tempTenths != Int.MIN_VALUE -> DeviceTuning.thermalFromBatteryTemp(tempTenths / 10.0)
        else -> null
    }

    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // NetworkCapabilities / activeNetwork need Android 6; the older NetworkInfo call works everywhere.
    val onWifi = runCatching { cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI }.getOrDefault(false)
    val frequency = if (onWifi) runCatching {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.frequency
    }.getOrNull() else null

    val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null
    val socMaker = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else null
    val doze = Build.VERSION.SDK_INT >= 23

    return DeviceSnapshot(
        model = DeviceTuning.deviceName(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty()),
        soc = DeviceTuning.socName(socMaker, socModel, cpuHardware() ?: Build.HARDWARE),
        cores = cores,
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        clusters = DeviceTuning.groupClusters(freqs),
        androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        thermal = thermal,
        totalMb = mem.totalMb,
        availMb = mem.availMb,
        address = DeviceTuning.pickAddress(ServerManager.localAddresses()),
        wifi = DeviceTuning.wifiBand(onWifi, frequency),
        batteryUnrestricted = if (doze) pm.isIgnoringBatteryOptimizations(ctx.packageName) else true,
        notificationsAllowed = notificationsEnabled(ctx),
        dozeAvailable = doze,
    )
}

/** The "Hardware" line of /proc/cpuinfo, which names the chip on phones that predate Build.SOC_MODEL. */
private fun cpuHardware(): String? = runCatching {
    File("/proc/cpuinfo").useLines { lines ->
        lines.firstOrNull { it.startsWith("Hardware") }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
    }
}.getOrNull()

/** [NotificationManager.areNotificationsEnabled] is Android 7+; before that notifications can't be blocked per app. */
private fun notificationsEnabled(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 24) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
    } else {
        true
    }
