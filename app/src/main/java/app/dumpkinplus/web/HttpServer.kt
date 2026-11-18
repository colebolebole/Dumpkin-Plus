package app.dumpkinplus.web

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Header names are lower-cased. */
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteIp: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun cookie(name: String): String? =
        header("cookie")?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    fun bodyText(): String = String(body, Charsets.UTF_8)
}

class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    /** When set, this file is streamed as the body (backups and world files can be far bigger than memory). */
    val file: File? = null,
) {
    fun withHeader(name: String, value: String) = HttpResponse(status, headers + (name to value), body, file)

    companion object {
        fun json(status: Int, value: JSONObject) = HttpResponse(
            status, mapOf("Content-Type" to "application/json; charset=utf-8", "Cache-Control" to "no-store"),
            value.toString().toByteArray(Charsets.UTF_8),
        )

        fun json(status: Int, value: JSONArray) = HttpResponse(
            status, mapOf("Content-Type" to "application/json; charset=utf-8", "Cache-Control" to "no-store"),
            value.toString().toByteArray(Charsets.UTF_8),
        )

        fun error(status: Int, message: String) = json(status, JSONObject().put("error", message))

        fun bytes(status: Int, contentType: String, body: ByteArray, cache: String = "no-cache") =
            HttpResponse(status, mapOf("Content-Type" to contentType, "Cache-Control" to cache), body)

        /** Streams [file] as a download. Only the first [File.length] bytes are sent, even if the file is still growing. */
        fun download(file: File, downloadName: String, contentType: String = "application/octet-stream") = HttpResponse(
            200,
            mapOf(
                "Content-Type" to contentType,
                "Cache-Control" to "no-store",
                "Content-Disposition" to "attachment; filename=\"" + downloadName.replace(Regex("[^A-Za-z0-9._+-]"), "_") + "\"",
            ),
            file = file,
        )
    }
}

/**
 * A deliberately small HTTP/1.1 server: one request per connection, bounded header and body sizes, read timeouts and
 * a fixed worker pool. It exists to serve the dashboard and its JSON API, nothing more.
 */
class HttpServer(
    private val port: Int,
    private val bindAddress: String = "0.0.0.0",
    private val handler: (HttpRequest) -> HttpResponse,
) {
    @Volatile private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = Executors.newFixedThreadPool(WORKERS, namedDaemonThreads("dumpkin-web"))

    val boundPort: Int get() = socket?.localPort ?: -1
    val isRunning: Boolean get() = socket?.isClosed == false

    /** @throws IOException if the port can't be bound (already in use, no permission, ...). */
    fun start() {
        check(socket == null) { "already started" }
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName(bindAddress), port), 64)
        socket = s
        acceptThread = Thread({ acceptLoop(s) }, "dumpkin-web-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        workers.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (_: IOException) {
                return
            }
            try {
                workers.execute { serve(client) }
            } catch (_: RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { c ->
            c.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(c.getInputStream())
            val out = c.getOutputStream()
            val request = try {
                readRequest(input, c.inetAddress?.hostAddress ?: "?")
            } catch (e: BadRequest) {
                write(out, HttpResponse.error(e.status, e.message ?: "Bad request"), headOnly = false)
                drain(c, input)
                return
            } catch (_: SocketTimeoutException) {
                return
            } catch (_: IOException) {
                return
            }
            val response = try {
                handler(request)
            } catch (_: Exception) {
                HttpResponse.error(500, "Something went wrong on the server.")
            }
            try {
                write(out, response, headOnly = request.method == "HEAD")
            } catch (_: IOException) {
            }
        }
    }

    private class BadRequest(val status: Int, message: String) : Exception(message)

    /**
     * After rejecting a request we may not have read all of it. Closing a socket with unread data makes the OS send a
     * reset, which can destroy the error response before the client reads it, so finish the response, then swallow
     * a bounded amount of the rest.
     */
    private fun drain(c: Socket, input: InputStream) {
        try {
            c.shutdownOutput()
            c.soTimeout = 300
            var left = 256 * 1024
            val buf = ByteArray(8192)
            while (left > 0) {
                val n = input.read(buf)
                if (n < 0) break
                left -= n
            }
        } catch (_: IOException) {
        }
    }

    private fun readRequest(input: InputStream, remoteIp: String): HttpRequest {
        val head = ByteArrayOutputStream()
        var matched = 0 // how many bytes of "\r\n\r\n" we've just seen
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw IOException("closed")
            head.write(b)
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (head.size() > MAX_HEADER_BYTES) throw BadRequest(431, "Request headers are too large.")
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val requestLine = lines.firstOrNull()?.split(' ') ?: throw BadRequest(400, "Bad request.")
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) throw BadRequest(400, "Bad request.")
        val method = requestLine[0].uppercase()
        if (method !in ALLOWED_METHODS) throw BadRequest(405, "Method not allowed.")

        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i <= 0) throw BadRequest(400, "Bad header.")
            headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        if (headers["transfer-encoding"] != null) throw BadRequest(501, "Chunked requests are not supported.")

        val length = headers["content-length"]?.let { it.toLongOrNull() ?: throw BadRequest(400, "Bad Content-Length.") } ?: 0L
        if (length < 0) throw BadRequest(400, "Bad Content-Length.")
        if (length > MAX_BODY_BYTES) throw BadRequest(413, "Request body is too large.")
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) throw IOException("closed")
            read += n
        }

        val target = requestLine[1]
        val rawPath = target.substringBefore('?')
        if (!rawPath.startsWith("/")) throw BadRequest(400, "Bad request.")
        val path = try {
            URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            throw BadRequest(400, "Bad path.")
        }
        val query = HashMap<String, String>()
        target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            runCatching { query[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8") }
        }
        return HttpRequest(method, path, query, headers, body, remoteIp)
    }

    private fun write(out: java.io.OutputStream, r: HttpResponse, headOnly: Boolean) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
        val all = LinkedHashMap<String, String>()
        all["X-Content-Type-Options"] = "nosniff"
        all["X-Frame-Options"] = "DENY"
        all["Referrer-Policy"] = "no-referrer"
        all["Content-Security-Policy"] =
            "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; connect-src 'self'; " +
                "frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        all.putAll(r.headers)
        val streamLength = r.file?.length() ?: 0L
        all["Content-Length"] = (if (r.file != null) streamLength else r.body.size.toLong()).toString()
        all["Connection"] = "close"
        for ((k, v) in all) {
            require(!k.contains('\r') && !k.contains('\n') && !v.contains('\r') && !v.contains('\n')) { "header injection" }
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) {
            if (r.file != null) {
                var left = streamLength
                val buf = ByteArray(32 * 1024)
                r.file.inputStream().use { input ->
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
            } else {
                out.write(r.body)
            }
        }
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
        409 -> "Conflict"; 411 -> "Length Required"; 413 -> "Payload Too Large"; 429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"; 501 -> "Not Implemented"
        else -> "Status"
    }

    private fun namedDaemonThreads(prefix: String): ThreadFactory {
        val n = AtomicInteger()
        return ThreadFactory { r -> Thread(r, "$prefix-${n.incrementAndGet()}").apply { isDaemon = true } }
    }

    companion object {
        const val WORKERS = 6 // each idle thread costs RAM; an old phone only serves a handful of browsers
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 64 * 1024
        private val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
    }
}
