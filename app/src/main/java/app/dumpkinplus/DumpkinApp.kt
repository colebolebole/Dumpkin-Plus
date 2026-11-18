package app.dumpkinplus

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.dumpkinplus.engine.EngineManager
import app.dumpkinplus.server.ServerManager
import app.dumpkinplus.update.AppUpdater
import app.dumpkinplus.web.AppBackend
import app.dumpkinplus.web.WebController
import kotlinx.coroutines.flow.MutableStateFlow

/** The colour scheme the user picked. [Auto] follows the system where it has a dark mode (Android 10+). */
enum class ThemeMode(val label: String) { Auto("Auto"), Dark("Dark"), Light("Light") }

class DumpkinApp : Application() {
    lateinit var engines: EngineManager
        private set
    lateinit var manager: ServerManager
        private set
    lateinit var web: WebController
        private set
    lateinit var updater: AppUpdater
        private set

    private val prefs by lazy { getSharedPreferences("dumpkin", Context.MODE_PRIVATE) }

    /** Keep the display on while a server is running (best ping: Android's low-latency Wi-Fi mode needs the screen on). */
    val keepScreenOn by lazy { MutableStateFlow(prefs.getBoolean("keepScreenOn", false)) }

    fun setKeepScreenOn(value: Boolean) {
        keepScreenOn.value = value
        prefs.edit().putBoolean("keepScreenOn", value).apply()
    }

    var themeMode: ThemeMode
        get() = ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: ThemeMode.Auto
        set(value) {
            prefs.edit().putString("theme", value.name).apply()
        }

    override fun onCreate() {
        super.onCreate()
        engines = EngineManager(this)
        manager = ServerManager(this, engines)
        updater = AppUpdater(this)
        web = WebController(
            app = this,
            backend = AppBackend(this, manager, engines),
            assets = { name -> runCatching { assets.open("web/$name").use { it.readBytes() } }.getOrNull() },
            reservedPorts = { manager.profiles.value.map { it.port }.toSet() },
        )
        // Notification channels only exist from Android 8.0; older versions show the notification as-is.
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Running servers", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Dumpkin+ is hosting a Pumpkin server or its web dashboard"
                }
            )
        }
        // If the dashboard was left on, bring it back up with the app.
        web.startIfEnabled()
        // Look for new engine builds in the background (at most every few hours; can be switched off on the Engine tab).
        // Not under Robolectric: unit tests must not reach the network.
        if (Build.FINGERPRINT != "robolectric") {
            engines.autoCheckIfDue()
            updater.autoCheckIfDue()
        }
    }

    companion object {
        const val CHANNEL_ID = "servers"
    }
}

val Context.dumpkin: DumpkinApp get() = applicationContext as DumpkinApp
