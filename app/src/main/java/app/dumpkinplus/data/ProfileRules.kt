package app.dumpkinplus.data

import org.json.JSONObject

/** Validation shared by everything that can change a server's settings (the app UI and the web dashboard). */
object ProfileRules {
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    /** @return a human-readable problem, or null when [p] is acceptable next to [others]. */
    fun validate(p: ServerProfile, others: List<ServerProfile>): String? = when {
        p.name.isBlank() -> "Give the server a name."
        p.name.length > 40 -> "The name can be at most 40 characters."
        p.motd.length > 120 -> "The message of the day can be at most 120 characters."
        javaPortError(p, others) != null -> javaPortError(p, others)
        bedrockPortError(p, others) != null -> bedrockPortError(p, others)
        p.maxPlayers !in 1..1000 -> "Max players must be between 1 and 1000."
        p.viewDistance !in 2..24 -> "View distance must be between 2 and 24."
        p.simulationDistance !in 2..16 -> "Simulation distance must be between 2 and 16."
        p.memoryLimitMb != 0 && p.memoryLimitMb !in 256..131072 -> "Memory limit must be 0 (off) or at least 256 MB."
        p.cpuThreads !in 0..64 -> "CPU threads must be between 0 (auto) and 64."
        else -> null
    }

    /** Every port another server has claimed: its Java port, plus its Bedrock port when Bedrock is on. */
    fun claimedPorts(servers: List<ServerProfile>): Set<Int> =
        servers.flatMapTo(mutableSetOf()) { if (it.bedrock) listOf(it.port, it.bedrockPort) else listOf(it.port) }

    fun javaPortError(p: ServerProfile, others: List<ServerProfile>): String? = when {
        p.port !in MIN_PORT..MAX_PORT -> "Use a port between $MIN_PORT and $MAX_PORT"
        others.any { it.id != p.id && it.port == p.port } -> "Another server already uses this port"
        others.any { it.id != p.id && it.bedrock && it.bedrockPort == p.port } ->
            "Another server uses this port for Bedrock"
        else -> null
    }

    /** Null when Bedrock is off: the port is then unused, so it can't clash with anything. */
    fun bedrockPortError(p: ServerProfile, others: List<ServerProfile>): String? = when {
        !p.bedrock -> null
        p.bedrockPort !in MIN_PORT..MAX_PORT -> "Use a port between $MIN_PORT and $MAX_PORT"
        p.bedrockPort == p.port -> "Must differ from the Java port"
        others.any { it.id != p.id && it.bedrock && it.bedrockPort == p.bedrockPort } ->
            "Another server already uses this Bedrock port"
        others.any { it.id != p.id && it.port == p.bedrockPort } -> "Another server uses this port for Java"
        else -> null
    }

    fun nextFreePort(taken: Set<Int>, start: Int = 25565): Int =
        generateSequence(start) { it + 1 }.first { it !in taken && it <= MAX_PORT }
}

/** Applies a partial JSON document to a profile. Unknown keys are ignored; bad values are rejected with a message. */
object ProfilePatch {
    class Invalid(message: String) : Exception(message)

    fun apply(base: ServerProfile, o: JSONObject): ServerProfile {
        fun int(key: String, current: Int): Int {
            if (!o.has(key)) return current
            val v = o.get(key)
            if (v !is Number || v.toDouble() != v.toInt().toDouble()) throw Invalid("\"$key\" must be a whole number.")
            return v.toInt()
        }
        fun bool(key: String, current: Boolean): Boolean {
            if (!o.has(key)) return current
            return o.get(key) as? Boolean ?: throw Invalid("\"$key\" must be true or false.")
        }
        fun str(key: String, current: String): String {
            if (!o.has(key)) return current
            return o.get(key) as? String ?: throw Invalid("\"$key\" must be text.")
        }
        fun <T : Enum<T>> enum(key: String, current: T, all: List<T>): T {
            if (!o.has(key)) return current
            val s = o.get(key) as? String ?: throw Invalid("\"$key\" must be text.")
            return all.firstOrNull { it.name.equals(s, ignoreCase = true) }
                ?: throw Invalid("\"$key\" must be one of ${all.joinToString { it.name }}.")
        }

        return base.copy(
            name = str("name", base.name).trim(),
            motd = str("motd", base.motd),
            port = int("port", base.port),
            maxPlayers = int("maxPlayers", base.maxPlayers),
            viewDistance = int("viewDistance", base.viewDistance),
            simulationDistance = int("simulationDistance", base.simulationDistance),
            gameMode = enum("gameMode", base.gameMode, GameMode.entries),
            difficulty = enum("difficulty", base.difficulty, Difficulty.entries),
            onlineMode = bool("onlineMode", base.onlineMode),
            bedrock = bool("bedrock", base.bedrock),
            bedrockPort = int("bedrockPort", base.bedrockPort),
            memoryLimitMb = int("memoryLimitMb", base.memoryLimitMb),
            memoryAction = enum("memoryAction", base.memoryAction, MemoryAction.entries),
            cpuThreads = int("cpuThreads", base.cpuThreads),
            autoRestart = bool("autoRestart", base.autoRestart),
            lightPing = bool("lightPing", base.lightPing),
            compression = enum("compression", base.compression, Compression.entries),
            fetchSkins = bool("fetchSkins", base.fetchSkins),
            engineId = when {
                !o.has("engineId") -> base.engineId
                o.isNull("engineId") -> null
                else -> {
                    val s = o.get("engineId") as? String ?: throw Invalid("\"engineId\" must be text or null.")
                    s.ifBlank { null } // an empty string means "use the newest build"
                }
            },
        )
    }
}
