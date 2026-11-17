package app.dumpkinplus.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import app.dumpkinplus.MainActivity
import app.dumpkinplus.ThemeMode
import app.dumpkinplus.data.DeviceSnapshot
import app.dumpkinplus.data.DeviceTuning
import app.dumpkinplus.data.ServerProfile
import app.dumpkinplus.data.TuningAction
import app.dumpkinplus.data.TuningItem
import app.dumpkinplus.data.readDeviceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What this phone can do, and the Android settings that decide how well it hosts. */
class TuningScreen(act: MainActivity) : Screen(act) {
    private lateinit var banner: TextView
    private lateinit var bannerCard: LinearLayout
    private lateinit var checks: LinearLayout
    private lateinit var memBar: ProgressBar
    private lateinit var memText: TextView
    private lateinit var memAdvice: TextView
    private val rows = LinkedHashMap<String, TextView>()
    private lateinit var deviceCard: LinearLayout

    private val expanded = HashSet<String>()
    private var checkKey: List<Any> = emptyList()
    private val checkViews = HashMap<String, Pair<TextView, TextView>>()

    override fun build(): View {
        val page = act.vbox(12).apply { setPadding(act.dp(16), act.dp(20), act.dp(16), act.dp(24)) }
        page.add(act.text("Tuning", 28f, bold = true))
        page.add(act.text("Your phone, and what hosting needs from it", 14f, Pal.dim))

        bannerCard = act.card(16, 4, Pal.surfaceHi)
        banner = act.text("Checking…", 15f, bold = true)
        bannerCard.add(banner)
        page.add(bannerCard)

        page.add(act.section("This device"))
        deviceCard = act.card(16, 6)
        for (k in listOf("Model", "Processor", "CPU", "Android", "Battery", "Heat", "Address")) {
            val row = act.hbox(12)
            row.add(act.text(k, 13.5f, Pal.dim), act.dp(92), WRAP)
            val v = act.text("…", 14f)
            rows[k] = v
            row.add(v, 0, WRAP, 1f)
            deviceCard.add(row)
        }
        page.add(deviceCard)

        page.add(act.section("Memory"))
        val mem = act.card(16, 8)
        memText = act.text("", 14f)
        memBar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        memAdvice = act.hint("")
        mem.add(memText)
        mem.add(memBar, MATCH, act.dp(10))
        mem.add(memAdvice)
        page.add(mem)

        page.add(act.section("Checks"))
        checks = act.vbox(8)
        page.add(checks)

        page.add(act.section("Hosting"))
        page.add(
            act.switchRow(
                "Keep screen on while hosting",
                "Android only grants its low-latency Wi-Fi mode while the screen is on, so this gives players the lowest ping at the " +
                    "cost of battery. Plug the phone in.",
                app.keepScreenOn.value,
            ) { app.setKeepScreenOn(it) }
        )

        page.add(act.section("Appearance"))
        page.add(act.hint("Auto follows the system's dark mode, which only exists on Android 10 and newer."))
        page.add(ChipGroup(act, ThemeMode.entries.map { it.label }, app.themeMode.ordinal) {
            app.themeMode = ThemeMode.entries[it]
            act.recreate()
        }.view)

        page.add(act.section("Android settings"))
        val shortcuts = act.vbox(8)
        shortcuts.add(act.button("Battery settings", BtnKind.Outline) { perform(TuningAction.BatterySettings) })
        shortcuts.add(act.button("Wi-Fi settings", BtnKind.Outline) { perform(TuningAction.WifiSettings) })
        shortcuts.add(act.button("Developer options", BtnKind.Outline) { perform(TuningAction.DeveloperOptions) })
        shortcuts.add(act.button("Dumpkin+ app info", BtnKind.Outline) { perform(TuningAction.AppInfo) })
        page.add(shortcuts)

        return ScrollView(act).apply { addView(page) }
    }

    override fun onStart() {
        scope.launch {
            while (isActive) {
                val s = withContext(Dispatchers.IO) { readDeviceSnapshot(act) }
                render(s)
                delay(3000) // slower than the stock app: every refresh reads /sys and /proc on a phone that's already busy hosting
            }
        }
    }

    private fun render(s: DeviceSnapshot) {
        rows["Model"]?.text = s.model
        rows["Processor"]?.text = s.soc
        rows["CPU"]?.text = buildString {
            append(s.cores).append(" cores · ").append(s.abi)
            if (s.clusters.isNotEmpty()) append("\n").append(DeviceTuning.formatClusters(s.clusters))
        }
        rows["Android"]?.text = s.androidVersion
        rows["Battery"]?.text = if (s.batteryPercent >= 0) "${s.batteryPercent}%" + if (s.charging) " · charging" else "" else "Unknown"
        rows["Heat"]?.text = s.thermal?.label ?: "Unknown"
        rows["Address"]?.text = s.address ?: "No network address"

        val used = (s.totalMb - s.availMb).coerceAtLeast(0)
        memText.text = "${formatMb(used)} in use of ${formatMb(s.totalMb)} · ${formatMb(s.availMb)} free"
        memBar.progress = if (s.totalMb > 0) (used * 1000L / s.totalMb).toInt().coerceIn(0, 1000) else 0
        val a = DeviceTuning.memoryAdvice(s.totalMb)
        memAdvice.text = "Give servers up to ${formatMb(a.maxMb)} (${a.maxPercent}% of RAM). Around ${formatMb(a.comfortableMb)} is " +
            "comfortable; above ${formatMb(a.cautionAboveMb)} Android may start closing other apps." +
            if (s.totalMb <= ServerProfile.LOW_END_MB) {
                " This is a low-memory phone: run one server, keep the Low-end view distance and close other apps."
            } else ""

        val items = DeviceTuning.items(s)
        val problems = items.count { !it.ok }
        banner.text = if (problems == 0) "Everything looks good for hosting"
        else "$problems ${if (problems == 1) "setting" else "settings"} could keep your server from running smoothly"
        bannerCard.background = rounded(
            if (problems == 0) Pal.surfaceHi else (if (Pal.dark) 0xFF4A2D12.toInt() else 0xFFFFE3C2.toInt()),
            act.dp(14).toFloat(),
        )

        // Rebuild the check cards only when their shape changes; otherwise just refresh the text, so a tap isn't lost.
        val key = items.map { Triple(it.id, it.ok, it.action) }
        if (key != checkKey) {
            checkKey = key
            checks.removeAllViews()
            checkViews.clear()
            items.forEach { checks.add(checkCard(it)) }
        } else {
            items.forEach { checkViews[it.id]?.let { (sum, det) -> sum.text = it.summary; det.text = it.detail } }
        }
    }

    private fun checkCard(item: TuningItem): View {
        val card = act.vbox(6).apply {
            background = ripple(rounded(Pal.surface, act.dp(14).toFloat()))
            setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(12))
            isClickable = true
        }
        val head = act.hbox(10)
        head.add(act.text(if (item.ok) "OK" else "!", 12f, if (item.ok) Pal.ok else Pal.warn, bold = true).apply {
            minWidth = act.dp(22)
        }, WRAP, WRAP)
        val col = act.vbox(1)
        col.add(act.text(item.title, 15f, bold = true))
        val summary = col.add(act.text(item.summary, 13f, Pal.dim))
        head.add(col, 0, WRAP, 1f)
        card.add(head)
        val more = act.vbox(8)
        val detail = more.add(act.text(item.detail, 13.5f))
        if (item.action != TuningAction.None) more.add(act.button(item.actionLabel, BtnKind.Primary) { perform(item.action) }, WRAP, WRAP)
        more.visibility = if (item.id in expanded || !item.ok) View.VISIBLE else View.GONE
        if (!item.ok) expanded += item.id
        card.add(more)
        card.setOnClickListener {
            if (more.visibility == View.VISIBLE) {
                more.visibility = View.GONE
                expanded -= item.id
            } else {
                more.visibility = View.VISIBLE
                expanded += item.id
            }
        }
        checkViews[item.id] = summary to detail
        return card
    }

    private fun open(i: Intent) {
        try {
            act.startActivity(i)
        } catch (_: Exception) {
            act.toast("This phone has no screen for that.")
        }
    }

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}"))

    private fun perform(a: TuningAction) {
        when (a) {
            TuningAction.None -> Unit
            TuningAction.RequestBattery ->
                if (Build.VERSION.SDK_INT >= 23) {
                    open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}")))
                } else {
                    act.toast("Android 5 doesn't restrict background apps.")
                }
            TuningAction.BatterySettings -> when {
                Build.VERSION.SDK_INT >= 23 -> open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                Build.VERSION.SDK_INT >= 22 -> open(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                else -> open(appDetails())
            }
            TuningAction.NotificationSettings ->
                if (Build.VERSION.SDK_INT >= 26) {
                    open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, act.packageName))
                } else {
                    open(appDetails())
                }
            TuningAction.WifiSettings -> open(Intent(Settings.ACTION_WIFI_SETTINGS))
            TuningAction.DeveloperOptions -> open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            TuningAction.AppInfo -> open(appDetails())
        }
    }
}
