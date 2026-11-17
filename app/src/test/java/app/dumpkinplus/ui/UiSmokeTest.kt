package app.dumpkinplus.ui

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.dumpkinplus.DumpkinApp
import app.dumpkinplus.MainActivity
import app.dumpkinplus.data.ServerProfile
import app.dumpkinplus.dumpkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/**
 * Builds and drives the real screens. Runs on Android 5.0 (API 21), the oldest version the app supports, and on a
 * current Android (API 34), so both the legacy and the modern code paths are exercised without a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 34], application = DumpkinApp::class)
class UiSmokeTest {
    private fun settle() {
        repeat(15) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun launch(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible().get()
        settle()
        return a
    }

    private fun texts(v: View): List<String> = buildList {
        if (v is TextView) add(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) addAll(texts(v.getChildAt(i)))
    }

    private fun find(v: View, text: String): TextView? = (if (v is TextView && v.text.toString() == text) v else null)
        ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it), text) } }

    private fun root(a: MainActivity): View = a.findViewById<ViewGroup>(android.R.id.content)

    @Test fun everyTabBuilds() {
        val a = launch()
        val expect = listOf("No servers yet", "Engine", "Memory", "Dashboard")
        for ((i, word) in expect.withIndex()) {
            a.setTab(i)
            settle()
            val all = texts(root(a))
            assertTrue("tab $i should show \"$word\" but shows $all", all.any { it.contains(word) })
        }
    }

    @Test fun tuningTabReadsThisPhoneWithoutCrashing() {
        val a = launch()
        a.setTab(2)
        settle()
        val all = texts(root(a))
        assertTrue(all.any { it.startsWith("Android ") || it.contains("API") })
        assertTrue("checks should be listed: $all", all.any { it == "Battery optimisation" })
    }

    @Test fun creatingAServerShowsItInTheList() {
        val a = launch()
        val newServer = find(root(a), "New server")
        assertNotNull(newServer)
        newServer!!.performClick()
        settle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("the create dialog should open", dialog)
        val create = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertTrue("defaults are valid, so Create is enabled", create.isEnabled)
        create.performClick()
        settle()
        // Creating a server opens it.
        assertTrue(texts(root(a)).any { it == "Start server" })
        assertEquals(1, a.dumpkin.manager.profiles.value.size)
    }

    @Test fun serverScreenShowsConsoleAndSettings() {
        val app = org.robolectric.RuntimeEnvironment.getApplication().dumpkin
        val p = ServerProfile(id = "abc", name = "Test World", port = 25570)
        app.manager.create(p)
        val a = launch()
        a.openServer("abc")
        settle()
        val console = texts(root(a))
        assertTrue(console.contains("Test World"))
        assertTrue(console.contains("Start server"))
        assertTrue(console.any { it.startsWith("Server output") })

        find(root(a), "Settings")!!.performClick()
        settle()
        val settings = texts(root(a))
        assertTrue("settings form should list its sections: $settings", settings.containsAll(listOf("General", "Performance", "Resources", "Network", "Advanced")))
        assertTrue(settings.contains("Save changes"))
        // Quick pick must include the low-end preset added for older phones.
        assertTrue(settings.contains("Low-end"))
    }

    @Test fun startingWithoutAnEngineExplainsWhatToDo() {
        val app = org.robolectric.RuntimeEnvironment.getApplication().dumpkin
        app.manager.create(ServerProfile(id = "x1", name = "NoEngine"))
        assertTrue(app.manager.start("x1")!!.contains("engine", ignoreCase = true))
        assertFalse(app.manager.runtime("x1")!!.state.value.isActive)
    }
}
