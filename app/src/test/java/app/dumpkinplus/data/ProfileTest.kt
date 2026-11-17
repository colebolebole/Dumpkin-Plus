package app.dumpkinplus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileTest {
    @Test fun resourceSettingsRoundTrip() {
        val p = ServerProfile(
            name = "x", memoryLimitMb = 1536, memoryAction = MemoryAction.Restart, cpuThreads = 3, autoRestart = true,
        )
        assertEquals(p, ServerProfile.fromJson(JSONObject(p.toJson().toString())))
    }

    @Test fun oldProfilesGetSafeDefaults() {
        val old = JSONObject().put("id", "abc").put("name", "Legacy").put("port", 25570)
        val p = ServerProfile.fromJson(old)
        assertEquals(0, p.memoryLimitMb)
        assertEquals(MemoryAction.Warn, p.memoryAction)
        assertEquals(0, p.cpuThreads)
        assertEquals(false, p.autoRestart)
        assertEquals(true, p.lightPing)
        assertEquals(Compression.Fast, p.compression)
        assertEquals(true, p.fetchSkins)
    }

    @Test fun smallPhonesStartWithLighterServers() {
        val low = ServerProfile.forDevice(1024)
        assertEquals(DistancePreset.Minimal.view, low.viewDistance)
        assertEquals(DistancePreset.Minimal.simulation, low.simulationDistance)
        assertEquals(8, low.maxPlayers)
        val edge = ServerProfile.forDevice(ServerProfile.LOW_END_MB) // the cut-off itself still counts as low-end
        assertEquals(edge, low.copy(id = edge.id))
        val big = ServerProfile.forDevice(6144)
        assertEquals(DistancePreset.Fast.view, big.viewDistance)
        assertEquals(20, big.maxPlayers)
        // Unknown memory (0) must not be mistaken for a tiny phone.
        assertEquals(20, ServerProfile.forDevice(0).maxPlayers)
    }

    @Test fun everyPresetIsWithinWhatTheSettingsAllow() {
        DistancePreset.entries.forEach {
            val p = ServerProfile(name = "x", viewDistance = it.view, simulationDistance = it.simulation)
            assertEquals(null, ProfileRules.validate(p, emptyList()))
        }
    }
}
