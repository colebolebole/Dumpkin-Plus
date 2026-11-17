package app.dumpkinplus.web

import app.dumpkinplus.data.RuntimeState
import app.dumpkinplus.data.ServerProfile
import app.dumpkinplus.engine.EngineUiState
import app.dumpkinplus.server.LogSlice

data class DeviceSnapshot(
    val totalMb: Int,
    val availMb: Int,
    val cores: Int,
    /** Addresses other devices on the network can use to reach this phone. */
    val addresses: List<String>,
)

/**
 * Everything the web dashboard can see or do. [AppBackend] implements it on top of the real app; tests and the
 * preview use a fake, which keeps the whole HTTP stack testable on a plain JVM.
 */
interface WebBackend {
    val appVersion: String

    /** False on CPUs the Pumpkin Android build can't run on (it is arm64 only). */
    val engineSupported: Boolean

    fun device(): DeviceSnapshot

    fun profiles(): List<ServerProfile>
    fun runtime(id: String): RuntimeState?
    fun logsSince(id: String, cursor: Long): LogSlice?

    fun create(profile: ServerProfile)
    fun update(profile: ServerProfile)
    fun delete(id: String)

    /** @return null on success, otherwise a message explaining why the server can't start. */
    fun start(id: String): String?
    fun stop(id: String)

    /**
     * Restarts a running server (a stopped one is started). @return null on success, otherwise a message explaining
     * why it can't be restarted right now.
     */
    fun restart(id: String): String?
    fun command(id: String, command: String)

    fun readConfig(id: String): String
    fun writeConfig(id: String, text: String)
    fun resetConfig(id: String)

    fun engine(): EngineUiState
    fun engineRefresh()
    /** @param remoteId id of an entry in [EngineUiState.remote]. @return false if there is no such build or a download is running. */
    fun engineDownload(remoteId: String): Boolean
    fun engineDelete(id: String): Boolean
}
