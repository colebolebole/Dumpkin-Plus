package app.dumpkinplus.web

import app.dumpkinplus.data.RuntimeState
import app.dumpkinplus.data.ServerProfile
import app.dumpkinplus.data.Status
import app.dumpkinplus.engine.Channel
import app.dumpkinplus.engine.EngineUiState
import app.dumpkinplus.engine.InstalledEngine
import app.dumpkinplus.engine.RemoteEngine
import app.dumpkinplus.server.LogBuffer
import app.dumpkinplus.server.LogSlice
import java.io.File
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An in-memory stand-in for the app. With [instant] = true state changes happen immediately (for tests); otherwise
 * servers take a moment to start and stop, which makes it a convincing back end for previewing the web UI.
 */
class FakeBackend(private val instant: Boolean = true) : WebBackend {
    override val appVersion = "0.5.1-alpha"
    override var engineSupported = true

    val servers = CopyOnWriteArrayList<ServerProfile>()
    private val states = ConcurrentHashMap<String, RuntimeState>()
    private val buffers = ConcurrentHashMap<String, LogBuffer>()
    private val configs = ConcurrentHashMap<String, String>()
    private val timer = Timer("fake-backend", true)

    @Volatile var engineState = EngineUiState(
        installed = listOf(InstalledEngine("0.2.2_26.3-26.60", "0.2.2+26.3-26.60", "Stable", 31_000_000, File("pumpkin"))),
        remote = listOf(
            RemoteEngine(Channel.Stable, "0.2.2+26.3-26.60", "0.2.2+26.3-26.60", "pumpkin-aarch64-android", "https://example.invalid/a", 31_000_000, "2026-11-08T10:00:00Z"),
            RemoteEngine(Channel.Canary, "canary", "Canary 2026-11-16", "pumpkin-aarch64-android", "https://example.invalid/b", 30_500_000, "2026-11-16T03:00:00Z"),
            RemoteEngine(Channel.Nightly, "nightly", "Nightly 2026-11-16", "pumpkin-aarch64-android", "https://example.invalid/c", 29_900_000, "2026-11-16T04:00:00Z"),
        ),
    )
    var startFailure: String? = null
    val sentCommands = CopyOnWriteArrayList<String>()

    private fun buffer(id: String) = buffers.getOrPut(id) { LogBuffer(2000) }
    private fun log(id: String, vararg lines: String) = buffer(id).append(lines.toList())
    private fun later(ms: Long, block: () -> Unit) {
        if (instant) block() else timer.schedule(object : TimerTask() { override fun run() = block() }, ms)
    }

    override fun device() = DeviceSnapshot(totalMb = 7600, availMb = 3100, cores = 8, addresses = listOf("192.168.0.83"))
    override fun profiles(): List<ServerProfile> = servers.toList()
    override fun runtime(id: String): RuntimeState? = if (servers.any { it.id == id }) states[id] ?: RuntimeState() else null
    override fun logsSince(id: String, cursor: Long): LogSlice? = if (servers.any { it.id == id }) buffer(id).slice(cursor) else null

    override fun create(profile: ServerProfile) { servers.add(profile) }
    override fun update(profile: ServerProfile) {
        val i = servers.indexOfFirst { it.id == profile.id }
        if (i >= 0) servers[i] = profile
    }
    override fun delete(id: String) {
        servers.removeAll { it.id == id }; states.remove(id); buffers.remove(id); configs.remove(id)
    }

    override fun start(id: String): String? {
        val p = servers.firstOrNull { it.id == id } ?: return "That server no longer exists."
        startFailure?.let { return it }
        if (engineState.installed.isEmpty()) return "No Pumpkin engine installed yet. Open the Engine tab and download one."
        val clash = servers.firstOrNull { it.id != id && it.port == p.port && states[it.id]?.isActive == true }
        if (clash != null) return "Port ${p.port} is already used by \"${clash.name}\"."
        if (states[id]?.isActive == true) return null
        states[id] = RuntimeState(Status.Starting, startedAt = System.currentTimeMillis())
        log(id, "▶ Starting \"${p.name}\" on port ${p.port}", "00:00:01 INFO Loaded 26 int and 4 float embedded context provider(s)")
        later(2200) {
            states[id] = RuntimeState(Status.Running, 0, p.maxPlayers, "26.3", 412, System.currentTimeMillis() - 2200)
            log(id, "00:00:03 INFO Started server; took 26ms", "00:00:03 INFO Server is now running. Connect using port: Java Edition: 0.0.0.0:${p.port}")
        }
        return null
    }

    override fun stop(id: String) {
        val cur = states[id] ?: return
        if (!cur.isActive) return
        states[id] = cur.copy(status = Status.Stopping)
        log(id, "■ Stopping…")
        later(1200) {
            states[id] = RuntimeState(Status.Stopped)
            log(id, "■ Process exited with code 0")
        }
    }

    override fun restart(id: String): String? {
        val cur = states[id]
        if (cur?.isActive != true) return start(id)
        if (cur.status == Status.Starting) return "The server is still launching. Try again in a moment."
        states[id] = cur.copy(status = Status.Stopping, restarting = true)
        log(id, "↻ Restart requested", "■ Stopping…")
        later(1200) {
            states[id] = RuntimeState(Status.Stopped, restarting = true)
            log(id, "■ Process exited with code 0", "↻ Restarting…")
            later(800) { start(id) }
        }
        return null
    }

    override fun command(id: String, command: String) {
        sentCommands.add("$id:$command")
        log(id, "> $command")
        if (command == "list") log(id, "00:00:09 INFO There are 0 of a max of 20 players online")
    }

    override fun readConfig(id: String): String = configs[id] ?: "default_gamemode = \"Survival\"\n[networking.java]\nmax_players = 20\n"
    override fun writeConfig(id: String, text: String) { configs[id] = text }
    override fun resetConfig(id: String) { configs.remove(id) }

    override fun engine(): EngineUiState = engineState
    override fun engineRefresh() {}
    override fun engineDownload(remoteId: String): Boolean {
        if (engineState.downloadingId != null) return false
        val remote = engineState.remote.firstOrNull { it.id == remoteId } ?: return false
        engineState = engineState.copy(downloadingId = remoteId, progress = 0f)
        fun finish() {
            engineState = engineState.copy(
                downloadingId = null, progress = 1f,
                installed = engineState.installed + InstalledEngine(remote.id, remote.label, remote.channel.label, remote.size, File("pumpkin")),
            )
        }
        if (instant) finish() else {
            for (step in 1..8) later(step * 350L) { engineState = engineState.copy(progress = step / 8f) }
            later(8 * 350L + 100) { finish() }
        }
        return true
    }
    override fun engineDelete(id: String): Boolean {
        if (engineState.installed.none { it.id == id }) return false
        engineState = engineState.copy(installed = engineState.installed.filterNot { it.id == id })
        return true
    }

    /** Adds a server in a given state, for previews. */
    fun seed(profile: ServerProfile, state: RuntimeState, lines: List<String> = emptyList()) {
        servers.add(profile)
        states[profile.id] = state
        if (lines.isNotEmpty()) log(profile.id, *lines.toTypedArray())
    }

    /** Pretends a player joined, for previews. */
    fun playerJoined(id: String, name: String) {
        val cur = states[id] ?: return
        states[id] = cur.copy(online = cur.online + 1)
        log(id, "00:01:02 INFO $name joined the game")
    }
}
