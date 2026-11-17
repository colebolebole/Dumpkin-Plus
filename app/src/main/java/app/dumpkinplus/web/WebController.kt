package app.dumpkinplus.web

import android.app.Application
import android.content.Context
import android.content.Intent
import app.dumpkinplus.server.startService
import app.dumpkinplus.data.ProfileRules
import app.dumpkinplus.server.ServerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.security.SecureRandom

data class WebState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val port: Int = WebController.DEFAULT_PORT,
    val error: String? = null,
    /** Browsers currently signed in. */
    val sessions: Int = 0,
)

/** Owns the dashboard's HTTP server, its on/off setting, port and password. */
class WebController(
    private val app: Application,
    backend: WebBackend,
    assets: (String) -> ByteArray?,
    private val reservedPorts: () -> Set<Int>,
) {
    private val prefs = app.getSharedPreferences("dumpkin_web", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        WebState(enabled = prefs.getBoolean(KEY_ENABLED, false), port = prefs.getInt(KEY_PORT, DEFAULT_PORT))
    )
    val state: StateFlow<WebState> = _state.asStateFlow()

    private val auth: WebAuth = WebAuth(
        password = { password() },
        onSessionsChanged = { _state.update { it.copy(sessions = auth.sessionCount) } },
    )
    private val api = WebApi(backend, auth, assets)
    private var server: HttpServer? = null

    /** The current dashboard password. Created on first use and kept in the app's private storage. */
    @Synchronized
    fun password(): String =
        prefs.getString(KEY_PASSWORD, null) ?: generatePassword().also { prefs.edit().putString(KEY_PASSWORD, it).apply() }

    /** @return the new password; every browser is signed out. */
    @Synchronized
    fun regeneratePassword(): String {
        val p = generatePassword()
        prefs.edit().putString(KEY_PASSWORD, p).apply()
        auth.revokeAll()
        return p
    }

    /** @return null on success, otherwise why the password was rejected. */
    @Synchronized
    fun setPassword(p: String): String? {
        if (p.length < MIN_PASSWORD) return "Use at least $MIN_PASSWORD characters."
        if (p.length > 64) return "Use at most 64 characters."
        if (p.any { it.isWhitespace() }) return "No spaces, please."
        prefs.edit().putString(KEY_PASSWORD, p).apply()
        auth.revokeAll()
        return null
    }

    fun signOutAll() = auth.revokeAll()

    /** @return null on success, otherwise why the port was rejected. */
    @Synchronized
    fun setPort(port: Int): String? {
        if (port !in ProfileRules.MIN_PORT..ProfileRules.MAX_PORT) {
            return "Use a port between ${ProfileRules.MIN_PORT} and ${ProfileRules.MAX_PORT}."
        }
        if (port in reservedPorts()) return "A Minecraft server already uses port $port."
        prefs.edit().putInt(KEY_PORT, port).apply()
        _state.update { it.copy(port = port) }
        if (_state.value.enabled) startServer()
        return null
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.update { it.copy(enabled = enabled) }
        if (enabled) startServer() else stopServer()
    }

    fun startIfEnabled() {
        if (_state.value.enabled) startServer()
    }

    @Synchronized
    private fun startServer() {
        stopServer(keepEnabled = true)
        val port = _state.value.port
        try {
            val s = HttpServer(port) { api.handle(it) }
            s.start()
            server = s
            _state.update { it.copy(running = true, error = null, sessions = auth.sessionCount) }
            // Keep the process alive (and Wi-Fi awake) while the dashboard is reachable.
            runCatching { startService(app) }
        } catch (e: IOException) {
            _state.update { it.copy(running = false, error = "Couldn't listen on port $port: ${e.message ?: "unknown error"}") }
        }
    }

    @Synchronized
    private fun stopServer(keepEnabled: Boolean = false) {
        server?.stop()
        server = null
        _state.update { it.copy(running = false, error = if (keepEnabled) it.error else null) }
        auth.revokeAll()
    }

    private fun generatePassword(): String {
        // No look-alike characters (0/O, 1/I/l), grouped for easy typing: XXXX-XXXX-XXXX
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return (0 until 12).map { alphabet[random.nextInt(alphabet.length)] }.chunked(4).joinToString("-") { it.joinToString("") }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val MIN_PASSWORD = 8
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        private const val KEY_PASSWORD = "password"
    }
}
