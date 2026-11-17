package app.dumpkinplus.data

import android.app.ActivityManager
import android.content.Context

data class DeviceMemory(val totalMb: Int, val availMb: Int)

fun deviceMemory(ctx: Context): DeviceMemory {
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    return DeviceMemory((mi.totalMem / 1_048_576).toInt(), (mi.availMem / 1_048_576).toInt())
}
