package app.dumpkinplus.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Persists server profiles as JSON and owns the on-disk layout of server folders. */
class ServerStore(ctx: Context) {
    private val file = File(ctx.filesDir, "profiles.json")
    private val root = File(ctx.filesDir, "servers").apply { mkdirs() }

    fun dir(id: String): File = File(root, id).apply { mkdirs() }

    fun load(): List<ServerProfile> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { ServerProfile.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(list: List<ServerProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, "profiles.json.tmp")
        tmp.writeText(arr.toString(2))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun deleteDir(id: String) {
        File(root, id).deleteRecursively()
    }
}
