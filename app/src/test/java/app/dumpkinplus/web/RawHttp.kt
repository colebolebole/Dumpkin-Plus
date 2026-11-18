package app.dumpkinplus.web

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Socket

class Resp(val status: Int, val headers: Map<String, String>, val body: String, val bytes: ByteArray = ByteArray(0)) {
    val json: JSONObject get() = JSONObject(body)
    val array: JSONArray get() = JSONArray(body)
    fun header(name: String): String? = headers[name.lowercase()]
}

/** A tiny HTTP client over a plain socket, so tests can send any header (Host, Origin, ...) exactly as they like. */
object RawHttp {
    fun call(
        port: Int,
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        cookie: String? = null,
        csrf: Boolean = method != "GET" && method != "HEAD",
        host: String? = "127.0.0.1:$port",
        extraRaw: String = "",
    ): Resp {
        val sb = StringBuilder("$method $path HTTP/1.1\r\n")
        if (host != null) sb.append("Host: $host\r\n")
        if (csrf) sb.append("X-Requested-With: Dumpkin\r\n")
        if (cookie != null) sb.append("Cookie: $cookie\r\n")
        for ((k, v) in headers) sb.append("$k: $v\r\n")
        val bytes = body?.toByteArray(Charsets.UTF_8)
        if (bytes != null) sb.append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
        sb.append(extraRaw).append("Connection: close\r\n\r\n")
        return Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().apply { write(sb.toString().toByteArray(Charsets.ISO_8859_1)); if (bytes != null) write(bytes); flush() }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = try { s.getInputStream().read(buf) } catch (_: java.io.IOException) { -1 }
                if (n < 0) break
                out.write(buf, 0, n)
            }
            parse(out.toByteArray())
        }
    }

    private fun parse(raw: ByteArray): Resp {
        val text = String(raw, Charsets.UTF_8)
        val split = text.indexOf("\r\n\r\n")
        val head = text.substring(0, split).split("\r\n")
        val status = head[0].split(' ')[1].toInt()
        val headers = head.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        // Binary bodies (downloads) are read from the raw bytes, since decoding them as text would corrupt them.
        var cut = 0
        while (cut + 3 < raw.size && !(raw[cut] == 13.toByte() && raw[cut + 1] == 10.toByte() && raw[cut + 2] == 13.toByte() && raw[cut + 3] == 10.toByte())) cut++
        return Resp(status, headers, text.substring(split + 4), raw.copyOfRange(minOf(cut + 4, raw.size), raw.size))
    }
}
