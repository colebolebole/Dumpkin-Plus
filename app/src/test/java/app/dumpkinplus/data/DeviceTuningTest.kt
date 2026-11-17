package app.dumpkinplus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTuningTest {
    private val good = DeviceSnapshot(
        model = "OnePlus CPH2415", soc = "Snapdragon 8+ Gen 1 (SM8475)", cores = 8, abi = "arm64-v8a",
        clusters = emptyList(), androidVersion = "Android 15 (API 35)", batteryPercent = 80, charging = true,
        thermal = Thermal.Cool, totalMb = 7270, availMb = 1950, address = "192.168.1.205", wifi = WifiBand.Ghz5,
        batteryUnrestricted = true, notificationsAllowed = true,
    )

    private fun problems(s: DeviceSnapshot) = DeviceTuning.items(s).filter { !it.ok }.map { it.id }

    @Test fun cpuCoresAreGroupedIntoClustersFastestFirst() {
        val khz = listOf(1996800L, 1996800, 1996800, 1996800, 2745600, 2745600, 2745600, 3187200)
        val clusters = DeviceTuning.groupClusters(khz)
        assertEquals(listOf(1, 3, 4), clusters.map { it.cores })
        assertEquals("1x 3.2 GHz + 3x 2.7 GHz + 4x 2.0 GHz", DeviceTuning.formatClusters(clusters))
        assertTrue(DeviceTuning.groupClusters(emptyList()).isEmpty())
        assertTrue(DeviceTuning.groupClusters(listOf(0L, -1L)).isEmpty()) // unreadable cores are skipped
    }

    @Test fun memoryAdviceLinesUpWithTheLimitSlider() {
        val a = DeviceTuning.memoryAdvice(7270)
        assertEquals(5376, a.maxMb) // 75%, in 128 MB steps: the same value the settings slider tops out at
        assertTrue(a.comfortableMb < a.cautionAboveMb && a.cautionAboveMb < a.maxMb)
        assertEquals(512, DeviceTuning.memoryAdvice(512).maxMb) // never below the slider's floor
    }

    @Test fun knownSocCodesGetFriendlyNames() {
        assertEquals("Snapdragon 8+ Gen 1 (SM8475)", DeviceTuning.socName("QTI", "SM8475", "qcom"))
        assertEquals("Google Tensor G3", DeviceTuning.socName("Google", "Tensor G3", "zuma"))
        assertEquals("exynos2200", DeviceTuning.socName(null, null, "exynos2200"))
        assertEquals("Unknown processor", DeviceTuning.socName("unknown", "", ""))
    }

    @Test fun deviceNameDoesNotRepeatTheMaker() {
        assertEquals("OnePlus CPH2415", DeviceTuning.deviceName("OnePlus", "CPH2415"))
        assertEquals("Samsung Galaxy S23", DeviceTuning.deviceName("samsung", "Galaxy S23"))
        assertEquals("Pixel 8", DeviceTuning.deviceName("Google", "Pixel 8").removePrefix("Google "))
        assertEquals("Samsung SM-S911B", DeviceTuning.deviceName("samsung", "Samsung SM-S911B"))
    }

    @Test fun thermalAndWifiBandsMapSensibly() {
        assertEquals(Thermal.Cool, DeviceTuning.thermalFromStatus(0))
        assertEquals(Thermal.Warm, DeviceTuning.thermalFromStatus(2))
        assertEquals(Thermal.Hot, DeviceTuning.thermalFromStatus(3))
        assertEquals(Thermal.Hot, DeviceTuning.thermalFromBatteryTemp(45.0))
        assertEquals(WifiBand.Ghz24, DeviceTuning.wifiBand(true, 2437))
        assertEquals(WifiBand.Ghz5, DeviceTuning.wifiBand(true, 5180))
        assertEquals(WifiBand.Ghz6, DeviceTuning.wifiBand(true, 6115))
        assertEquals(WifiBand.Unknown, DeviceTuning.wifiBand(true, null))
        assertEquals(WifiBand.None, DeviceTuning.wifiBand(false, 5180))
    }

    @Test fun homeNetworkAddressesWinOverCarrierOnes() {
        assertEquals("192.168.1.205", DeviceTuning.pickAddress(listOf("100.64.1.2", "192.168.1.205")))
        assertEquals("10.0.0.7", DeviceTuning.pickAddress(listOf("172.1.1.1", "10.0.0.7")))
        assertEquals("172.20.0.3", DeviceTuning.pickAddress(listOf("172.20.0.3")))
        assertEquals("8.8.8.8", DeviceTuning.pickAddress(listOf("8.8.8.8")))
        assertNull(DeviceTuning.pickAddress(emptyList()))
    }

    @Test fun aHealthyPhoneHasNothingToFix() = assertEquals(emptyList<String>(), problems(good))

    @Test fun eachProblemIsReportedWithAnAction() {
        assertEquals(listOf("battery"), problems(good.copy(batteryUnrestricted = false)))
        assertEquals(listOf("notifications"), problems(good.copy(notificationsAllowed = false)))
        assertEquals(listOf("wifi"), problems(good.copy(wifi = WifiBand.Ghz24)))
        assertEquals(listOf("wifi"), problems(good.copy(address = null, wifi = WifiBand.None)))
        assertEquals(listOf("heat"), problems(good.copy(thermal = Thermal.Hot)))
        assertEquals(listOf("memory"), problems(good.copy(availMb = 300)))
        val battery = DeviceTuning.items(good.copy(batteryUnrestricted = false)).first { it.id == "battery" }
        assertEquals(TuningAction.RequestBattery, battery.action)
        assertTrue(battery.actionLabel.isNotBlank())
    }

    @Test fun hotspotOrCableWithAnAddressIsFine() {
        assertFalse(problems(good.copy(wifi = WifiBand.None)).contains("wifi"))
        assertEquals(emptyList<String>(), problems(good.copy(thermal = null))) // no thermal reading is not a problem
    }

    @Test fun androidFiveHasNoDozeSoThereIsNothingToFix() {
        val old = good.copy(batteryUnrestricted = true, dozeAvailable = false)
        val battery = DeviceTuning.items(old).first { it.id == "battery" }
        assertTrue(battery.ok)
        assertEquals(TuningAction.None, battery.action)
        // The same phone with Doze available and not allow-listed would be flagged.
        assertTrue("battery" in problems(good.copy(batteryUnrestricted = false)))
    }

    @Test fun oldSnapdragonChipsAreNamedFromTheCpuinfoLine() {
        assertEquals("Snapdragon 810 (MSM8994)", DeviceTuning.socName(null, null, "Qualcomm Technologies, Inc MSM8994"))
        assertEquals("Snapdragon 410 (MSM8916)", DeviceTuning.socName("unknown", "", "Qualcomm Technologies, Inc MSM8916"))
        assertEquals("mt6735", DeviceTuning.socName(null, null, "mt6735")) // unknown chips fall back to what the phone says
    }
}
