package app.dumpkinplus.backup

import app.dumpkinplus.files.FileAccessException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_795_000_000_000L // a fixed clock so file names are predictable
    private fun manager() = BackupManager(tmp.newFolder("backups")) { now.also { now += 5_000 } }

    private fun server(): File {
        val d = tmp.newFolder("servers", "abc")
        File(d, "pumpkin.toml").writeText("motd = \"hi\"\n")
        File(d, "world/region").mkdirs()
        File(d, "world/region/r.0.0.mca").writeBytes(ByteArray(2000) { (it % 7).toByte() })
        File(d, "world/empty").mkdirs()
        return d
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
    }.toByteArray()

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("expected a refusal")
        } catch (_: IOException) {
        }
    }

    @Test fun backsUpTheWholeFolderAndListsItNewestFirst() {
        val m = manager()
        val d = server()
        val a = m.create("abc", d)
        val b = m.create("abc", d, BackupKind.Auto)
        assertTrue(a.name.matches(Regex("""backup-\d{8}-\d{6}\.zip""")))
        assertTrue(b.name.endsWith("-auto.zip"))
        assertEquals(listOf(b.name, a.name), m.list("abc").map { it.name })
        assertEquals(BackupKind.Auto, m.list("abc")[0].kind)
        assertEquals(BackupKind.Manual, m.list("abc")[1].kind)
        assertTrue(a.sizeBytes > 0)
        assertTrue(m.list("other").isEmpty())
    }

    @Test fun restoreBringsBackFilesAndFoldersIncludingEmptyOnes() {
        val m = manager()
        val d = server()
        val b = m.create("abc", d)
        File(d, "pumpkin.toml").writeText("motd = \"ruined\"\n")
        File(d, "world/region/r.0.0.mca").delete()
        File(d, "extra.txt").writeText("new since the backup")
        m.restore("abc", d, b.name)
        assertEquals("motd = \"hi\"\n", File(d, "pumpkin.toml").readText())
        assertEquals(2000, File(d, "world/region/r.0.0.mca").length())
        assertTrue(File(d, "world/empty").isDirectory)
        assertFalse(File(d, "extra.txt").exists())
        assertFalse(File(d.parentFile, "abc.restore-old").exists())
        assertFalse(File(d.parentFile, "abc.restore-tmp").exists())
    }

    @Test fun aSafetyBackupIsTakenBeforeARestoreSoItCanBeUndone() {
        val m = manager()
        val d = server()
        val b = m.create("abc", d)
        File(d, "extra.txt").writeText("work done after the backup")
        m.restore("abc", d, b.name)
        val safety = m.list("abc").single { it.kind == BackupKind.PreRestore }
        m.restore("abc", d, safety.name)
        assertEquals("work done after the backup", File(d, "extra.txt").readText())
    }

    @Test fun aZipWithPathsOutsideTheServerFolderIsNeverExtracted() {
        val m = manager()
        val d = server()
        val imported = m.importZip("abc", zipOf("../../escaped.txt" to "pwned").inputStream())
        refused { m.restore("abc", d, imported.name) }
        assertFalse(File(tmp.root, "escaped.txt").exists())
        assertFalse(File(d.parentFile.parentFile, "escaped.txt").exists())
        // The server's own files are untouched after the refused restore.
        assertEquals("motd = \"hi\"\n", File(d, "pumpkin.toml").readText())
    }

    @Test fun importingRequiresARealNonEmptyZip() {
        val m = manager()
        refused { m.importZip("abc", "this is not a zip".byteInputStream()) }
        refused { m.importZip("abc", ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray().inputStream()) }
        assertTrue(m.list("abc").isEmpty())
        assertTrue(File(tmp.root, "backups/abc").listFiles().orEmpty().isEmpty()) // no half-written leftovers
        assertEquals(BackupKind.Imported, m.importZip("abc", zipOf("a.txt" to "1").inputStream()).kind)
    }

    @Test fun exportCopiesTheZipAndBadNamesAreRefused() {
        val m = manager()
        val d = server()
        val b = m.create("abc", d)
        val out = ByteArrayOutputStream()
        m.export("abc", b.name, out)
        assertEquals(b.sizeBytes, out.size().toLong())
        for (bad in listOf("../x.zip", "backup-1.zip", "x.zip", "backup-20260101-000000.zip/../../x", "")) {
            assertNull(bad, m.file("abc", bad))
            assertFalse(m.delete("abc", bad))
        }
        assertNotNull(m.file("abc", b.name))
        assertTrue(m.delete("abc", b.name))
        assertTrue(m.list("abc").isEmpty())
    }

    @Test fun pruningOnlyRemovesOldAutomaticBackups() {
        val m = manager()
        val d = server()
        val manual = m.create("abc", d)
        val imported = m.importZip("abc", zipOf("a.txt" to "1").inputStream())
        repeat(8) { m.create("abc", d, BackupKind.Auto) }
        m.pruneAuto("abc", 5)
        val left = m.list("abc")
        assertEquals(5, left.count { it.kind == BackupKind.Auto })
        assertTrue(left.any { it.name == manual.name })
        assertTrue(left.any { it.name == imported.name })
        val autos = left.filter { it.kind == BackupKind.Auto }.map { it.createdAt }
        assertEquals(autos.sortedDescending(), autos)
    }

    @Test fun twoBackupsInTheSameSecondGetDifferentNames() {
        val m = BackupManager(tmp.newFolder("b2")) { 1_795_000_000_000L }
        val d = server()
        val a = m.create("abc", d)
        val b = m.create("abc", d)
        assertTrue(a.name != b.name)
    }

    @Test fun backingUpAServerWithNoFilesIsAClearError() {
        val m = manager()
        try {
            m.create("abc", File(tmp.root, "missing"))
            fail()
        } catch (e: FileAccessException) {
            assertTrue(e.message!!.contains("Start it once"))
        }
    }

    @Test fun deletingAServerRemovesItsBackups() {
        val m = manager()
        val d = server()
        m.create("abc", d)
        m.deleteAll("abc")
        assertTrue(m.list("abc").isEmpty())
    }

    @Test fun aServerCanOnlyDoOneThingAtATime() {
        val m = manager()
        val gate = CountDownLatch(1)
        assertTrue(m.start("abc", "Backing up…") { gate.await(); "done" })
        assertEquals("Backing up…", m.statusOf("abc").busy)
        assertFalse(m.start("abc", "Restoring…") { "second" })
        assertTrue(m.start("other", "Backing up…") { "fine" }) // a different server is independent
        gate.countDown()
        repeat(200) { if (m.statusOf("abc").busy != null) Thread.sleep(10) }
        assertEquals("done", m.statusOf("abc").message)
        assertFalse(m.statusOf("abc").error)
    }

    @Test fun aFailureIsReportedNotThrown() {
        val m = manager()
        assertTrue(m.start("abc", "Backing up…") { throw IOException("disk full") })
        repeat(200) { if (m.statusOf("abc").busy != null) Thread.sleep(10) }
        assertEquals("disk full", m.statusOf("abc").message)
        assertTrue(m.statusOf("abc").error)
    }
}
