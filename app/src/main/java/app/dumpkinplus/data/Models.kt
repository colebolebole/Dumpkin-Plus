package app.dumpkinplus.data

import org.json.JSONObject
import java.util.UUID

enum class GameMode(val label: String) {
    Survival("Survival"), Creative("Creative"), Adventure("Adventure"), Spectator("Spectator")
}

enum class Difficulty(val label: String) {
    Peaceful("Peaceful"), Easy("Easy"), Normal("Normal"), Hard("Hard")
}

/**
 * Java packet compression. Chunk packets are big, so compressing them is the main CPU cost of a join on a phone.
 * Over a home network bandwidth is plentiful, so cheaper (or no) compression joins faster.
 */
enum class Compression(val label: String, val hint: String) {
    Off("Off", "Fastest on Wi-Fi/LAN, uses more bandwidth"),
    Fast("Fast", "Level 1: light CPU use, good for most"),
    Balanced("Balanced", "Pumpkin's default (level 4)"),
}

/** Quick picks for view/simulation distance: fewer chunks to send means a faster join. */
enum class DistancePreset(val label: String, val view: Int, val simulation: Int) {
    /** For phones with about 2 GB of RAM or less: the number of chunks per player is what costs memory and CPU. */
    Minimal("Low-end", 4, 3),
    Fast("Fast join", 6, 4),
    Balanced("Balanced", 8, 6),
    Detailed("Detailed", 12, 8),
}

enum class MemoryAction(val label: String) {
    Warn("Warn only"), Restart("Restart server")
}

const val DEFAULT_BEDROCK_PORT = 19132

/** Everything Dumpkin remembers about one hosted server. The world itself lives in the server folder. */
data class ServerProfile(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val port: Int = 25565,
    val motd: String = "A Pumpkin server on Android",
    val maxPlayers: Int = 20,
    val viewDistance: Int = DistancePreset.Fast.view,
    val simulationDistance: Int = DistancePreset.Fast.simulation,
    val gameMode: GameMode = GameMode.Survival,
    val difficulty: Difficulty = Difficulty.Normal,
    val onlineMode: Boolean = true,
    val bedrock: Boolean = false,
    /** Port Bedrock players connect to (Pumpkin's `[networking.bedrock.nethernet] address`). Only used when [bedrock] is on. */
    val bedrockPort: Int = DEFAULT_BEDROCK_PORT,
    /** Installed engine build to use; null = newest installed. */
    val engineId: String? = null,
    /** Soft RAM cap in MB, enforced by watching the process's resident memory. 0 = no limit. */
    val memoryLimitMb: Int = 0,
    val memoryAction: MemoryAction = MemoryAction.Warn,
    /** Worker threads for the async runtime / thread pool. 0 = let Pumpkin decide. */
    val cpuThreads: Int = 0,
    val autoRestart: Boolean = false,
    /** Skip the server icon in the server-list response: smaller, faster pings over Wi-Fi. */
    val lightPing: Boolean = true,
    val compression: Compression = Compression.Fast,
    /** Fetch/validate skins and capes on join. Turning it off trims join work but players may show default skins. */
    val fetchSkins: Boolean = true,
    /** Back the server up each time it stops (the newest 5 automatic backups are kept). */
    val autoBackup: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("port", port)
        put("motd", motd)
        put("maxPlayers", maxPlayers)
        put("viewDistance", viewDistance)
        put("simulationDistance", simulationDistance)
        put("gameMode", gameMode.name)
        put("difficulty", difficulty.name)
        put("onlineMode", onlineMode)
        put("bedrock", bedrock)
        put("bedrockPort", bedrockPort)
        put("engineId", engineId ?: "")
        put("memoryLimitMb", memoryLimitMb)
        put("memoryAction", memoryAction.name)
        put("cpuThreads", cpuThreads)
        put("autoRestart", autoRestart)
        put("lightPing", lightPing)
        put("compression", compression.name)
        put("fetchSkins", fetchSkins)
        put("autoBackup", autoBackup)
    }

    companion object {
        /** RAM at or below which a phone counts as low-end and new servers start lighter. */
        const val LOW_END_MB = 2560

        /** A starting profile sized for the phone: old, small phones get fewer players and a shorter view distance. */
        fun forDevice(totalMb: Int, name: String = "Server"): ServerProfile =
            if (totalMb in 1..LOW_END_MB) ServerProfile(
                name = name, maxPlayers = 8,
                viewDistance = DistancePreset.Minimal.view, simulationDistance = DistancePreset.Minimal.simulation,
            ) else ServerProfile(name = name)

        fun fromJson(o: JSONObject) = ServerProfile(
            id = o.getString("id"),
            name = o.optString("name", "Server"),
            port = o.optInt("port", 25565),
            motd = o.optString("motd", "A Pumpkin server on Android"),
            maxPlayers = o.optInt("maxPlayers", 20),
            viewDistance = o.optInt("viewDistance", 8),
            simulationDistance = o.optInt("simulationDistance", 6),
            gameMode = GameMode.entries.firstOrNull { it.name == o.optString("gameMode") } ?: GameMode.Survival,
            difficulty = Difficulty.entries.firstOrNull { it.name == o.optString("difficulty") } ?: Difficulty.Normal,
            onlineMode = o.optBoolean("onlineMode", true),
            bedrock = o.optBoolean("bedrock", false),
            bedrockPort = o.optInt("bedrockPort", DEFAULT_BEDROCK_PORT),
            engineId = o.optString("engineId").ifBlank { null },
            memoryLimitMb = o.optInt("memoryLimitMb", 0),
            memoryAction = MemoryAction.entries.firstOrNull { it.name == o.optString("memoryAction") } ?: MemoryAction.Warn,
            cpuThreads = o.optInt("cpuThreads", 0),
            autoRestart = o.optBoolean("autoRestart", false),
            lightPing = o.optBoolean("lightPing", true),
            compression = Compression.entries.firstOrNull { it.name == o.optString("compression") } ?: Compression.Fast,
            fetchSkins = o.optBoolean("fetchSkins", true),
            autoBackup = o.optBoolean("autoBackup", false),
        )
    }
}

enum class Status { Stopped, Starting, Running, Stopping, Crashed }

data class RuntimeState(
    val status: Status = Status.Stopped,
    val online: Int = 0,
    val max: Int = 0,
    val version: String? = null,
    /** Resident memory of the server process in MB (0 when not running). */
    val rssMb: Int = 0,
    val startedAt: Long = 0L,
    val error: String? = null,
    /** A restart was requested: the server is stopping (or waiting to launch again) and will come back by itself. */
    val restarting: Boolean = false,
) {
    val isActive get() = status == Status.Starting || status == Status.Running || status == Status.Stopping
}
