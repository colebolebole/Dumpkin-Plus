package app.dumpkinplus.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineReleasesTest {
    private val sha = "2a11324f192ac44e8f24fe0674b4531ada0e9e6142ad9659cd57acdc887d7757"
    private val sha2 = "b".repeat(64)

    private fun asset(name: String, digest: String? = null, size: Long = 91_451_432) = JSONObject()
        .put("name", name).put("size", size).put("updated_at", "2026-11-17T10:05:58Z")
        .put("browser_download_url", "https://github.com/x/y/releases/download/t/$name")
        .apply { if (digest != null) put("digest", "sha256:$digest") }

    private fun release(tag: String, pre: Boolean = false, draft: Boolean = false, vararg assets: JSONObject) = JSONObject()
        .put("tag_name", tag).put("prerelease", pre).put("draft", draft).put("published_at", "2026-11-17T10:05:58Z")
        .put("assets", JSONArray(assets.toList()))

    private val engine = asset("pumpkin-armv7-android", sha)
    private val sums = asset("SHA256SUMS.txt")

    private fun installed(id: String, channel: Channel, tag: String?, sha: String?) =
        InstalledEngine(id, "x", channel.label, 1, File("x"), tag, sha)

    private fun remote(tag: String, sha: String? = this.sha) = RemoteEngine(
        Channel.Armv7, tag, "32-bit ARM $tag", "pumpkin-armv7-android", "https://x", 1, "2026-11-17", sha,
    )

    @Test fun readsTheEngineAndItsChecksumFromARelease() {
        val r = EngineReleases.parseRelease(release("v0.2.2", false, false, sums, engine), Channel.Armv7, EngineReleases::isArmv7Engine)!!
        assertEquals("pumpkin-armv7-android", r.assetName)
        assertEquals("v0.2.2", r.tag)
        assertEquals("32-bit ARM v0.2.2", r.label)
        assertEquals(sha, r.sha256)
        assertFalse(r.prerelease)
        assertEquals("armv7-v0.2.2", r.id)
    }

    @Test fun theChecksumListIsNotAnEngine() {
        assertNull(EngineReleases.parseRelease(release("v1", false, false, sums), Channel.Armv7, EngineReleases::isArmv7Engine))
        assertTrue(EngineReleases.isArmv7Engine("pumpkin-armv7-android"))
        assertFalse(EngineReleases.isArmv7Engine("pumpkin-armv7-android.sha256"))
        assertFalse(EngineReleases.isArmv7Engine("pumpkin-aarch64-android"))
    }

    @Test fun aMissingOrMalformedDigestMeansNoChecksumRatherThanAFailure() {
        val noDigest = EngineReleases.parseRelease(release("v1", false, false, asset("pumpkin-armv7-android")), Channel.Armv7, EngineReleases::isArmv7Engine)!!
        assertNull(noDigest.sha256)
        val short = asset("pumpkin-armv7-android").put("digest", "sha256:abc")
        assertNull(EngineReleases.parseRelease(release("v1", false, false, short), Channel.Armv7, EngineReleases::isArmv7Engine)!!.sha256)
    }

    @Test fun listsPreReleasesTooSkippingDraftsAndReleasesWithoutAnEngine() {
        val json = JSONArray(
            listOf(
                release("v0.3.0-rc1", true, false, engine),
                release("v0.2.9", false, true, engine), // draft
                release("v0.2.5", false, false, sums), // no engine
                release("v0.2.2", false, false, sums, engine),
            )
        )
        val out = EngineReleases.parseList(json, Channel.Armv7, EngineReleases::isArmv7Engine)
        assertEquals(listOf("v0.3.0-rc1", "v0.2.2"), out.map { it.tag })
        assertTrue(out[0].prerelease)
        assertEquals("32-bit ARM v0.3.0-rc1 (pre-release)", out[0].label)
    }

    @Test fun listIsCappedAtTheNewestFew() {
        val json = JSONArray((1..9).map { release("v0.1.$it", false, false, engine) })
        assertEquals(5, EngineReleases.parseList(json, Channel.Armv7, EngineReleases::isArmv7Engine).size)
        assertEquals(2, EngineReleases.parseList(json, Channel.Armv7, EngineReleases::isArmv7Engine, limit = 2).size)
    }

    @Test fun aNewerVersionIsAnUpdateForSomeoneWhoHasTheOlderOne() {
        val have = listOf(installed("armv7-v0.2.2", Channel.Armv7, "v0.2.2", sha))
        val updates = EngineUpdates.compute(have, listOf(remote("v0.2.3", sha2), remote("v0.2.2")))
        assertEquals(listOf("v0.2.3"), updates.map { it.tag })
    }

    @Test fun upToDateMeansNoUpdate() {
        val have = listOf(installed("armv7-v0.2.2", Channel.Armv7, "v0.2.2", sha))
        assertTrue(EngineUpdates.compute(have, listOf(remote("v0.2.2"))).isEmpty())
    }

    @Test fun theSameVersionRebuiltCountsAsANewBuild() {
        val have = listOf(installed("armv7-v0.2.2", Channel.Armv7, "v0.2.2", sha))
        val updates = EngineUpdates.compute(have, listOf(remote("v0.2.2", sha2)))
        assertEquals(1, updates.size)
        // Same id, so installing it replaces the old file in place.
        assertEquals("armv7-v0.2.2", updates[0].id)
    }

    @Test fun anEngineInstalledBeforeChecksumsWereRecordedIsNotNagged() {
        val have = listOf(installed("armv7-v0.2.2", Channel.Armv7, "v0.2.2", null))
        assertTrue(EngineUpdates.compute(have, listOf(remote("v0.2.2"))).isEmpty())
    }

    @Test fun nothingInstalledFromAChannelMeansNothingToUpdate() {
        assertTrue(EngineUpdates.compute(emptyList(), listOf(remote("v0.2.2"))).isEmpty())
        // An official engine doesn't make the community build an "update".
        val official = listOf(installed("v0.2.2", Channel.Stable, "0.2.2", sha))
        assertTrue(EngineUpdates.compute(official, listOf(remote("v0.2.2"))).isEmpty())
    }

    @Test fun onlyTheNewestBuildOfAChannelIsOfferedAsTheUpdate() {
        val have = listOf(installed("armv7-v0.1.0", Channel.Armv7, "v0.1.0", sha))
        val updates = EngineUpdates.compute(have, listOf(remote("v0.3.0", sha2), remote("v0.2.0", sha2), remote("v0.1.0")))
        assertEquals(listOf("v0.3.0"), updates.map { it.tag })
    }
}
