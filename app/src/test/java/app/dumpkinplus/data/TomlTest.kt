package app.dumpkinplus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TomlTest {
    @Test fun createsSectionsFromEmpty() {
        val out = Toml.apply("", listOf(
            Toml.Edit("", "default_gamemode", Toml.q("Creative")),
            Toml.Edit("networking.java", "address", Toml.q("0.0.0.0:25570")),
            Toml.Edit("networking.java", "max_players", "8"),
        ))
        assertEquals("Creative", Toml.read(out, "", "default_gamemode"))
        assertEquals("0.0.0.0:25570", Toml.read(out, "networking.java", "address"))
        assertEquals("8", Toml.read(out, "networking.java", "max_players"))
        assertTrue(out.indexOf("default_gamemode") < out.indexOf("[networking.java]"))
    }

    @Test fun preservesOtherKeysAndComments() {
        val src = """
            # my config
            seed = 5
            [world]
            autosave_ticks = 6000
            [networking.java]
            enabled = true
            max_players = 1000 # lots
            [pvp]
            enabled = true
        """.trimIndent()
        val out = Toml.apply(src, listOf(
            Toml.Edit("networking.java", "max_players", "20"),
            Toml.Edit("networking.java", "motd", Toml.q("Hi \"there\"")),
            Toml.Edit("", "default_difficulty", Toml.q("Hard")),
        ))
        assertTrue(out.contains("# my config"))
        assertEquals("6000", Toml.read(out, "world", "autosave_ticks"))
        assertEquals("true", Toml.read(out, "pvp", "enabled"))
        assertEquals("true", Toml.read(out, "networking.java", "enabled"))
        assertEquals("20", Toml.read(out, "networking.java", "max_players"))
        assertEquals("Hi \"there\"", Toml.read(out, "networking.java", "motd"))
        assertEquals("Hard", Toml.read(out, "", "default_difficulty"))
        assertEquals("5", Toml.read(out, "", "seed"))
    }

    @Test fun sameKeyInOtherSectionIsUntouched() {
        val src = "[a]\nenabled = true\n[b]\nenabled = true\n"
        val out = Toml.apply(src, listOf(Toml.Edit("b", "enabled", "false")))
        assertEquals("true", Toml.read(out, "a", "enabled"))
        assertEquals("false", Toml.read(out, "b", "enabled"))
    }
}
