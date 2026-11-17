package app.dumpkinplus.server

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import app.dumpkinplus.DumpkinApp
import app.dumpkinplus.MainActivity
import app.dumpkinplus.R
import app.dumpkinplus.web.WebState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Keeps the process, CPU and Wi-Fi awake while a server is active or the web dashboard is reachable. */
class ServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification(emptyMap(), null))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dumpkinplus:servers").apply { acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Wi-Fi power saving is the biggest source of 100ms+ ping spikes. HIGH_PERF is a no-op on Android 14+,
        // so use the low-latency mode on Android 10+ (it is honoured while the screen is on).
        @Suppress("DEPRECATION")
        val wifiMode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(wifiMode, "dumpkinplus:wifi").apply { acquire() }

        val app = application as DumpkinApp
        scope.launch {
            // Stay up while a server is active OR the web dashboard is reachable; stop once neither is true.
            combine(app.manager.active, app.web.state) { active, web -> active to web }.collect { (active, web) ->
                if (active.isEmpty() && !web.running) {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                    stopSelf()
                } else {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIF_ID, buildNotification(active, web.takeIf { it.running }))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) (application as DumpkinApp).manager.stopAll()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(active: Map<String, String>, web: WebState?): Notification {
        // FLAG_IMMUTABLE needs Android 6; older versions don't have mutable-by-default PendingIntents to worry about.
        val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), immutable)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP_ALL), immutable
        )
        val title = when {
            active.isEmpty() && web != null -> "Dashboard is online"
            active.isEmpty() -> "Dumpkin+ is getting ready"
            active.size == 1 -> "${active.values.first()} is hosting"
            else -> "${active.size} servers are hosting"
        }
        val text = buildList {
            if (active.isNotEmpty()) add(active.values.joinToString(", "))
            if (web != null) add("Dashboard on port ${web.port}")
        }.joinToString(" · ").ifEmpty { "Starting up…" }
        // Channels exist from Android 8.0; before that the builder takes no channel and priority is set directly.
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, DumpkinApp.CHANNEL_ID)
        else Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        return builder
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop all", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFFF58220.toInt())
            .build()
    }

    companion object {
        const val NOTIF_ID = 42
        const val ACTION_STOP_ALL = "app.dumpkinplus.STOP_ALL"
    }
}
