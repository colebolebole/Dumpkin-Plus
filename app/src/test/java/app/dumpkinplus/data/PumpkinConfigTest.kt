package app.dumpkinplus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PumpkinConfigTest {
    private fun tmp() = Files.createTempDirectory("dumpkin").toFile()

    @Test fun writesJoinSpeedKeysUnderTheirDocumentedSections() {
        val dir = tmp()
        PumpkinConfig.write(dir, ServerProfile(id = "t", name = "T", compression = Compression.Fast, fetchSkins = false))
        val t = PumpkinConfig.file(dir).readText()
        assertEquals("true", Toml.read(t, "networking.java.compression", "enabled"))
        assertEquals("1", Toml.read(t, "networking.java.compression", "level"))
        assertEquals("false", Toml.read(t, "networking.java.authentication.textures", "enabled"))
        assertEquals("false", Toml.read(t, "", "use_favicon"))
        assertEquals("6", Toml.read(t, "networking.java", "view_distance"))
        assertEquals("false", Toml.read(t, "telemetry", "enabled"))
        // top-level keys must come before the first table or TOML would nest them
        assertTrue(t.indexOf("use_favicon") < t.indexOf("["))
    }

    @Test fun compressionOffLeavesLevelAloneAndRoundTrips() {
        val dir = tmp()
        val p = ServerProfile(id = "t", name = "T", compression = Compression.Off)
        PumpkinConfig.write(dir, p)
        assertEquals("false", Toml.read(PumpkinConfig.file(dir).readText(), "networking.java.compression", "enabled"))
        assertEquals(Compression.Off, PumpkinConfig.pull(dir, p.copy(compression = Compression.Balanced)).compression)
    }

    @Test fun balancedWritesPumpkinDefaultLevel() {
        val dir = tmp()
        val p = ServerProfile(id = "t", name = "T", compression = Compression.Balanced)
        PumpkinConfig.write(dir, p)
        assertEquals("4", Toml.read(PumpkinConfig.file(dir).readText(), "networking.java.compression", "level"))
        assertEquals(Compression.Balanced, PumpkinConfig.pull(dir, p.copy(compression = Compression.Fast)).compression)
    }

    @Test fun bedrockPortIsWrittenToTheNethernetAddressAndRoundTrips() {
        val dir = tmp()
        val p = ServerProfile(id = "t", name = "T", bedrock = true, bedrockPort = 19140)
        PumpkinConfig.write(dir, p)
        val t = PumpkinConfig.file(dir).readText()
        assertEquals("true", Toml.read(t, "networking.bedrock", "enabled"))
        assertEquals("0.0.0.0:19140", Toml.read(t, "networking.bedrock.nethernet", "address"))
        val pulled = PumpkinConfig.pull(dir, p.copy(bedrockPort = 19132, bedrock = false))
        assertEquals(19140, pulled.bedrockPort)
        assertTrue(pulled.bedrock)
    }

    @Test fun existingFileKeepsUnmanagedSettings() {
        val dir = tmp()
        PumpkinConfig.file(dir).writeText("[world]\nautosave_ticks = 123\n[networking.java.compression]\nthreshold = 999\n")
        PumpkinConfig.write(dir, ServerProfile(id = "t", name = "T"))
        val t = PumpkinConfig.file(dir).readText()
        assertEquals("123", Toml.read(t, "world", "autosave_ticks"))
        assertEquals("999", Toml.read(t, "networking.java.compression", "threshold"))
        assertEquals("1", Toml.read(t, "networking.java.compression", "level"))
    }
}
