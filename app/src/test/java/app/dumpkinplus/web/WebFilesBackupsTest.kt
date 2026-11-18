package app.dumpkinplus.web

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.util.zip.ZipFile

/** The file browser and backup endpoints, over real sockets, on a real temporary server folder. */
class WebFilesBackupsTest {
    private val password = "TEST-PASS-1234"
    private lateinit var backend: FakeBackend
    private lateinit var server: HttpServer
    private var port = 0

    @Before fun setUp() {
        backend = FakeBackend(instant = true)
        val api = WebApi(backend, WebAuth(password = { password })) { null }
        server = HttpServer(0, "127.0.0.1") { api.handle(it) }
        server.start()
        port = server.boundPort
    }

    @After fun tearDown() = server.stop()

    private fun req(method: String, path: String, body: String? = null, cookie: String? = null, csrf: Boolean = method != "GET" && method != "HEAD") =
        RawHttp.call(port, method, path, body, emptyMap(), cookie, csrf)

    private fun login(): String =
        req("POST", "/api/login", """{"password":"$password"}""").header("set-cookie")!!.substringBefore(';')

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    /** A server with a small world, signed in. Returns (cookie, id, folder). */
    private fun serverWithFiles(): Triple<String, String, File> {
        val cookie = login()
        val id = req("POST", "/api/servers", """{"name":"Test"}""", cookie).json.getString("id")
        val dir = backend.serverDir(id)
        File(dir, "pumpkin.toml").writeText("motd = \"hi\"\n")
        File(dir, "world/region").mkdirs()
        File(dir, "world/level.dat").writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 0, 1, 2, 3))
        File(dir, "world/region/r.0.0.mca").writeBytes(ByteArray(5000) { (it % 11).toByte() })
        return Triple(cookie, id, dir)
    }

    private fun waitIdle(cookie: String, id: String): JSONObject {
        repeat(300) {
            val j = req("GET", "/api/servers/$id/backups", cookie = cookie).json
            if (j.isNull("busy")) return j
            Thread.sleep(20)
        }
        error("backup job never finished")
    }

    // ---- files -----------------------------------------------------------------------------------------------------

    @Test fun listsTheServerFolderWithTextFlags() {
        val (c, id, _) = serverWithFiles()
        val r = req("GET", "/api/servers/$id/files", cookie = c)
        assertEquals(200, r.status)
        val entries = r.json.getJSONArray("entries")
        assertEquals("world", entries.getJSONObject(0).getString("name"))
        assertTrue(entries.getJSONObject(0).getBoolean("dir"))
        val toml = entries.getJSONObject(1)
        assertEquals("pumpkin.toml", toml.getString("name"))
        assertTrue(toml.getBoolean("text"))
        val inner = req("GET", "/api/servers/$id/files?path=${q("world")}", cookie = c).json
        assertEquals("world", inner.getString("path"))
        val dat = (0 until inner.getJSONArray("entries").length()).map { inner.getJSONArray("entries").getJSONObject(it) }
            .first { it.getString("name") == "level.dat" }
        assertFalse(dat.getBoolean("text"))
        assertEquals("world/level.dat", dat.getString("path"))
    }

    @Test fun everyFileEndpointNeedsASession() {
        val (_, id, _) = serverWithFiles()
        for (p in listOf("/files", "/files/content?path=pumpkin.toml", "/files/download?path=pumpkin.toml", "/backups")) {
            assertEquals(p, 401, req("GET", "/api/servers/$id$p").status)
        }
    }

    @Test fun pathsOutsideTheServerFolderAreRefusedEverywhere() {
        val (c, id, dir) = serverWithFiles()
        File(dir.parentFile, "secret.txt").writeText("nope")
        val evil = listOf("..", "../secret.txt", "world/../../secret.txt", "..%2F..%2Fsecret.txt")
        for (e in evil) {
            val enc = if (e.contains('%')) e else q(e)
            assertEquals(e, 400, req("GET", "/api/servers/$id/files?path=$enc", cookie = c).status)
            assertEquals(e, 400, req("GET", "/api/servers/$id/files/content?path=$enc", cookie = c).status)
            assertEquals(e, 400, req("GET", "/api/servers/$id/files/download?path=$enc", cookie = c).status)
            assertEquals(e, 400, req("DELETE", "/api/servers/$id/files?path=$enc", cookie = c).status)
        }
        assertEquals(400, req("PUT", "/api/servers/$id/files/content", """{"path":"../secret.txt","text":"x"}""", c).status)
        assertEquals(400, req("POST", "/api/servers/$id/files/folder", """{"path":"..","name":"x"}""", c).status)
        assertEquals("nope", File(dir.parentFile, "secret.txt").readText())
    }

    @Test fun readsAndSavesTextFilesButNotBinary() {
        val (c, id, dir) = serverWithFiles()
        assertEquals("motd = \"hi\"\n", req("GET", "/api/servers/$id/files/content?path=pumpkin.toml", cookie = c).json.getString("text"))
        val save = req("PUT", "/api/servers/$id/files/content", """{"path":"pumpkin.toml","text":"motd = \"new\"\n"}""", c)
        assertEquals(200, save.status)
        assertEquals("motd = \"new\"\n", File(dir, "pumpkin.toml").readText())
        assertEquals(400, req("GET", "/api/servers/$id/files/content?path=${q("world/level.dat")}", cookie = c).status)
        assertEquals(400, req("PUT", "/api/servers/$id/files/content", """{"path":"nothing.txt","text":"x"}""", c).status)
        val huge = "x".repeat(WebApi.EDITABLE_BYTES.toInt() + 1)
        assertEquals(400, req("PUT", "/api/servers/$id/files/content", """{"path":"pumpkin.toml","text":"$huge"}""", c).status)
    }

    @Test fun changingFilesNeedsTheSameSiteHeader() {
        val (c, id, dir) = serverWithFiles()
        val put = req("PUT", "/api/servers/$id/files/content", """{"path":"pumpkin.toml","text":"x"}""", c, csrf = false)
        assertEquals(403, put.status)
        assertEquals(403, req("DELETE", "/api/servers/$id/files?path=pumpkin.toml", cookie = c, csrf = false).status)
        assertEquals("motd = \"hi\"\n", File(dir, "pumpkin.toml").readText())
    }

    @Test fun downloadsStreamTheFileUnchanged() {
        val (c, id, dir) = serverWithFiles()
        val big = File(dir, "big.bin").apply { writeBytes(ByteArray(3_000_000) { (it * 31).toByte() }) }
        val r = req("GET", "/api/servers/$id/files/download?path=big.bin", cookie = c)
        assertEquals(200, r.status)
        assertEquals("3000000", r.header("content-length"))
        assertTrue(r.header("content-disposition")!!.contains("filename=\"big.bin\""))
        assertEquals(r.header("cache-control"), "no-store")
        assertArrayEquals(big.readBytes(), r.bytes)
        assertEquals(400, req("GET", "/api/servers/$id/files/download?path=world", cookie = c).status) // a folder
    }

    @Test fun headRequestsSendNoBody() {
        val (c, id, _) = serverWithFiles()
        val r = req("HEAD", "/api/servers/$id/files/download?path=pumpkin.toml", cookie = c)
        assertEquals(200, r.status)
        assertEquals(0, r.bytes.size)
    }

    @Test fun deletingNeedsAStoppedServer() {
        val (c, id, dir) = serverWithFiles()
        assertEquals(200, req("POST", "/api/servers/$id/start", cookie = c).status)
        val running = req("DELETE", "/api/servers/$id/files?path=${q("world/level.dat")}", cookie = c)
        assertEquals(409, running.status)
        assertTrue(File(dir, "world/level.dat").exists())
        req("POST", "/api/servers/$id/stop", cookie = c)
        assertEquals(200, req("DELETE", "/api/servers/$id/files?path=${q("world/level.dat")}", cookie = c).status)
        assertFalse(File(dir, "world/level.dat").exists())
        assertEquals(400, req("DELETE", "/api/servers/$id/files?path=", cookie = c).status) // the server folder itself
        assertTrue(dir.exists())
    }

    @Test fun makesFolders() {
        val (c, id, dir) = serverWithFiles()
        assertEquals(201, req("POST", "/api/servers/$id/files/folder", """{"path":"world","name":"datapacks"}""", c).status)
        assertTrue(File(dir, "world/datapacks").isDirectory)
        assertEquals(400, req("POST", "/api/servers/$id/files/folder", """{"path":"world","name":"datapacks"}""", c).status) // exists
        assertEquals(400, req("POST", "/api/servers/$id/files/folder", """{"path":"","name":"a/b"}""", c).status)
    }

    @Test fun unknownServerOrRouteIs404() {
        val c = login()
        assertEquals(404, req("GET", "/api/servers/nope/files", cookie = c).status)
        assertEquals(404, req("GET", "/api/servers/nope/backups", cookie = c).status)
        val (c2, id, _) = serverWithFiles()
        assertEquals(404, req("GET", "/api/servers/$id/files/other", cookie = c2).status)
        assertEquals(404, req("GET", "/api/servers/$id/backups/a/b/c", cookie = c2).status)
    }

    // ---- backups ---------------------------------------------------------------------------------------------------

    @Test fun backsUpListsAndDownloadsAZip() {
        val (c, id, _) = serverWithFiles()
        assertEquals(202, req("POST", "/api/servers/$id/backups", "{}", c).status)
        val done = waitIdle(c, id)
        assertEquals("Backup saved.", done.getString("message"))
        val list = done.getJSONArray("backups")
        assertEquals(1, list.length())
        val b = list.getJSONObject(0)
        assertEquals("manual", b.getString("kind"))
        assertTrue(b.getLong("size") > 0)

        val dl = req("GET", "/api/servers/$id/backups/${b.getString("name")}/download", cookie = c)
        assertEquals(200, dl.status)
        assertEquals("application/zip", dl.header("content-type"))
        assertEquals(b.getLong("size").toString(), dl.header("content-length"))
        val tmpZip = File.createTempFile("download", ".zip").apply { writeBytes(dl.bytes); deleteOnExit() }
        ZipFile(tmpZip).use { z ->
            val names = z.entries().toList().map { it.name }
            assertTrue(names.toString(), "pumpkin.toml" in names && "world/region/r.0.0.mca" in names)
        }
    }

    @Test fun restoringPutsTheFilesBackAndNeedsAStoppedServer() {
        val (c, id, dir) = serverWithFiles()
        req("POST", "/api/servers/$id/backups", "{}", c)
        val name = waitIdle(c, id).getJSONArray("backups").getJSONObject(0).getString("name")
        File(dir, "pumpkin.toml").writeText("motd = \"ruined\"\n")
        File(dir, "world/region/r.0.0.mca").delete()

        req("POST", "/api/servers/$id/start", cookie = c)
        assertEquals(409, req("POST", "/api/servers/$id/backups/$name/restore", "{}", c).status)
        assertEquals("motd = \"ruined\"\n", File(dir, "pumpkin.toml").readText())

        req("POST", "/api/servers/$id/stop", cookie = c)
        assertEquals(202, req("POST", "/api/servers/$id/backups/$name/restore", "{}", c).status)
        assertEquals("Backup restored.", waitIdle(c, id).getString("message"))
        assertEquals("motd = \"hi\"\n", File(dir, "pumpkin.toml").readText())
        assertEquals(5000, File(dir, "world/region/r.0.0.mca").length())
        // The state before the restore was kept as a safety backup.
        assertTrue(req("GET", "/api/servers/$id/backups", cookie = c).json.getJSONArray("backups").toString().contains("prerestore"))
    }

    @Test fun backingUpARunningServerNeedsForce() {
        val (c, id, _) = serverWithFiles()
        req("POST", "/api/servers/$id/start", cookie = c)
        assertEquals(409, req("POST", "/api/servers/$id/backups", "{}", c).status)
        assertEquals(202, req("POST", "/api/servers/$id/backups", """{"force":true}""", c).status)
        waitIdle(c, id)
    }

    @Test fun deletesBackupsAndRefusesBadNames() {
        val (c, id, _) = serverWithFiles()
        req("POST", "/api/servers/$id/backups", "{}", c)
        val name = waitIdle(c, id).getJSONArray("backups").getJSONObject(0).getString("name")
        assertEquals(404, req("DELETE", "/api/servers/$id/backups/${q("../x.zip")}", cookie = c).status)
        assertEquals(404, req("GET", "/api/servers/$id/backups/nope.zip/download", cookie = c).status)
        assertEquals(404, req("POST", "/api/servers/$id/backups/nope.zip/restore", "{}", c).status)
        assertEquals(200, req("DELETE", "/api/servers/$id/backups/$name", cookie = c).status)
        assertEquals(0, req("GET", "/api/servers/$id/backups", cookie = c).json.getJSONArray("backups").length())
    }

    @Test fun backupChangesNeedTheSameSiteHeader() {
        val (c, id, _) = serverWithFiles()
        assertEquals(403, req("POST", "/api/servers/$id/backups", "{}", c, csrf = false).status)
        assertEquals(0, req("GET", "/api/servers/$id/backups", cookie = c).json.getJSONArray("backups").length())
    }

    @Test fun autoBackupIsAServerSetting() {
        val (c, id, _) = serverWithFiles()
        assertFalse(req("GET", "/api/servers/$id/backups", cookie = c).json.getBoolean("autoBackup"))
        assertEquals(200, req("PATCH", "/api/servers/$id", """{"autoBackup":true}""", c).status)
        assertTrue(req("GET", "/api/servers/$id/backups", cookie = c).json.getBoolean("autoBackup"))
        assertEquals(400, req("PATCH", "/api/servers/$id", """{"autoBackup":"yes"}""", c).status)
    }
}
