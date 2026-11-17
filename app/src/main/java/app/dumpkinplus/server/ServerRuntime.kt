package app.dumpkinplus.server

import android.os.Build
import app.dumpkinplus.data.MemoryAction
import app.dumpkinplus.data.RuntimeState
import app.dumpkinplus.data.ServerProfile
import app.dumpkinplus.data.Status
import app.dumpkinplus.engine.ElfInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** One Pumpkin process plus its console log. Survives UI recreation because [ServerManager] owns it. */
class ServerRuntime(
    private val tempDir: File,
    @Volatile var profile: ServerProfile,
    private val scope: CoroutineScope,
    private val onStatus: (ServerRuntime, Status) -> Unit,
    private val onRestart: (String) -> Unit,
) {
    val state = MutableStateFlow(RuntimeState())
    val logs = MutableStateFlow<List<String>>(emptyList())

    @Volatile private var process: Process? = null
    @Volatile private var stopRequested = false
    @Volatile private var everRan = false
    @Volatile private var restartAfterExit = false
    private val crashTimes = ArrayDeque<Long>()

    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    private fun setState(transform: (RuntimeState) -> RuntimeState) {
        val old = state.value.status
        val new = state.updateAndGet(transform)
        if (new.status != old) onStatus(this, new.status)
    }

    // Log lines are queued and published in batches: during startup Pumpkin prints hundreds of lines,
    // and rebuilding a 2000-entry list per line would steal CPU from the server itself.
    private val pending = ConcurrentLinkedQueue<String>()
    private val flushScheduled = AtomicBoolean(false)

    fun appendLog(line: String) {
        pending.add(ansi.replace(line, "").trimEnd())
        if (flushScheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(LOG_FLUSH_MS)
                flushScheduled.set(false)
                flushLogs()
            }
        }
    }

    private fun flushLogs() {
        if (pending.isEmpty()) return
        val batch = ArrayList<String>()
        while (true) batch.add(pending.poll() ?: break)
        history.append(batch)
        logs.value = history.snapshot()
    }

    fun clearLogs() {
        pending.clear()
        history.clear()
        logs.value = emptyList()
    }

    // Numbered history for remote viewers (the web dashboard asks for "everything after line N").
    private val history = LogBuffer(MAX_LINES)

    fun logsSince(cursor: Long): LogSlice = history.slice(cursor)

    fun start(binary: File, dir: File) {
        if (state.value.isActive) return
        stopRequested = false
        restartAfterExit = false
        everRan = false
        setState { RuntimeState(status = Status.Starting, startedAt = System.currentTimeMillis()) }
        appendLog("▶ Starting \"${profile.name}\" on port ${profile.port}")
        scope.launch { run(binary, dir) }
    }

    private suspend fun run(binary: File, dir: File) {
        val proc = try {
            spawn(binary, dir)
        } catch (e: Exception) {
            appendLog("✖ Could not launch the engine: ${e.message}")
            appendLog("  Was the engine installed from the Engine tab, and is it built for this phone's CPU?")
            setState { RuntimeState(status = Status.Crashed, error = e.message) }
            return
        }
        process = proc

        val reader = scope.launch(Dispatchers.IO) {
            try {
                proc.inputStream.bufferedReader().useLines { seq -> seq.forEach { appendLog(it) } }
            } catch (_: IOException) {
            }
        }
        val pinger = scope.launch(Dispatchers.IO) {
            while (isActive) {
                // Poll fast while starting so "Online" shows the moment the port opens; relax afterwards so
                // the app isn't adding handshake traffic (and log noise) to a server that's busy hosting.
                delay(if (state.value.status == Status.Starting) 250 else 10_000)
                val r = StatusPing.query("127.0.0.1", profile.port) ?: continue
                setState {
                    when (it.status) {
                        Status.Starting -> {
                            everRan = true
                            it.copy(status = Status.Running, online = r.online, max = r.max, version = r.version)
                        }
                        Status.Running, Status.Stopping -> it.copy(online = r.online, max = r.max, version = r.version)
                        else -> it
                    }
                }
            }
        }

        val monitor = scope.launch(Dispatchers.IO) { watchMemory(proc) }

        val code = withContext(Dispatchers.IO) { proc.waitFor() }
        pinger.cancel()
        monitor.cancel()
        withTimeoutOrNull(2000) { reader.join() }
        process = null

        appendLog("■ Process exited with code $code")
        if (!everRan && !stopRequested && Build.VERSION.SDK_INT < 24) {
            appendLog("ℹ The official Pumpkin build needs Android 7.0 or newer. On Android 5 and 6 it fails to load; import an engine built for older Android.")
        }
        val crashed = !stopRequested && (code != 0 || !everRan)
        val willRestart = restartAfterExit
        setState {
            RuntimeState(
                status = if (crashed) Status.Crashed else Status.Stopped,
                error = if (crashed) "Exited with code $code" else null,
                // Keeps the UI on "Restarting…" through the short gap before the new process launches.
                restarting = willRestart,
            )
        }

        val id = profile.id
        when {
            willRestart -> {
                restartAfterExit = false
                appendLog("↻ Restarting…")
                scope.launch { delay(2000); onRestart(id) }
            }
            crashed && profile.autoRestart -> {
                val now = System.currentTimeMillis()
                crashTimes.addLast(now)
                while (crashTimes.isNotEmpty() && now - crashTimes.first() > 5 * 60_000) crashTimes.removeFirst()
                if (crashTimes.size <= MAX_AUTO_RESTARTS) {
                    appendLog("↻ Crash detected, restarting in 5s (${crashTimes.size}/$MAX_AUTO_RESTARTS)…")
                    scope.launch { delay(5000); onRestart(id) }
                } else {
                    appendLog("✖ Crashed $MAX_AUTO_RESTARTS times in 5 minutes, not restarting again.")
                }
            }
        }
    }

    /** Samples the server's resident memory and enforces the profile's soft RAM limit. */
    private suspend fun watchMemory(proc: Process) {
        val pid = pidOf(proc) ?: run {
            appendLog("ℹ Memory usage isn't available on this device.")
            return
        }
        var over = 0
        var handled = false
        while (currentCoroutineContext().isActive) {
            delay(2000)
            val rss = readRssMb(pid) ?: continue
            setState { if (it.isActive) it.copy(rssMb = rss) else it }
            val limit = profile.memoryLimitMb
            if (limit <= 0) { over = 0; handled = false; continue }
            if (rss > limit) {
                over++
                if (over >= 2 && !handled) {
                    handled = true
                    when (profile.memoryAction) {
                        MemoryAction.Warn ->
                            appendLog("⚠ Memory use ($rss MB) is above the $limit MB limit.")
                        MemoryAction.Restart -> {
                            appendLog("⚠ Memory use ($rss MB) is above the $limit MB limit, restarting to free it.")
                            requestRestart()
                            stop()
                        }
                    }
                }
            } else {
                over = 0
                if (rss < limit * 0.9) handled = false
            }
        }
    }

    /** Process.isAlive() is Java 8 (Android 8+); exitValue() throws while the process is still running, on every version. */
    private fun Process.alive(): Boolean = try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    /** Process.destroyForcibly() is Android 8+. Before that, SIGKILL the child directly. */
    private fun forceKill(p: Process) {
        if (Build.VERSION.SDK_INT >= 26) {
            p.destroyForcibly()
        } else {
            pidOf(p)?.let { android.os.Process.killProcess(it) } ?: p.destroy()
        }
    }

    private fun pidOf(p: Process): Int? =
        runCatching { (Process::class.java.getMethod("pid").invoke(p) as Number).toInt() }.getOrNull()
            ?: runCatching { p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p) }.getOrNull()

    private fun readRssMb(pid: Int): Int? = runCatching {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.filter(Char::isDigit)?.toLong()?.div(1024)?.toInt()
        }
    }.getOrNull()

    private fun spawn(binary: File, dir: File): Process {
        fun builder(cmd: List<String>) = ProcessBuilder(cmd)
            .directory(dir)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    put("HOME", dir.absolutePath)
                    put("TMPDIR", tempDir.absolutePath)
                    put("TERM", "dumb")
                    put("NO_COLOR", "1")
                    put("RUST_BACKTRACE", "1")
                    if (profile.cpuThreads > 0) {
                        put("TOKIO_WORKER_THREADS", profile.cpuThreads.toString())
                        put("RAYON_NUM_THREADS", profile.cpuThreads.toString())
                    }
                }
            }
        return try {
            builder(listOf(binary.absolutePath)).start()
        } catch (e: IOException) {
            // Some devices refuse a direct exec from app storage; going through the system linker often works.
            // A 32-bit engine (the community armv7 build, even on a 64-bit phone) needs the 32-bit linker.
            val is64 = runCatching {
                val head = ByteArray(ElfInfo.HEADER_SIZE)
                binary.inputStream().use { it.read(head) }
                ElfInfo.parse(head)?.is64Bit
            }.getOrNull() ?: true
            val linkers = if (is64) listOf("/system/bin/linker64", "/system/bin/linker") else listOf("/system/bin/linker")
            val linker = linkers.firstOrNull { File(it).exists() } ?: throw e
            appendLog("Direct launch was blocked (${e.message}); retrying via the system linker…")
            builder(listOf(linker, binary.absolutePath)).start()
        }
    }

    fun sendCommand(cmd: String) {
        val p = process ?: return
        scope.launch(Dispatchers.IO) {
            try {
                p.outputStream.write((cmd + "\n").toByteArray())
                p.outputStream.flush()
                appendLog("> $cmd")
            } catch (e: IOException) {
                appendLog("✖ Could not send command: ${e.message}")
            }
        }
    }

    /**
     * Stops the server, then starts it again once the process has exited, with the settings as they are by then.
     * @return false when there is no live process to restart (it has not finished launching, or is not running).
     */
    fun restart(): Boolean {
        if (!state.value.isActive || process == null) return false
        appendLog("↻ Restart requested")
        requestRestart()
        stop() // does nothing if it is already stopping; the restart still happens after it exits
        return true
    }

    private fun requestRestart() {
        restartAfterExit = true
        setState { it.copy(restarting = true) }
    }

    /** Called when the relaunch could not happen, so the UI stops saying "Restarting…". */
    fun clearRestarting() = setState { it.copy(restarting = false) }

    fun stop() {
        val p = process ?: return
        if (!state.value.isActive || state.value.status == Status.Stopping) return
        stopRequested = true
        setState { it.copy(status = Status.Stopping) }
        appendLog("■ Stopping…")
        scope.launch(Dispatchers.IO) {
            sendCommand("stop")
            var waited = 0
            while (p.alive() && waited < 15_000) {
                delay(250); waited += 250
            }
            if (p.alive()) {
                appendLog("Server did not stop in time, terminating…")
                p.destroy()
                delay(4000)
                if (p.alive()) forceKill(p)
            }
        }
    }

    companion object {
        const val MAX_LINES = 1000 // older phones have little RAM to spare for scrollback
        const val LOG_FLUSH_MS = 250L
        const val MAX_AUTO_RESTARTS = 3
    }
}
