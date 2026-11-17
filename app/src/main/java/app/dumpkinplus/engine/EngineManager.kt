package app.dumpkinplus.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.SSLException

enum class Channel(val label: String, val tag: String?, val blurb: String) {
    Stable("Stable", null, "Latest tagged release. Best choice for most people."),
    Canary("Canary", "canary", "Rolling build with the newest changes, not fully optimised."),
    Nightly("Nightly", "nightly", "Rolling, fully optimised build."),
    /** Not from Pumpkin: a community 32-bit ARM build of Pumpkin made for Dumpkin+ (Android 5.0+). Released from its own repo. */
    Armv7("32-bit ARM", null, "Unofficial 32-bit ARM build for Android 5.0 and newer. Not tested on many phones yet."),
}

data class InstalledEngine(
    val id: String,
    val label: String,
    val channel: String,
    val sizeBytes: Long,
    val file: File,
    /** Release tag and checksum recorded at install time, used to spot newer builds. Null for imported files. */
    val tag: String? = null,
    val sha256: String? = null,
)

data class RemoteEngine(
    val channel: Channel,
    val tag: String,
    val label: String,
    val assetName: String,
    val url: String,
    val size: Long,
    val updated: String,
    /** Expected SHA-256 of the file (from GitHub's asset digest), checked after the download. */
    val sha256: String? = null,
    val prerelease: Boolean = false,
) {
    val id: String
        get() = when (channel) {
            Channel.Stable -> tag.sanitize()
            Channel.Armv7 -> "armv7-" + tag.sanitize()
            else -> "${channel.name.lowercase()}-" + updated.filter { it.isLetterOrDigit() }
        }
}

data class EngineUiState(
    val installed: List<InstalledEngine> = emptyList(),
    val remote: List<RemoteEngine> = emptyList(),
    val checking: Boolean = false,
    val downloadingId: String? = null,
    val progress: Float = 0f,
    val message: String? = null,
    val importing: Boolean = false,
    /** Newest builds that are newer than what is installed (see [EngineUpdates]). */
    val updates: List<RemoteEngine> = emptyList(),
    /** When the last successful check finished, in epoch millis; 0 if never. */
    val lastChecked: Long = 0L,
)

private fun String.sanitize() = replace(Regex("[^A-Za-z0-9._-]"), "_")

/**
 * Downloads and stores builds of the Pumpkin server. Release assets are discovered through the GitHub API,
 * so the exact asset name (`pumpkin-aarch64-android`) is never hard-coded.
 *
 * Older phones need two extra things the stock app doesn't: a way to **import** an engine file you got some other way
 * (very old Android versions often can't reach GitHub over TLS, and 32-bit phones need a self-built engine), and a
 * check that an engine's CPU architecture matches the phone before it is ever run.
 */
class EngineManager(ctx: Context) {
    private val app = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = File(app.filesDir, "engine").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("dumpkin_engine", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(EngineUiState(installed = scanInstalled(), lastChecked = prefs.getLong("lastChecked", 0L)))
    val state: StateFlow<EngineUiState> = _state.asStateFlow()

    /** This phone's CPU family, from its preferred ABI. */
    val arch: Arch = Arch.fromAbi(Build.SUPPORTED_ABIS.firstOrNull())

    /** Dumpkin+ can run an engine built for these CPUs. The official downloads exist for [Arch.Arm64] only. */
    val supported: Boolean = arch != Arch.Other

    /** True when Pumpkin's own release builds can run here. */
    val officialBuildAvailable: Boolean =
        arch == Arch.Arm64 && Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a") && Build.VERSION.SDK_INT >= OFFICIAL_MIN_API

    /** An arm64 phone that is too old for the official build: it imports getifaddrs(), which Android only has from 7.0. */
    val needsNewerAndroid: Boolean = arch == Arch.Arm64 && Build.VERSION.SDK_INT < OFFICIAL_MIN_API

    /** This phone can run 32-bit ARM programs: it is a 32-bit phone, or a 64-bit one that still supports 32-bit apps. */
    private val runs32BitArm: Boolean = Build.SUPPORTED_32_BIT_ABIS.any { it.startsWith("armeabi") }

    /** The community 32-bit ARM build is offered when the official one can't run here. It is built for Android 5.0+. */
    val communityBuildAvailable: Boolean = !officialBuildAvailable && runs32BitArm

    /** There is something to download for this phone. */
    val hasRemote: Boolean get() = officialBuildAvailable || communityBuildAvailable

    /** An engine built for [elf] can run here: same CPU, or 32-bit ARM on a phone that supports it (including arm64 ones). */
    private fun canRun(elf: Arch): Boolean = elf == arch || (elf == Arch.Arm32 && runs32BitArm)

    /** Whether to look for new engine builds by itself. On by default; it is one small request every few hours. */
    var autoCheck: Boolean
        get() = prefs.getBoolean("autoCheck", true)
        set(v) {
            prefs.edit().putBoolean("autoCheck", v).apply()
        }

    /** Called when the app starts: looks for new builds if it is enabled and the last check is old enough. */
    fun autoCheckIfDue(now: Long = System.currentTimeMillis()) {
        if (!autoCheck || !hasRemote) return
        if (now - prefs.getLong("lastChecked", 0L) < AUTO_CHECK_INTERVAL_MS) return
        checkRemote()
    }

    fun resolve(id: String?): InstalledEngine? {
        val list = _state.value.installed
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    fun checkRemote() {
        if (_state.value.checking) return
        scope.launch {
            _state.update { it.copy(checking = true, message = null) }
            var failure: Exception? = null
            val channels = buildList {
                if (officialBuildAvailable) addAll(listOf(Channel.Stable, Channel.Canary, Channel.Nightly))
                if (communityBuildAvailable) add(Channel.Armv7)
            }
            val found = channels.flatMap { ch ->
                try {
                    fetchAll(ch)
                } catch (e: Exception) {
                    failure = e
                    emptyList()
                }
            }
            val now = System.currentTimeMillis()
            // A check that reached GitHub counts, even if it found nothing, so auto-check doesn't retry on every launch.
            if (failure == null) prefs.edit().putLong("lastChecked", now).apply()
            _state.update {
                it.copy(
                    checking = false,
                    remote = found,
                    updates = EngineUpdates.compute(it.installed, found),
                    lastChecked = if (failure == null) now else it.lastChecked,
                    message = when {
                        found.isNotEmpty() -> null
                        needsNewerAndroid && !communityBuildAvailable -> NEEDS_NEWER_ANDROID
                        !hasRemote ->
                            "Pumpkin doesn't publish a build for ${arch.label} phones. Build one yourself and use Import (see the README)."
                        failure != null -> explain(failure!!, "Couldn't reach GitHub")
                        else -> "Couldn't find an Android build. Check your connection and try again."
                    },
                )
            }
        }
    }

    fun download(r: RemoteEngine) {
        if (_state.value.downloadingId != null) return
        scope.launch {
            _state.update { it.copy(downloadingId = r.id, progress = 0f, message = null) }
            val dir = File(root, r.id).apply { mkdirs() }
            try {
                val part = File(dir, "pumpkin.part")
                val conn = open(r.url, "application/octet-stream")
                // getContentLengthLong() is Android 7+; the Int version is plenty for a ~50 MB file.
                val total = conn.contentLength.toLong().takeIf { it > 0 } ?: r.size
                var done = 0L
                var lastPublished = 0f
                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done.toFloat() / total).coerceIn(0f, 1f)
                                if (p - lastPublished >= 0.02f) {
                                    lastPublished = p
                                    _state.update { it.copy(progress = p) }
                                }
                            }
                        }
                    }
                }
                // A truncated or tampered download must never be installed: check it against GitHub's own checksum.
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (r.sha256 != null && !actual.equals(r.sha256, ignoreCase = true)) {
                    throw IOException("the file's checksum doesn't match what GitHub reports, so it was discarded")
                }
                install(
                    part, dir,
                    JSONObject().put("label", r.label).put("channel", r.channel.label).put("tag", r.tag).put("sha256", actual),
                )
                refreshInstalled { it.copy(downloadingId = null, progress = 1f) }
            } catch (e: Exception) {
                dir.deleteRecursively()
                _state.update { it.copy(downloadingId = null, message = explain(e, "Download failed")) }
            }
        }
    }

    /** Copies an engine the user picked with the system file chooser into the engine folder. */
    fun importFrom(uri: Uri) {
        if (_state.value.importing || _state.value.downloadingId != null) return
        scope.launch {
            _state.update { it.copy(importing = true, message = null) }
            val id = "imported-" + System.currentTimeMillis()
            val dir = File(root, id).apply { mkdirs() }
            try {
                val name = displayName(uri) ?: "engine"
                val part = File(dir, "pumpkin.part")
                val input = app.contentResolver.openInputStream(uri) ?: throw IOException("couldn't open the file")
                input.use { i -> part.outputStream().use { o -> i.copyTo(o, 32 * 1024) } }
                install(part, dir, JSONObject().put("label", "Imported · $name").put("channel", "Imported"))
                refreshInstalled { it.copy(importing = false) }
            } catch (e: Exception) {
                dir.deleteRecursively()
                _state.update { it.copy(importing = false, message = "Import failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        refreshInstalled { it }
    }

    /** Checks that [part] is an executable for this phone's CPU, then moves it into place as the engine binary. */
    private fun install(part: File, dir: File, meta: JSONObject) {
        val head = ByteArray(ElfInfo.HEADER_SIZE)
        val read = part.inputStream().use { it.read(head) }
        val elf = ElfInfo.parse(if (read == head.size) head else head.copyOf(maxOf(read, 0)))
            ?: throw IOException("that file is not an executable")
        if (!canRun(elf.arch)) {
            throw IOException("it was built for ${elf.arch.label} but this phone is ${arch.label}")
        }
        val bin = File(dir, "pumpkin")
        bin.delete()
        check(part.renameTo(bin)) { "could not move the file into place" }
        bin.setExecutable(true, false)
        File(dir, "meta.json").writeText(meta.toString())
    }

    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    /** Plain-language reasons, with the fix that matters on old phones: out-of-date certificates. */
    private fun explain(e: Exception, prefix: String): String = when (e) {
        is SSLException ->
            "$prefix: this phone's security certificates look too old to connect to GitHub. " +
                "Download the engine on a computer, copy it to the phone and use Import instead."
        else -> "$prefix: ${e.message ?: e.javaClass.simpleName}"
    }

    /** Matches the release asset built for this phone's CPU. Only arm64 has official builds today. */
    private fun wantedAsset(name: String): Boolean {
        val n = name.lowercase()
        if (!n.contains("android") || n.endsWith(".sig") || n.endsWith(".sha256")) return false
        return when (arch) {
            Arch.Arm64 -> n.contains("aarch64") || n.contains("arm64")
            Arch.Arm32 -> n.contains("armv7") || n.contains("armeabi")
            Arch.X64 -> n.contains("x86_64") || n.contains("x64")
            Arch.X86 -> n.contains("i686") || n.contains("x86") && !n.contains("64")
            Arch.Other -> false
        }
    }

    /** The engines on offer for [ch]: one for each of Pumpkin's channels, or the community repo's newest few releases. */
    private fun fetchAll(ch: Channel): List<RemoteEngine> {
        val community = ch == Channel.Armv7
        val api = "https://api.github.com/repos/" + (if (community) COMMUNITY_ARMV7_REPO else "Pumpkin-MC/Pumpkin") + "/releases"
        if (community) {
            // The list, not /latest: /latest hides pre-releases, and new builds are often published as pre-releases.
            val conn = open("$api?per_page=${EngineReleases.COMMUNITY_LIMIT * 2}", "application/vnd.github+json")
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            return EngineReleases.parseList(arr, ch, EngineReleases::isArmv7Engine)
        }
        val url = if (ch.tag == null) "$api/latest" else "$api/tags/${ch.tag}"
        val conn = open(url, "application/vnd.github+json")
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        return listOfNotNull(EngineReleases.parseRelease(json, ch, ::wantedAsset))
    }

    /** Re-reads what is installed and recomputes which builds are now out of date. */
    private fun refreshInstalled(also: (EngineUiState) -> EngineUiState) {
        val installed = scanInstalled()
        _state.update { st -> also(st).copy(installed = installed, updates = EngineUpdates.compute(installed, st.remote)) }
    }

    private fun open(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000 // old phones on slow Wi-Fi
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "Dumpkin-Plus-Android")
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        return c
    }

    private fun scanInstalled(): List<InstalledEngine> =
        root.listFiles { f -> f.isDirectory }.orEmpty()
            .filter { File(it, "pumpkin").isFile }
            .sortedByDescending { File(it, "pumpkin").lastModified() }
            .map { dir ->
                val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull()
                val bin = File(dir, "pumpkin")
                InstalledEngine(
                    id = dir.name,
                    label = meta?.optString("label").orEmpty().ifBlank { dir.name },
                    channel = meta?.optString("channel").orEmpty(),
                    sizeBytes = bin.length(),
                    file = bin,
                    tag = meta?.optString("tag")?.takeIf { it.isNotBlank() },
                    sha256 = meta?.optString("sha256")?.takeIf { it.isNotBlank() },
                )
            }
}

/** The official Pumpkin Android build is linked for API 24 and imports getifaddrs(), so it can't load on Android 5 or 6. */
const val OFFICIAL_MIN_API = 24

/** How often the automatic check may run: GitHub allows 60 anonymous requests an hour, and old phones have little to spare. */
const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

/** Where the community 32-bit ARM build of Pumpkin (Android 5.0+) is released. */
const val COMMUNITY_ARMV7_REPO = "colebolebole/pumpkin-android-armv7"

const val NEEDS_NEWER_ANDROID =
    "Pumpkin's official Android build needs Android 7.0 or newer, so it can't start on this phone. " +
        "Import an engine built for older Android instead (see the README)."
