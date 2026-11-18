package app.dumpkinplus.backup

import app.dumpkinplus.files.FileAccessException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class BackupKind(val suffix: String, val label: String) {
    Manual("", "Manual"),
    Auto("-auto", "Automatic"),
    PreRestore("-prerestore", "Before a restore"),
    Imported("-imported", "Imported"),
}

data class Backup(val name: String, val file: File, val createdAt: Long, val sizeBytes: Long, val kind: BackupKind)

/** What is happening to a server's backups right now, for both UIs. */
data class BackupStatus(val busy: String? = null, val message: String? = null, val error: Boolean = false)

/**
 * Zip backups of server folders, one folder of backups per server. The file work is plain java.io/java.util.zip so it is
 * tested on the JVM; the `*Async` functions run it on a background thread and report through [status].
 */
class BackupManager(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<Map<String, BackupStatus>>(emptyMap())
    val status: StateFlow<Map<String, BackupStatus>> = _status.asStateFlow()

    fun statusOf(id: String): BackupStatus = _status.value[id] ?: BackupStatus()

    private fun dir(id: String): File = File(root, id)

    private val stamp get() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    // ---- reading -------------------------------------------------------------------------------------------------

    /** Newest first. */
    fun list(id: String): List<Backup> =
        dir(id).listFiles { f -> f.isFile && validName(f.name) }.orEmpty().map { toBackup(it) }.sortedByDescending { it.createdAt }

    /** The file for [name], or null if there is no such backup. Names are checked so they can't point elsewhere. */
    fun file(id: String, name: String): File? {
        if (!validName(name)) return null
        return File(dir(id), name).takeIf { it.isFile }
    }

    private fun validName(name: String) = NAME.matches(name)

    private fun toBackup(f: File): Backup {
        val kind = BackupKind.entries.filter { it != BackupKind.Manual }.firstOrNull { f.name.endsWith(it.suffix + ".zip") } ?: BackupKind.Manual
        val created = runCatching { stamp.parse(f.name.removePrefix("backup-").take(15))?.time }.getOrNull() ?: f.lastModified()
        return Backup(f.name, f, created, f.length(), kind)
    }

    // ---- the operations (blocking) -------------------------------------------------------------------------------

    /** Zips [serverDir] into a new backup. */
    fun create(id: String, serverDir: File, kind: BackupKind = BackupKind.Manual): Backup {
        if (!serverDir.isDirectory) throw FileAccessException("This server has no files yet. Start it once first.")
        val out = dir(id).apply { mkdirs() }
        val needed = sizeOf(serverDir) + MIN_FREE
        if (out.usableSpace < needed) {
            throw IOException("Not enough free space for a backup (needs about ${needed / 1_048_576} MB).")
        }
        val name = uniqueName(out, kind)
        val tmp = File(out, "$name.part")
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED) // worlds are already compressed; speed matters more on old CPUs
                addTree(zip, serverDir, "")
            }
            val dest = File(out, name)
            if (!tmp.renameTo(dest)) throw IOException("Couldn't save the backup.")
            return toBackup(dest)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Replaces [serverDir] with the contents of backup [name]. The current files are kept in a safety backup first, and
     * if anything goes wrong while swapping, the original folder is put back.
     */
    fun restore(id: String, serverDir: File, name: String) {
        val zipFile = file(id, name) ?: throw FileAccessException("That backup doesn't exist.")
        verify(zipFile)
        if (serverDir.isDirectory && serverDir.list().orEmpty().isNotEmpty()) create(id, serverDir, BackupKind.PreRestore)

        val parent = serverDir.absoluteFile.parentFile ?: throw IOException("No parent folder.")
        val fresh = File(parent, serverDir.name + ".restore-tmp")
        val old = File(parent, serverDir.name + ".restore-old")
        fresh.deleteRecursively()
        old.deleteRecursively()
        try {
            extract(zipFile, fresh)
            if (serverDir.exists() && !serverDir.renameTo(old)) throw IOException("Couldn't move the current files aside.")
            if (!fresh.renameTo(serverDir)) {
                old.renameTo(serverDir)
                throw IOException("Couldn't put the restored files in place. Nothing was changed.")
            }
            old.deleteRecursively()
        } finally {
            fresh.deleteRecursively()
        }
    }

    fun delete(id: String, name: String): Boolean = file(id, name)?.delete() ?: false

    fun deleteAll(id: String) {
        dir(id).deleteRecursively()
        _status.update { it - id }
    }

    /** Keeps the newest [keep] automatic backups and removes the rest. Manual and imported backups are never touched. */
    fun pruneAuto(id: String, keep: Int) {
        list(id).filter { it.kind == BackupKind.Auto }.drop(keep).forEach { it.file.delete() }
    }

    /** Copies a backup chosen by the user into this server's backups. It must be a readable zip. */
    fun importZip(id: String, input: InputStream): Backup {
        val out = dir(id).apply { mkdirs() }
        val name = uniqueName(out, BackupKind.Imported)
        val tmp = File(out, "$name.part")
        try {
            tmp.outputStream().use { o -> input.copyTo(o, 32 * 1024) }
            verify(tmp)
            val dest = File(out, name)
            if (!tmp.renameTo(dest)) throw IOException("Couldn't save the backup.")
            return toBackup(dest)
        } finally {
            tmp.delete()
        }
    }

    fun export(id: String, name: String, to: OutputStream) {
        val f = file(id, name) ?: throw FileAccessException("That backup doesn't exist.")
        f.inputStream().use { it.copyTo(to, 32 * 1024) }
    }

    // ---- the same, in the background -----------------------------------------------------------------------------

    /**
     * Runs [work] off the main thread while the server shows [label] as busy. A server can only do one thing at a time.
     * @return false if it is already busy.
     */
    fun start(id: String, label: String, work: () -> String): Boolean {
        var claimed = false
        _status.update { m ->
            if (m[id]?.busy != null) m else {
                claimed = true
                m + (id to BackupStatus(busy = label))
            }
        }
        if (!claimed) return false
        scope.launch {
            val result = try {
                BackupStatus(message = work())
            } catch (e: Exception) {
                BackupStatus(message = e.message ?: e.javaClass.simpleName, error = true)
            }
            _status.update { m -> if (id in m) m + (id to result) else m } // dropped if the server was deleted meanwhile
        }
        return true
    }

    /** Exports backup [name] to wherever [open] points (a file the user chose), in the background. */
    fun runExport(id: String, name: String, open: () -> OutputStream) {
        start(id, "Exporting…") {
            open().use { export(id, name, it) }
            "Exported $name."
        }
    }

    // ---- zip internals -------------------------------------------------------------------------------------------

    private fun addTree(zip: ZipOutputStream, dir: File, prefix: String) {
        for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
            if (f.name.endsWith(".restore-tmp") || f.name.endsWith(".restore-old")) continue
            val entry = prefix + f.name
            if (f.isDirectory) {
                zip.putNextEntry(ZipEntry("$entry/").apply { time = f.lastModified() })
                zip.closeEntry()
                addTree(zip, f, "$entry/")
            } else if (f.isFile) {
                zip.putNextEntry(ZipEntry(entry).apply { time = f.lastModified() })
                try {
                    f.inputStream().use { it.copyTo(zip, 64 * 1024) }
                } catch (_: IOException) {
                    // A file that vanished or is locked while the server writes: keep what was read, carry on.
                }
                zip.closeEntry()
            }
        }
    }

    private fun verify(zipFile: File) {
        try {
            ZipFile(zipFile).use { z ->
                if (!z.entries().hasMoreElements()) throw FileAccessException("That backup is empty.")
            }
        } catch (e: FileAccessException) {
            throw e
        } catch (_: Exception) {
            throw FileAccessException("That isn't a valid backup (a zip file).")
        }
    }

    private fun extract(zipFile: File, into: File) {
        into.mkdirs()
        val base = into.canonicalFile
        ZipFile(zipFile).use { z ->
            for (e in z.entries()) {
                val target = File(base, e.name).canonicalFile
                // A crafted zip must not write outside the folder it is restored into.
                if (target != base && !target.path.startsWith(base.path + File.separator)) {
                    throw FileAccessException("That backup contains an unsafe path, so it was not restored.")
                }
                if (e.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    z.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it, 64 * 1024) } }
                    if (e.time > 0) target.setLastModified(e.time)
                }
            }
        }
    }

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun uniqueName(dir: File, kind: BackupKind): String {
        var t = clock()
        while (true) {
            val name = "backup-" + stamp.format(Date(t)) + kind.suffix + ".zip"
            if (!File(dir, name).exists() && !File(dir, "$name.part").exists()) return name
            t += 1000 // two backups in the same second
        }
    }

    companion object {
        const val AUTO_KEEP = 5
        private const val MIN_FREE = 32L * 1024 * 1024
        private val NAME = Regex("""^backup-\d{8}-\d{6}(-auto|-prerestore|-imported)?\.zip$""")
    }
}
