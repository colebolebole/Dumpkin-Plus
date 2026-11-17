package app.dumpkinplus.server

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Minecraft Java "Server List Ping". Used both as a readiness probe and to read the player count. */
object StatusPing {
    data class Result(val online: Int, val max: Int, val version: String)

    fun query(host: String, port: Int, timeoutMs: Int = 1500): Result? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            val hs = ByteArrayOutputStream()
            DataOutputStream(hs).apply {
                writeVarInt(0)
                writeVarInt(767)
                val h = host.toByteArray()
                writeVarInt(h.size)
                write(h)
                writeShort(port)
                writeVarInt(1)
            }
            out.writeVarInt(hs.size())
            out.write(hs.toByteArray())
            out.writeVarInt(1)
            out.writeVarInt(0)
            out.flush()

            input.readVarInt() // frame length
            if (input.readVarInt() != 0) return@use null
            val len = input.readVarInt()
            if (len <= 0 || len > 4 * 1024 * 1024) return@use null
            val buf = ByteArray(len)
            input.readFully(buf)
            val json = JSONObject(String(buf))
            val players = json.optJSONObject("players")
            Result(
                online = players?.optInt("online", 0) ?: 0,
                max = players?.optInt("max", 0) ?: 0,
                version = json.optJSONObject("version")?.optString("name").orEmpty(),
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun OutputStream.writeVarInt(value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                write(v)
                return
            }
            write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    private fun InputStream.readVarInt(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = read()
            if (b < 0) throw java.io.EOFException()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 35) throw java.io.IOException("VarInt too long")
        }
    }
}
