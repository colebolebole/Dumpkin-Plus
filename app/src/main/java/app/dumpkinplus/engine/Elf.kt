package app.dumpkinplus.engine

/** The CPU families Dumpkin+ knows about, from a device's first supported ABI or an ELF header. */
enum class Arch(val label: String) {
    Arm64("arm64"), Arm32("32-bit ARM"), X64("x86-64"), X86("x86"), Other("unknown");

    companion object {
        /** From an Android ABI string such as "arm64-v8a" or "armeabi-v7a". */
        fun fromAbi(abi: String?): Arch = when {
            abi == null -> Other
            abi.startsWith("arm64") -> Arm64
            abi.startsWith("armeabi") -> Arm32
            abi == "x86_64" -> X64
            abi == "x86" -> X86
            else -> Other
        }
    }
}

/** What the first bytes of an executable say about it. Used to refuse files that can't run on this phone. */
data class ElfInfo(val arch: Arch, val is64Bit: Boolean) {
    companion object {
        const val HEADER_SIZE = 20

        /** @return null when [header] (at least the first [HEADER_SIZE] bytes) is not a little-endian ELF file. */
        fun parse(header: ByteArray): ElfInfo? {
            if (header.size < HEADER_SIZE) return null
            val magic = header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
            if (!magic) return null
            val is64 = header[4].toInt() == 2
            if (header[5].toInt() != 1) return null // big-endian: no Android device is
            val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
            val arch = when (machine) {
                0xB7 -> Arch.Arm64
                0x28 -> Arch.Arm32
                0x3E -> Arch.X64
                0x03 -> Arch.X86
                else -> Arch.Other
            }
            return ElfInfo(arch, is64)
        }
    }
}
