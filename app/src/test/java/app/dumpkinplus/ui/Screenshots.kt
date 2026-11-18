package app.dumpkinplus.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import app.dumpkinplus.DumpkinApp
import app.dumpkinplus.MainActivity
import app.dumpkinplus.backup.BackupKind
import app.dumpkinplus.data.RuntimeState
import app.dumpkinplus.data.ServerProfile
import app.dumpkinplus.data.Status
import app.dumpkinplus.dumpkin
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screens to PNGs in docs/screenshots, with made-up servers. Off by default:
 *   DUMPKINPLUS_SCREENSHOTS=1 ./gradlew testDebugUnitTest --tests '*Screenshots*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = DumpkinApp::class, qualifiers = "w360dp-h720dp-xhdpi")
class Screenshots {
    private fun settle() {
        repeat(15) { Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun shoot(a: MainActivity, name: String) {
        settle()
        val root = a.findViewById<ViewGroup>(android.R.id.content)
        val w = 720
        val h = 1440
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val out = File("../docs/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun render() {
        assumeTrue(System.getenv("DUMPKINPLUS_SCREENSHOTS") == "1")
        val app = RuntimeEnvironment.getApplication().dumpkin
        app.manager.create(ServerProfile(id = "a", name = "Survival World", port = 25565))
        app.manager.create(ServerProfile(id = "b", name = "Creative Plots", port = 25566))
        app.manager.runtime("a")!!.apply {
            state.value = RuntimeState(Status.Running, online = 3, max = 20, version = "1.21.8", rssMb = 412, startedAt = System.currentTimeMillis() - 754_000)
            logs.value = listOf(
                "▶ Starting \"Survival World\" on port 25565",
                "[INFO] Pumpkin starting, listening on 0.0.0.0:25565",
                "[INFO] Loaded world in 2.4s",
                "[WARN] Chunk 12,-4 took 480ms to generate",
                "[INFO] Steve joined the game",
                "> list",
                "There are 3 of 20 players online: Steve, Alex, Sam",
            )
        }
        val a = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible().get()
        shoot(a, "servers")
        a.openServer("a")
        shoot(a, "console")
        settle()
        fun find(v: View, text: String): android.widget.TextView? =
            (if (v is android.widget.TextView && v.text.toString() == text) v else null)
                ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it), text) } }
        find(a.findViewById(android.R.id.content), "Settings")!!.performClick()
        shoot(a, "settings")
        // A small world and one backup, for the Backups and Files tabs.
        val dir = app.manager.dir("a")
        File(dir, "world/region").mkdirs()
        File(dir, "world/region/r.0.0.mca").writeBytes(ByteArray(1_800_000))
        File(dir, "world/level.dat").writeBytes(ByteArray(2200))
        File(dir, "whitelist.json").writeText("[]\n")
        app.manager.backups.create("a", dir)
        app.manager.backups.create("a", dir, BackupKind.Auto)
        find(a.findViewById(android.R.id.content), "Backups")!!.performClick()
        shoot(a, "backups")
        find(a.findViewById(android.R.id.content), "Files")!!.performClick()
        shoot(a, "files")
        a.setTab(1); shoot(a, "engine")
        a.setTab(2); shoot(a, "tuning")
        a.setTab(3); shoot(a, "dashboard")
    }
}
