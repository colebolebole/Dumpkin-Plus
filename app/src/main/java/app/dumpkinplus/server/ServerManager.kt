package app.dumpkinplus.server

import android.app.Application
import android.content.Intent
import android.os.Build
import app.dumpkinplus.data.PumpkinConfig
import app.dumpkinplus.data.ServerProfile
import app.dumpkinplus.data.ServerStore
import app.dumpkinplus.data.Status
import app.dumpkinplus.engine.EngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/** Single owner of every server profile and running process. Lives as long as the app process. */
class ServerManager(private val app: Application, private val engines: EngineManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ServerStore(app)
    private val runtimes = ConcurrentHashMap<String, ServerRuntime>()

    private val _profiles = MutableStateFlow(store.load())
    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()

    /** id -> name of every server that is starting, running or stopping. Drives the foreground service. */
    private val _active = MutableStateFlow<Map<String, String>>(emptyMap())
    val active: StateFlow<Map<String, String>> = _active.asStateFlow()

    fun runtime(id: String): ServerRuntime? {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return null
        return runtimes.getOrPut(id) {
            ServerRuntime(
                app.cacheDir, profile, scope,
                onStatus = { rt, status ->
                    val id2 = rt.profile.id
                    _active.update { m ->
                        when (status) {
                            Status.Starting, Status.Running, Status.Stopping -> m + (id2 to rt.profile.name)
                            else -> m - id2
                        }
                    }
                },
                onRestart = { rid ->
                    start(rid)?.let {
                        runtimes[rid]?.appendLog("✖ Could not restart: $it")
                        runtimes[rid]?.clearRestarting()
                    }
                },
            )
        }
    }

    fun dir(id: String): File = store.dir(id)

    fun create(profile: ServerProfile) {
        _profiles.update { it + profile }
        store.save(_profiles.value)
        store.dir(profile.id)
        PumpkinConfig.write(store.dir(profile.id), profile)
    }

    fun update(profile: ServerProfile) {
        _profiles.update { list -> list.map { if (it.id == profile.id) profile else it } }
        store.save(_profiles.value)
        runtimes[profile.id]?.profile = profile
        PumpkinConfig.write(store.dir(profile.id), profile)
    }

    fun delete(id: String) {
        runtimes[id]?.let { if (it.state.value.isActive) it.stop() }
        runtimes.remove(id)
        _active.update { it - id }
        _profiles.update { list -> list.filterNot { it.id == id } }
        store.save(_profiles.value)
        store.deleteDir(id)
    }

    /** The profile with any values that were edited directly in pumpkin.toml folded in. */
    fun pullConfig(profile: ServerProfile): ServerProfile = PumpkinConfig.pull(store.dir(profile.id), profile)

    fun readRawConfig(id: String): String =
        PumpkinConfig.file(store.dir(id)).takeIf { it.exists() }?.readText().orEmpty()

    fun writeRawConfig(id: String, text: String) {
        PumpkinConfig.file(store.dir(id)).writeText(text)
    }

    fun resetConfig(profile: ServerProfile) {
        PumpkinConfig.file(store.dir(profile.id)).delete()
        PumpkinConfig.write(store.dir(profile.id), profile)
    }

    /** @return null on success, otherwise a message to show the user. */
    fun start(id: String): String? {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return "That server no longer exists."
        val rt = runtime(id) ?: return "That server no longer exists."
        if (rt.state.value.isActive) return null
        if (!engines.supported) return "Pumpkin has no engine for this phone's CPU (${engines.arch.label})."
        val engine = engines.resolve(profile.engineId)
            ?: return "No Pumpkin engine installed yet. Open the Engine tab and download one."
        val clash = _profiles.value.firstOrNull {
            it.id != id && it.port == profile.port && runtimes[it.id]?.state?.value?.isActive == true
        }
        if (clash != null) return "Port ${profile.port} is already used by \"${clash.name}\"."

        PumpkinConfig.write(store.dir(id), profile)
        _active.update { it + (id to profile.name) }
        startService(app)
        rt.start(engine.file, store.dir(id))
        return null
    }

    fun stop(id: String) {
        runtimes[id]?.stop()
    }

    /**
     * Restarts a running server so it picks up changed settings; a server that is not running is simply started.
     * @return null on success, otherwise a message to show the user.
     */
    fun restart(id: String): String? {
        val rt = runtime(id) ?: return "That server no longer exists."
        if (!rt.state.value.isActive) return start(id)
        return if (rt.restart()) null else "The server is still launching. Try again in a moment."
    }

    fun stopAll() {
        runtimes.values.forEach { if (it.state.value.isActive) it.stop() }
    }

    fun command(id: String, cmd: String) {
        val c = cmd.trim().removePrefix("/")
        if (c.isNotEmpty()) runtimes[id]?.sendCommand(c)
    }

    companion object {
        /** Tests replace this so screenshots and assertions don't depend on the machine's real network. */
        @Volatile
        var addressSource: () -> List<String> = ::readLocalAddresses

        /** IPv4 addresses other devices on the same network can use to reach this phone. */
        fun localAddresses(): List<String> = addressSource()

        private fun readLocalAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress.orEmpty() }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
}

/** Context.startForegroundService() only exists on Android 8+; earlier versions start the foreground service normally. */
internal fun startService(ctx: android.content.Context) {
    val intent = Intent(ctx, ServerService::class.java)
    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
}
