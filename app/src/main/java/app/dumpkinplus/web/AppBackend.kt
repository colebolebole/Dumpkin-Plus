package app.dumpkinplus.web

import android.app.Application
import app.dumpkinplus.data.RuntimeState
import app.dumpkinplus.data.ServerProfile
import app.dumpkinplus.data.deviceMemory
import app.dumpkinplus.engine.EngineManager
import app.dumpkinplus.engine.EngineUiState
import app.dumpkinplus.server.LogSlice
import app.dumpkinplus.server.ServerManager

/** The real implementation of [WebBackend], backed by the app's server and engine managers. */
class AppBackend(
    private val app: Application,
    private val manager: ServerManager,
    private val engines: EngineManager,
) : WebBackend {
    override val appVersion: String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    override val engineSupported: Boolean get() = engines.supported

    override fun device(): DeviceSnapshot {
        val mem = deviceMemory(app)
        return DeviceSnapshot(mem.totalMb, mem.availMb, Runtime.getRuntime().availableProcessors(), ServerManager.localAddresses())
    }

    override fun profiles(): List<ServerProfile> = manager.profiles.value
    override fun runtime(id: String): RuntimeState? = manager.runtime(id)?.state?.value
    override fun logsSince(id: String, cursor: Long): LogSlice? = manager.runtime(id)?.logsSince(cursor)

    override fun create(profile: ServerProfile) = manager.create(profile)
    override fun update(profile: ServerProfile) = manager.update(profile)
    override fun delete(id: String) = manager.delete(id)

    override fun start(id: String): String? = manager.start(id)
    override fun stop(id: String) = manager.stop(id)
    override fun restart(id: String): String? = manager.restart(id)
    override fun command(id: String, command: String) = manager.command(id, command)

    override fun readConfig(id: String): String = manager.readRawConfig(id)

    override fun writeConfig(id: String, text: String) {
        manager.writeRawConfig(id, text)
        // Same as the in-app editor: fold values edited in the file back into the saved settings.
        profiles().firstOrNull { it.id == id }?.let { manager.update(manager.pullConfig(it)) }
    }

    override fun resetConfig(id: String) {
        profiles().firstOrNull { it.id == id }?.let { manager.resetConfig(it) }
    }

    override fun engine(): EngineUiState = engines.state.value
    override fun engineRefresh() = engines.checkRemote()

    override fun engineDownload(remoteId: String): Boolean {
        val state = engines.state.value
        if (state.downloadingId != null) return false
        val remote = state.remote.firstOrNull { it.id == remoteId } ?: return false
        engines.download(remote)
        return true
    }

    override fun engineDelete(id: String): Boolean {
        if (engines.state.value.installed.none { it.id == id }) return false
        engines.delete(id)
        return true
    }
}
