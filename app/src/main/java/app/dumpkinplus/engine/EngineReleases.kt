package app.dumpkinplus.engine

import org.json.JSONArray
import org.json.JSONObject

/** Turns GitHub's release JSON into downloadable engines. Pure, so it is tested without a network or a phone. */
object EngineReleases {
    /** How many releases of the community repo are listed: its newest builds, pre-releases included. */
    const val COMMUNITY_LIMIT = 5

    /**
     * One release (`/releases/latest` or `/releases/tags/x`) to the engine in it, or null if it has no matching file.
     * [match] decides which asset is the engine, by name.
     */
    fun parseRelease(json: JSONObject, ch: Channel, match: (String) -> Boolean): RemoteEngine? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            if (!match(name)) continue
            val tag = json.getString("tag_name")
            val updated = a.optString("updated_at", json.optString("published_at"))
            val pre = json.optBoolean("prerelease", false)
            return RemoteEngine(
                channel = ch,
                tag = tag,
                label = when (ch) {
                    Channel.Stable -> tag
                    Channel.Armv7 -> "${ch.label} $tag" + if (pre) " (pre-release)" else ""
                    else -> "${ch.label} ${updated.take(10)}"
                },
                assetName = name,
                url = a.getString("browser_download_url"),
                size = a.optLong("size"),
                updated = updated,
                sha256 = a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 },
                prerelease = pre,
            )
        }
        return null
    }

    /** `/releases`: every published release, newest first, that has a matching file. Drafts are never listed by GitHub. */
    fun parseList(json: JSONArray, ch: Channel, match: (String) -> Boolean, limit: Int = COMMUNITY_LIMIT): List<RemoteEngine> {
        val out = ArrayList<RemoteEngine>()
        for (i in 0 until json.length()) {
            val rel = json.getJSONObject(i)
            if (rel.optBoolean("draft", false)) continue
            parseRelease(rel, ch, match)?.let { out += it }
            if (out.size >= limit) break
        }
        return out
    }

    /** The community 32-bit ARM engine is the `armv7` file; the checksum list next to it is not an engine. */
    fun isArmv7Engine(name: String): Boolean = name.lowercase().let { it.contains("armv7") && !it.endsWith(".txt") && !it.endsWith(".sha256") }
}

/** Works out which installed engines have something newer to download. */
object EngineUpdates {
    /**
     * For each channel the user has installed, the newest build of it (first in [remote], which is ordered newest first)
     * when it isn't what is installed: a new version, a new nightly, or the same version rebuilt (checksum changed).
     * A channel the user hasn't installed anything from is not an "update", just something available.
     */
    fun compute(installed: List<InstalledEngine>, remote: List<RemoteEngine>): List<RemoteEngine> =
        remote.groupBy { it.channel }.values.map { it.first() }.filter { r ->
            val mine = installed.filter { it.channel == r.channel.label }
            mine.isNotEmpty() && mine.none { it.id == r.id && sameFile(it, r) }
        }

    private fun sameFile(i: InstalledEngine, r: RemoteEngine): Boolean =
        i.sha256 == null || r.sha256 == null || i.sha256.equals(r.sha256, ignoreCase = true)
}
