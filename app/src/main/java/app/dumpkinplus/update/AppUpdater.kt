package app.dumpkinplus.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.SSLException

data class AppUpdateState(
    val current: String = "",
    val available: AppRelease? = null,
    val checking: Boolean = false,
    /** 0..1 while the APK downloads, or null when it isn't. */
    val progress: Float? = null,
    val installing: Boolean = false,
    val message: String? = null,
    val lastChecked: Long = 0L,
    /** On Android 8+ the user has to allow this app to install apps once; the UI then offers to open that setting. */
    val needsInstallPermission: Boolean = false,
)

/** Where the app's own releases live. */
const val APP_REPO = "colebolebole/Dumpkin-Plus"

/** Auto-check at most this often. */
const val APP_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000

/**
 * Looks for a newer Dumpkin+ in this app's GitHub releases and installs it with the system's package installer, so the
 * user always gets Android's own confirmation. Only releases are read; nothing is ever installed without a tap.
 */
class AppUpdater(ctx: Context) {
    private val app = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("dumpkin_update", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        AppUpdateState(current = installedVersion(), lastChecked = prefs.getLong("lastChecked", 0L))
    )
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    var autoCheck: Boolean
        get() = prefs.getBoolean("autoCheck", true)
        set(v) {
            prefs.edit().putBoolean("autoCheck", v).apply()
        }

    /** This project's builds are alphas, so pre-releases are on by default. */
    var includePrereleases: Boolean
        get() = prefs.getBoolean("pre", true)
        set(v) {
            prefs.edit().putBoolean("pre", v).apply()
        }

    private fun installedVersion(): String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    fun autoCheckIfDue(now: Long = System.currentTimeMillis()) {
        if (!autoCheck) return
        if (now - prefs.getLong("lastChecked", 0L) < APP_CHECK_INTERVAL_MS) return
        check()
    }

    fun check() {
        if (_state.value.checking || _state.value.progress != null || _state.value.installing) return
        scope.launch {
            _state.update { it.copy(checking = true, message = null) }
            try {
                val conn = open("https://api.github.com/repos/$APP_REPO/releases?per_page=15", "application/vnd.github+json")
                val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                val list = AppReleases.parseList(arr, includePrereleases)
                val found = AppReleases.newer(_state.value.current, list)
                val now = System.currentTimeMillis()
                prefs.edit().putLong("lastChecked", now).apply()
                _state.update {
                    it.copy(
                        checking = false, available = found, lastChecked = now,
                        message = if (found == null) "You have the newest version." else null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, message = explain(e, "Couldn't check for updates")) }
            }
        }
    }

    /** Whether this app may install other apps (Android 8+ asks the user once). Always true before 8. */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()

    /** The system screen where the user lets Dumpkin+ install updates (Android 8+). */
    @android.annotation.TargetApi(26)
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun downloadAndInstall() {
        val release = _state.value.available ?: return
        if (_state.value.progress != null || _state.value.installing) return
        if (!canInstall()) {
            _state.update { it.copy(needsInstallPermission = true, message = "Allow Dumpkin+ to install apps, then tap Update again.") }
            return
        }
        scope.launch {
            _state.update { it.copy(progress = 0f, message = null, needsInstallPermission = false) }
            val dir = File(app.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
            val apk = File(dir, "update.apk")
            try {
                download(release, apk)
                _state.update { it.copy(progress = null, installing = true) }
                install(apk)
            } catch (e: Exception) {
                dir.deleteRecursively()
                _state.update { it.copy(progress = null, installing = false, message = explain(e, "Update failed")) }
            }
        }
    }

    private fun download(r: AppRelease, into: File) {
        val conn = open(r.url, "application/octet-stream")
        val total = conn.contentLength.toLong().takeIf { it > 0 } ?: r.size
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var last = 0f
        conn.inputStream.use { input ->
            into.outputStream().use { out ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done.toFloat() / total).coerceIn(0f, 1f)
                        if (p - last >= 0.02f) {
                            last = p
                            _state.update { it.copy(progress = p) }
                        }
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (r.sha256 != null && !actual.equals(r.sha256, ignoreCase = true)) {
            throw IOException("the file's checksum doesn't match what GitHub reports, so it was discarded")
        }
        if (into.length() < 10_000) throw IOException("the download is too small to be an app")
    }

    /** Hands the APK to the system installer through a session, which needs no file URI or FileProvider. */
    private fun install(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("dumpkinplus.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 32 * 1024) }
                session.fsync(out)
            }
            registerReceiver()
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE // the system adds the result extras
            val pi = PendingIntent.getBroadcast(app, id, Intent(ACTION).setPackage(app.packageName), flags)
            session.commit(pi.intentSender)
        }
    }

    private var receiverRegistered = false

    private fun registerReceiver() {
        if (receiverRegistered) return
        receiverRegistered = true
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // The system's own "Do you want to update this app?" screen.
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm != null) {
                            runCatching { app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { _state.update { s -> s.copy(installing = false, message = "Couldn't open the installer.") } }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> Unit // the app is replaced and restarted by the system
                    else -> _state.update {
                        it.copy(
                            installing = false,
                            message = describe(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
                        )
                    }
                }
            }
        }, IntentFilter(ACTION))
    }

    private fun describe(status: Int, detail: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "The update was cancelled."
        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "Android won't install this over the current app: it is signed with a different key. " +
                "Uninstall Dumpkin+ first (this deletes your servers on this phone), or keep the current version." +
                (detail?.let { " ($it)" } ?: "")
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
        else -> "The install failed" + (detail?.let { ": $it" } ?: ".")
    }

    private fun explain(e: Exception, prefix: String): String = when (e) {
        is SSLException ->
            "$prefix: this phone's security certificates look too old to connect to GitHub. " +
                "Download the APK on a computer and install it from a file instead."
        else -> "$prefix: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun open(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "Dumpkin-Plus-Android")
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        return c
    }

    companion object {
        private const val ACTION = "app.dumpkinplus.INSTALL_RESULT"
    }
}
