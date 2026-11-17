package app.dumpkinplus.data

import java.io.File

/**
 * Minimal, format-preserving TOML editor. It only ever touches the keys Dumpkin manages, so any other
 * settings in pumpkin.toml (and Pumpkin's own comments/defaults) are left exactly as they are.
 */
object Toml {
    data class Edit(val section: String, val key: String, val value: String)

    private val header = Regex("""^\s*\[([^\[\]]+)]\s*(#.*)?$""")

    private fun keyRegex(key: String) = Regex("""^\s*${Regex.escape(key)}\s*=\s*(.*?)\s*$""")

    fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ") + "\""

    fun read(text: String, section: String, key: String): String? {
        var cur = ""
        val keyRe = keyRegex(key)
        for (line in text.lines()) {
            val h = header.matchEntire(line)
            if (h != null) {
                cur = h.groupValues[1].trim()
                continue
            }
            if (cur == section) {
                val m = keyRe.matchEntire(line)
                if (m != null) return unquote(m.groupValues[1])
            }
        }
        return null
    }

    private fun unquote(raw: String): String {
        val v = raw.trim()
        if (!v.startsWith("\"")) return v.substringBefore('#').trim()
        val sb = StringBuilder()
        var i = 1
        while (i < v.length) {
            val c = v[i]
            when {
                c == '\\' && i + 1 < v.length -> { sb.append(v[i + 1]); i++ }
                c == '"' -> return sb.toString()
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    fun apply(text: String, edits: List<Edit>): String {
        val lines = if (text.isBlank()) mutableListOf() else text.lines().toMutableList()
        for (e in edits) {
            val keyRe = keyRegex(e.key)
            var cur = ""
            var found = -1
            var sectionIdx = -1
            var firstHeader = -1
            for ((i, line) in lines.withIndex()) {
                val h = header.matchEntire(line)
                if (h != null) {
                    cur = h.groupValues[1].trim()
                    if (firstHeader < 0) firstHeader = i
                    if (e.section.isNotEmpty() && cur == e.section && sectionIdx < 0) sectionIdx = i
                    continue
                }
                if (cur == e.section && found < 0 && keyRe.matches(line)) found = i
            }
            val newLine = "${e.key} = ${e.value}"
            when {
                found >= 0 -> lines[found] = newLine
                e.section.isEmpty() -> lines.add(if (firstHeader >= 0) firstHeader else lines.size, newLine)
                sectionIdx >= 0 -> lines.add(sectionIdx + 1, newLine)
                else -> {
                    if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
                    lines.add("[${e.section}]")
                    lines.add(newLine)
                }
            }
        }
        return lines.joinToString("\n").trimEnd() + "\n"
    }
}

/** Maps a [ServerProfile] to/from the keys of Pumpkin's `pumpkin.toml`. */
object PumpkinConfig {
    fun file(dir: File) = File(dir, "pumpkin.toml")

    fun write(dir: File, p: ServerProfile) {
        val f = file(dir)
        val firstRun = !f.exists()
        val edits = buildList {
            add(Toml.Edit("", "default_gamemode", Toml.q(p.gameMode.label)))
            add(Toml.Edit("", "default_difficulty", Toml.q(p.difficulty.label)))
            add(Toml.Edit("networking.java", "address", Toml.q("0.0.0.0:${p.port}")))
            add(Toml.Edit("networking.java", "online_mode", p.onlineMode.toString()))
            add(Toml.Edit("networking.java", "max_players", p.maxPlayers.toString()))
            add(Toml.Edit("networking.java", "view_distance", p.viewDistance.toString()))
            add(Toml.Edit("networking.java", "simulation_distance", p.simulationDistance.toString()))
            add(Toml.Edit("networking.java", "motd", Toml.q(p.motd)))
            add(Toml.Edit("networking.bedrock", "enabled", p.bedrock.toString()))
            // Bedrock listens on its own address; each server needs a different port to run side by side.
            add(Toml.Edit("networking.bedrock.nethernet", "address", Toml.q("0.0.0.0:${p.bedrockPort}")))
            // The console is driven over a pipe, not a terminal.
            add(Toml.Edit("commands", "use_tty", "false"))
            // Join speed: compression is the CPU-heavy part of sending chunks. All values are within Pumpkin's
            // documented ranges (level 0-9); a bad value would make Pumpkin reset the whole config.
            add(Toml.Edit("networking.java.compression", "enabled", (p.compression != Compression.Off).toString()))
            when (p.compression) {
                Compression.Fast -> add(Toml.Edit("networking.java.compression", "level", "1"))
                Compression.Balanced -> add(Toml.Edit("networking.java.compression", "level", "4"))
                Compression.Off -> Unit
            }
            add(Toml.Edit("networking.java.authentication.textures", "enabled", p.fetchSkins.toString()))
            // The favicon is a base64 PNG sent with every server-list ping; skipping it keeps pings snappy on Wi-Fi.
            add(Toml.Edit("", "use_favicon", (!p.lightPing).toString()))
            if (firstRun) {
                // Telemetry is opt-in on this app: off for new servers, user can flip it in the raw editor.
                add(Toml.Edit("telemetry", "enabled", "false"))
                // Fewer listeners and background work on a phone: no query port, no management server.
                add(Toml.Edit("networking.query", "enabled", "false"))
                add(Toml.Edit("networking.management", "enabled", "false"))
            }
        }
        val current = if (f.exists()) f.readText() else ""
        f.writeText(Toml.apply(current, edits))
    }

    /** Reads managed keys back from the file so edits made in the raw editor show up in the form. */
    fun pull(dir: File, p: ServerProfile): ServerProfile {
        val f = file(dir)
        if (!f.exists()) return p
        val t = f.readText()
        fun s(section: String, key: String) = Toml.read(t, section, key)
        return p.copy(
            port = s("networking.java", "address")?.substringAfterLast(':')?.toIntOrNull() ?: p.port,
            motd = s("networking.java", "motd") ?: p.motd,
            maxPlayers = s("networking.java", "max_players")?.toIntOrNull() ?: p.maxPlayers,
            viewDistance = s("networking.java", "view_distance")?.toIntOrNull() ?: p.viewDistance,
            simulationDistance = s("networking.java", "simulation_distance")?.toIntOrNull() ?: p.simulationDistance,
            onlineMode = s("networking.java", "online_mode")?.toBooleanStrictOrNull() ?: p.onlineMode,
            bedrock = s("networking.bedrock", "enabled")?.toBooleanStrictOrNull() ?: p.bedrock,
            bedrockPort = s("networking.bedrock.nethernet", "address")?.substringAfterLast(':')?.toIntOrNull() ?: p.bedrockPort,
            lightPing = s("", "use_favicon")?.toBooleanStrictOrNull()?.not() ?: p.lightPing,
            fetchSkins = s("networking.java.authentication.textures", "enabled")?.toBooleanStrictOrNull() ?: p.fetchSkins,
            compression = when {
                s("networking.java.compression", "enabled")?.toBooleanStrictOrNull() == false -> Compression.Off
                (s("networking.java.compression", "level")?.toIntOrNull() ?: 4) <= 2 -> Compression.Fast
                else -> Compression.Balanced
            },
            gameMode = GameMode.entries.firstOrNull { it.label.equals(s("", "default_gamemode"), true) } ?: p.gameMode,
            difficulty = Difficulty.entries.firstOrNull { it.label.equals(s("", "default_difficulty"), true) } ?: p.difficulty,
        )
    }
}
