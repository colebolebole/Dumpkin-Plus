package app.dumpkinplus.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ServerFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun server(): Pair<File, ServerFiles> {
        val root = tmp.newFolder("server")
        File(root, "pumpkin.toml").writeText("motd = \"hi\"\n")
        File(root, "world/region").mkdirs()
        File(root, "world/level.dat").writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 0, 1, 2))
        File(root, "world/region/r.0.0.mca").writeBytes(ByteArray(100))
        File(root, "Zeta.txt").writeText("z")
        File(root, "alpha.txt").writeText("a")
        return root to ServerFiles(root)
    }

    private fun denied(block: () -> Unit) {
        try {
            block()
            fail("expected FileAccessException")
        } catch (_: FileAccessException) {
        }
    }

    @Test fun listsFoldersFirstThenFilesIgnoringCase() {
        val (_, f) = server()
        assertEquals(listOf("world", "alpha.txt", "pumpkin.toml", "Zeta.txt"), f.list("").map { it.name })
        val inner = f.list("world")
        assertEquals(listOf("region", "level.dat"), inner.map { it.name })
        assertEquals("world/level.dat", inner[1].path)
        assertEquals(5L, inner[1].size)
        assertTrue(inner[0].isDir)
    }

    @Test fun pathsThatEscapeTheServerFolderAreRefused() {
        val (root, f) = server()
        File(root.parentFile, "secret.txt").writeText("nope")
        for (bad in listOf("..", "../secret.txt", "world/../../secret.txt", "/etc/passwd/../../..", "world\\..\\..\\secret.txt", "a\u0000b")) {
            denied { f.resolve(bad) }
        }
        denied { f.readText("../secret.txt") }
        denied { f.delete("../secret.txt") }
        assertTrue(File(root.parentFile, "secret.txt").exists())
    }

    @Test fun anAbsolutePathIsTreatedAsRelativeToTheServerFolder() {
        val (root, f) = server()
        // "/pumpkin.toml" means the server's file of that name, never the phone's root.
        assertEquals(File(root, "pumpkin.toml").canonicalFile, f.resolve("/pumpkin.toml"))
    }

    @Test fun readsAndWritesTextAndRefusesBinary() {
        val (_, f) = server()
        assertEquals("motd = \"hi\"\n", f.readText("pumpkin.toml"))
        f.writeText("pumpkin.toml", "motd = \"changed\"\n")
        assertEquals("motd = \"changed\"\n", f.readText("pumpkin.toml"))
        denied { f.readText("world/level.dat") } // binary
        denied { f.readText("world") } // a folder
        denied { f.writeText("missing.txt", "x") } // writeText doesn't create files
        denied { f.writeText("pumpkin.toml", "a\u0000b") }
    }

    @Test fun bigFilesAreNotOpenedAsText() {
        val (root, f) = server()
        File(root, "big.log").writeText("x".repeat(ServerFiles.MAX_TEXT_BYTES + 1))
        denied { f.readText("big.log") }
    }

    @Test fun textDetectionHandlesUnicodeCutAtTheSampleBoundary() {
        val (root, f) = server()
        // 4095 ASCII bytes then 3-byte characters straddling the 4096-byte sample edge.
        File(root, "u.txt").writeText("a".repeat(4095) + "€€€")
        assertTrue(f.isText(File(root, "u.txt")))
        File(root, "empty.txt").writeText("")
        assertTrue(f.isText(File(root, "empty.txt")))
        assertFalse(f.isText(File(root, "world/level.dat")))
    }

    @Test fun addingFilesAndFolders() {
        val (_, f) = server()
        f.mkdir("", "plugins")
        f.write("plugins", "a.jar", "bytes".byteInputStream())
        assertEquals("bytes", f.readText("plugins/a.jar"))
        denied { f.write("plugins", "a.jar", "again".byteInputStream()) } // won't overwrite by default
        f.write("plugins", "a.jar", "again".byteInputStream(), overwrite = true)
        assertEquals("again", f.readText("plugins/a.jar"))
        assertFalse(File(f.resolve("plugins"), "a.jar.part").exists())
        for (bad in listOf("", "..", ".", "a/b", "a\\b", "x\u0001y", "n".repeat(201))) denied { f.mkdir("", bad) }
        denied { f.mkdir("", "plugins") }
        denied { f.write("nope", "x.txt", "x".byteInputStream()) }
    }

    @Test fun deletingFilesAndFoldersButNotTheServerFolder() {
        val (root, f) = server()
        f.delete("alpha.txt")
        assertFalse(File(root, "alpha.txt").exists())
        f.delete("world")
        assertFalse(File(root, "world").exists())
        denied { f.delete("") }
        denied { f.delete("/") }
        denied { f.delete("nothing") }
        assertTrue(root.exists())
    }

    @Test fun relativePathsRoundTrip() {
        val (_, f) = server()
        assertEquals("world/region/r.0.0.mca", f.relative(f.resolve("world/region/r.0.0.mca")))
        assertEquals("", f.relative(f.resolve("")))
    }
}
