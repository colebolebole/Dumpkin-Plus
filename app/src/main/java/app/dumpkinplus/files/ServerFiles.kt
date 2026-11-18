package app.dumpkinplus.files

import java.io.File
import java.io.IOException
import java.io.InputStream

/** One file or folder in a server's folder. [path] is relative to the server folder, uses `/`, and has no leading slash. */
data class FileEntry(val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long)

/** Something the user asked for isn't allowed or isn't possible; the message is meant to be shown as is. */
class FileAccessException(message: String) : IOException(message)

/**
 * Safe access to one server's folder, shared by the app and the web dashboard. Every path is resolved against the
 * folder's real location, so `..`, absolute paths and symlinks can't reach anything outside it.
 */
class ServerFiles(root: File) {
    private val root: File = root.canonicalFile

    /** The folder-relative path of [file], or "" for the folder itself. */
    fun relative(file: File): String {
        val c = file.canonicalFile
        if (c == root) return ""
        return c.path.removePrefix(root.path + File.separator).replace(File.separatorChar, '/')
    }

    /** @throws FileAccessException if [rel] points outside the server folder. */
    fun resolve(rel: String): File {
        val clean = rel.replace('\\', '/').trim('/')
        if (clean.contains('\u0000') || clean.split('/').any { it == ".." }) throw FileAccessException("That path isn't allowed.")
        val target = (if (clean.isEmpty()) root else File(root, clean)).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw FileAccessException("That path isn't allowed.")
        }
        return target
    }

    /** Folders first, then files, each A to Z ignoring case. */
    fun list(rel: String): List<FileEntry> {
        val dir = resolve(rel)
        if (!dir.exists()) return emptyList()
        if (!dir.isDirectory) throw FileAccessException("That isn't a folder.")
        return dir.listFiles().orEmpty()
            .map { FileEntry(it.name, relative(it), it.isDirectory, if (it.isDirectory) 0L else it.length(), it.lastModified()) }
            .sortedWith(compareBy<FileEntry>({ !it.isDir }, { it.name.lowercase() }))
    }

    /** Looks like text: known extension, or no NUL bytes and valid UTF-8 in the first few KB. */
    fun isText(file: File): Boolean {
        if (!file.isFile) return false
        if (file.length() == 0L) return true
        val head = ByteArray(minOf(file.length(), 4096L).toInt())
        val n = file.inputStream().use { it.read(head) }
        if (n <= 0) return true
        if (head.take(n).any { it == 0.toByte() }) return false
        // A multi-byte character may be cut at the end of the sample, so only judge the part before the last 3 bytes.
        return runCatching {
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.decode(java.nio.ByteBuffer.wrap(head, 0, maxOf(0, n - 3)))
        }.isSuccess
    }

    fun readText(rel: String): String {
        val f = resolve(rel)
        if (!f.isFile) throw FileAccessException("That file doesn't exist.")
        if (f.length() > MAX_TEXT_BYTES) throw FileAccessException("That file is too large to edit here (over ${MAX_TEXT_BYTES / 1000} KB).")
        if (!isText(f)) throw FileAccessException("That isn't a text file.")
        return f.readText(Charsets.UTF_8)
    }

    /** Replaces an existing text file. New files are added with [write]. */
    fun writeText(rel: String, text: String) {
        val f = resolve(rel)
        if (!f.isFile) throw FileAccessException("That file doesn't exist.")
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) throw FileAccessException("That text is too large.")
        if (text.contains('\u0000')) throw FileAccessException("That isn't valid text.")
        f.writeText(text, Charsets.UTF_8)
    }

    /** Creates a file called [name] in folder [dirRel] from [input]. Won't replace an existing file unless [overwrite]. */
    fun write(dirRel: String, name: String, input: InputStream, overwrite: Boolean = false): File {
        val dir = resolve(dirRel)
        checkName(name)
        if (!dir.isDirectory) throw FileAccessException("That folder doesn't exist.")
        val target = resolve(relative(dir).let { if (it.isEmpty()) name else "$it/$name" })
        if (target.exists() && !overwrite) throw FileAccessException("\"$name\" already exists here.")
        if (target.isDirectory) throw FileAccessException("\"$name\" is a folder.")
        val tmp = File(dir, "$name.part")
        try {
            tmp.outputStream().use { out -> input.copyTo(out, 32 * 1024) }
            target.delete()
            if (!tmp.renameTo(target)) throw FileAccessException("Couldn't save \"$name\".")
        } finally {
            tmp.delete()
        }
        return target
    }

    fun mkdir(dirRel: String, name: String) {
        val dir = resolve(dirRel)
        checkName(name)
        val target = resolve(relative(dir).let { if (it.isEmpty()) name else "$it/$name" })
        if (target.exists()) throw FileAccessException("\"$name\" already exists here.")
        if (!target.mkdirs()) throw FileAccessException("Couldn't create the folder.")
    }

    /** Deletes a file or a whole folder. The server folder itself can't be deleted. */
    fun delete(rel: String) {
        val f = resolve(rel)
        if (f == root) throw FileAccessException("The server folder itself can't be deleted here.")
        if (!f.exists()) throw FileAccessException("That doesn't exist.")
        if (!f.deleteRecursively()) throw FileAccessException("Couldn't delete everything in there.")
    }

    private fun checkName(name: String) {
        if (name.isBlank() || name == "." || name == ".." || name.length > 200 ||
            name.any { it == '/' || it == '\\' || it.code < 32 }
        ) {
            throw FileAccessException("That isn't a valid name.")
        }
    }

    companion object {
        /** Biggest file the in-app and web editors will open. */
        const val MAX_TEXT_BYTES = 200_000
    }
}
