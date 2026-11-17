package app.dumpkinplus.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElfTest {
    /** The first 20 bytes of an ELF file: magic, class, endianness, then e_machine at offset 18. */
    private fun header(machine: Int, class64: Boolean = true, little: Boolean = true) = ByteArray(20).also {
        it[0] = 0x7F; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte()
        it[4] = if (class64) 2 else 1
        it[5] = if (little) 1 else 2
        it[18] = (machine and 0xFF).toByte()
        it[19] = ((machine shr 8) and 0xFF).toByte()
    }

    @Test fun recognisesTheCpuFamiliesAndroidRunsOn() {
        assertEquals(Arch.Arm64, ElfInfo.parse(header(0xB7))?.arch)
        assertEquals(Arch.Arm32, ElfInfo.parse(header(0x28, class64 = false))?.arch)
        assertEquals(Arch.X64, ElfInfo.parse(header(0x3E))?.arch)
        assertEquals(Arch.X86, ElfInfo.parse(header(0x03, class64 = false))?.arch)
        assertEquals(Arch.Other, ElfInfo.parse(header(0xF3))?.arch) // RISC-V
        assertTrue(ElfInfo.parse(header(0xB7))!!.is64Bit)
        assertFalse(ElfInfo.parse(header(0x28, class64 = false))!!.is64Bit)
    }

    @Test fun rejectsFilesThatAreNotExecutables() {
        assertNull(ElfInfo.parse(ByteArray(0)))
        assertNull(ElfInfo.parse("#!/bin/sh\nexit 0\n          ".toByteArray()))
        assertNull(ElfInfo.parse(header(0xB7).copyOf(10))) // truncated
        assertNull(ElfInfo.parse(header(0xB7, little = false))) // big-endian
        // A zip or html error page saved by mistake:
        assertNull(ElfInfo.parse("<html><body>Not Found</body></html>".toByteArray()))
    }

    @Test fun mapsAndroidAbisToArch() {
        assertEquals(Arch.Arm64, Arch.fromAbi("arm64-v8a"))
        assertEquals(Arch.Arm32, Arch.fromAbi("armeabi-v7a"))
        assertEquals(Arch.Arm32, Arch.fromAbi("armeabi"))
        assertEquals(Arch.X64, Arch.fromAbi("x86_64"))
        assertEquals(Arch.X86, Arch.fromAbi("x86"))
        assertEquals(Arch.Other, Arch.fromAbi("mips"))
        assertEquals(Arch.Other, Arch.fromAbi(null))
    }
}
