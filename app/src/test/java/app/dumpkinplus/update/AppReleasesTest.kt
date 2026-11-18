package app.dumpkinplus.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleasesTest {
    private val sha = "a".repeat(64)

    private fun asset(name: String, digest: String? = sha) = JSONObject()
        .put("name", name).put("size", 230_568).put("browser_download_url", "https://github.com/x/y/releases/download/t/$name")
        .apply { if (digest != null) put("digest", "sha256:$digest") }

    private fun release(tag: String, pre: Boolean = true, draft: Boolean = false, vararg assets: JSONObject) = JSONObject()
        .put("tag_name", tag).put("name", "Dumpkin+ $tag").put("body", "notes for $tag\n").put("prerelease", pre)
        .put("draft", draft).put("published_at", "2026-11-17T15:26:00Z").put("assets", JSONArray(assets.toList()))

    private fun apk() = asset("Dumpkin+-0.1.2-alpha.apk")

    @Test fun versionsAreOrderedTheWayPeopleExpect() {
        fun older(a: String, b: String) = assertTrue("$a < $b", AppVersions.compare(a, b) < 0)
        older("0.1.1-alpha", "0.1.2-alpha")
        older("0.1.2-alpha", "0.1.2-beta")
        older("0.1.2-beta", "0.1.2-rc1")
        older("0.1.2-rc1", "0.1.2-rc2")
        older("0.1.2-rc2", "0.1.2")
        older("0.1.9", "0.1.10") // numbers, not text
        older("0.9.9", "1.0.0-alpha")
        older("v0.1.0-alpha", "v0.1.1-alpha")
        assertEquals(0, AppVersions.compare("0.1.2-alpha", "v0.1.2-alpha"))
        assertEquals(0, AppVersions.compare("0.1.2-ALPHA", "0.1.2-alpha"))
    }

    @Test fun textThatIsNotAVersionIsNeverNewer() {
        assertFalse(AppVersions.isValid("nightly"))
        assertFalse(AppVersions.isValid("1.2"))
        assertEquals(0, AppVersions.compare("nightly", "0.1.0"))
        assertEquals(0, AppVersions.compare("0.1.0", "latest"))
    }

    @Test fun readsTheApkChecksumAndNotes() {
        val r = AppReleases.parse(release("v0.1.2-alpha", true, false, apk()))!!
        assertEquals("v0.1.2-alpha", r.tag)
        assertEquals("0.1.2-alpha", r.version)
        assertEquals("Dumpkin+-0.1.2-alpha.apk", r.apkName)
        assertEquals(sha, r.sha256)
        assertEquals("notes for v0.1.2-alpha", r.notes)
        assertTrue(r.prerelease)
    }

    @Test fun aReleaseWithoutAnApkIsSkipped() {
        assertNull(AppReleases.parse(release("v1.0.0", false, false, asset("source.zip"))))
        assertNull(AppReleases.parse(release("v1.0.0", false, false)))
    }

    @Test fun aBadOrMissingDigestMeansNoChecksumNotAFailure() {
        assertNull(AppReleases.parse(release("v0.1.2-alpha", true, false, asset("a.apk", null)))!!.sha256)
        assertNull(AppReleases.parse(release("v0.1.2-alpha", true, false, asset("a.apk").put("digest", "sha256:12")))!!.sha256)
    }

    @Test fun listIsSortedNewestFirstAndSkipsDraftsAndUnversionedTags() {
        val json = JSONArray(
            listOf(
                release("v0.1.0-alpha", true, false, apk()),
                release("v0.1.2-alpha", true, false, apk()),
                release("v0.1.1-alpha", true, false, apk()),
                release("v0.9.0", false, true, apk()), // draft
                release("nightly", true, false, apk()), // not a version
            )
        )
        assertEquals(listOf("v0.1.2-alpha", "v0.1.1-alpha", "v0.1.0-alpha"), AppReleases.parseList(json, true).map { it.tag })
    }

    @Test fun preReleasesCanBeLeftOut() {
        val json = JSONArray(
            listOf(release("v0.2.0-beta", true, false, apk()), release("v0.1.0", false, false, apk()))
        )
        assertEquals(listOf("v0.1.0"), AppReleases.parseList(json, false).map { it.tag })
        assertEquals(listOf("v0.2.0-beta", "v0.1.0"), AppReleases.parseList(json, true).map { it.tag })
    }

    @Test fun offersOnlyABuildNewerThanTheOneInstalled() {
        val list = AppReleases.parseList(
            JSONArray(listOf(release("v0.1.2-alpha", true, false, apk()), release("v0.1.1-alpha", true, false, apk()))), true,
        )
        assertEquals("v0.1.2-alpha", AppReleases.newer("0.1.1-alpha", list)?.tag)
        assertNull(AppReleases.newer("0.1.2-alpha", list))
        assertNull(AppReleases.newer("0.2.0", list)) // running something newer than anything published
        assertEquals("v0.1.2-alpha", AppReleases.newer("0.1.0-alpha", list)?.tag) // jumps straight to the newest
    }

    @Test fun anAlphaIsNotOfferedToSomeoneOnAStableBuildOfTheSameNumber() {
        val list = AppReleases.parseList(JSONArray(listOf(release("v0.1.2-alpha", true, false, apk()))), true)
        assertNull(AppReleases.newer("0.1.2", list))
    }
}
