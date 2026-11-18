package app.dumpkinplus.update

import org.json.JSONArray
import org.json.JSONObject

/** A published Dumpkin+ build that can be installed. */
data class AppRelease(
    val tag: String,
    val name: String,
    val notes: String,
    val prerelease: Boolean,
    val apkName: String,
    val url: String,
    val size: Long,
    /** Expected SHA-256 of the APK (GitHub's asset digest), checked before installing. */
    val sha256: String?,
    val published: String,
) {
    val version: String get() = AppVersions.strip(tag)
}

/** Version strings like `0.1.2-alpha`, ordered so that 0.1.2-alpha < 0.1.2-beta < 0.1.2-rc < 0.1.2 < 0.1.10. */
object AppVersions {
    private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[-.]([A-Za-z]+)[.-]?(\d*))?$""")

    fun strip(tag: String) = tag.trim().removePrefix("v").removePrefix("V")

    /** Null if [v] isn't a version this app understands. */
    private fun key(v: String): List<Int>? {
        val m = PATTERN.matchEntire(v.trim()) ?: return null
        val (maj, min, pat, label, num) = m.destructured
        val rank = when {
            label.isEmpty() -> 100
            label.equals("alpha", true) || label.equals("a", true) -> 1
            label.equals("beta", true) || label.equals("b", true) -> 2
            label.equals("rc", true) -> 3
            else -> 0 // an unknown label sorts before the alpha
        }
        return listOf(maj.toInt(), min.toInt(), pat.toInt(), rank, num.toIntOrNull() ?: 0)
    }

    fun isValid(v: String) = key(v) != null

    /** Negative if [a] is older than [b]. Strings that aren't versions compare as equal, so they are never "newer". */
    fun compare(a: String, b: String): Int {
        val x = key(a) ?: return 0
        val y = key(b) ?: return 0
        for (i in x.indices) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return 0
    }
}

object AppReleases {
    /** One release to its APK, or null if it has none. */
    fun parse(rel: JSONObject): AppRelease? {
        val assets = rel.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            return AppRelease(
                tag = rel.getString("tag_name"),
                name = rel.optString("name").ifBlank { rel.getString("tag_name") },
                notes = rel.optString("body").trim(),
                prerelease = rel.optBoolean("prerelease", false),
                apkName = name,
                url = a.getString("browser_download_url"),
                size = a.optLong("size"),
                sha256 = a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 },
                published = rel.optString("published_at"),
            )
        }
        return null
    }

    /** Every installable, non-draft release, newest version first. Pre-releases only when [includePre]. */
    fun parseList(json: JSONArray, includePre: Boolean): List<AppRelease> =
        (0 until json.length()).map { json.getJSONObject(it) }
            .filter { !it.optBoolean("draft", false) && (includePre || !it.optBoolean("prerelease", false)) }
            .mapNotNull { parse(it) }
            .filter { AppVersions.isValid(it.tag) }
            .sortedWith { x, y -> AppVersions.compare(y.version, x.version) }

    /** The newest release that is newer than [current], or null when this build is up to date. */
    fun newer(current: String, releases: List<AppRelease>): AppRelease? =
        releases.firstOrNull { AppVersions.compare(it.version, current) > 0 }
}
