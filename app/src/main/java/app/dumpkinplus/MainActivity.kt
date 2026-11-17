package app.dumpkinplus

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.dumpkinplus.ui.EngineScreen
import app.dumpkinplus.ui.HomeScreen
import app.dumpkinplus.ui.MATCH
import app.dumpkinplus.ui.Pal
import app.dumpkinplus.ui.Screen
import app.dumpkinplus.ui.ServerScreen
import app.dumpkinplus.ui.TuningScreen
import app.dumpkinplus.ui.WRAP
import app.dumpkinplus.ui.WebScreen
import app.dumpkinplus.ui.add
import app.dumpkinplus.ui.dp
import app.dumpkinplus.ui.text
import app.dumpkinplus.ui.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The only activity. Plain [Activity] with hand-built views: no AndroidX, so it starts quickly and uses little memory
 * on phones from the Android 5 era.
 */
class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private val tabViews = ArrayList<TextView>()

    private var tab = 0
    private var openId: String? = null
    private var screen: Screen? = null
    private var started = false
    private var scope: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val mode = dumpkin.themeMode
        Pal.dark = when (mode) {
            ThemeMode.Dark -> true
            ThemeMode.Light -> false
            ThemeMode.Auto ->
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        setTheme(if (Pal.dark) R.style.Theme_Dark else R.style.Theme_Light)
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt(STATE_TAB) ?: 0
        openId = savedInstanceState?.getString(STATE_OPEN)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.bg)
        }
        content = FrameLayout(this)
        root.add(content, MATCH, 0, 1f)
        nav = buildNav()
        root.add(nav, MATCH, WRAP)
        setContentView(root)
        route()
    }

    private fun buildNav(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Pal.surface)
        }
        listOf("Servers", "Engine", "Tuning", "Dashboard").forEachIndexed { i, label ->
            val t = text(label, 13.5f).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(14))
                setOnClickListener { setTab(i) }
            }
            tabViews += t
            bar.add(t, 0, WRAP, 1f)
        }
        return bar
    }

    private fun styleNav() {
        tabViews.forEachIndexed { i, t ->
            t.setTextColor(if (i == tab) Pal.primary else Pal.dim)
            t.setTypeface(null, if (i == tab) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    fun setTab(i: Int) {
        tab = i
        openId = null
        route()
    }

    fun openServer(id: String) {
        openId = id
        route()
    }

    fun closeServer() {
        openId = null
        route()
    }

    /** Swaps the visible screen for whatever [tab] and [openId] say should be showing. */
    private fun route() {
        screen?.let { if (started) it.stop() }
        val next: Screen = when {
            openId != null -> ServerScreen(this, openId!!)
            tab == 0 -> HomeScreen(this)
            tab == 1 -> EngineScreen(this)
            tab == 2 -> TuningScreen(this)
            else -> WebScreen(this)
        }
        content.removeAllViews()
        content.addView(next.create(), FrameLayout.LayoutParams(MATCH, MATCH))
        nav.visibility = if (openId != null) View.GONE else View.VISIBLE
        styleNav()
        screen = next
        if (started) next.start()
    }

    /** Opens the system file chooser so an engine downloaded elsewhere can be imported. */
    fun pickEngineFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQ_IMPORT)
        } catch (_: Exception) {
            toast("No file manager found on this phone.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                dumpkin.engines.importFrom(uri)
                toast("Importing…")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        screen?.start()
        // Keep the display on while a server runs, if the user asked for it (best ping needs the screen on).
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        // A dot on the Engine tab when a newer engine build is waiting.
        s.launch {
            dumpkin.engines.state.collect { st ->
                tabViews.getOrNull(1)?.text = if (st.updates.isNotEmpty()) "Engine •" else "Engine"
            }
        }
        s.launch {
            combine(dumpkin.keepScreenOn, dumpkin.manager.active) { keep, active -> keep && active.isNotEmpty() }
                .collect { hold ->
                    if (hold) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
        }
    }

    override fun onStop() {
        started = false
        screen?.stop()
        scope?.cancel()
        scope = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
        openId?.let { outState.putString(STATE_OPEN, it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Dark mode flipped while the app was open and the user follows the system: rebuild with the other colours.
        if (dumpkin.themeMode == ThemeMode.Auto) {
            val nowDark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (nowDark != Pal.dark) recreate()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen?.onBack() == true) return
        if (openId != null) closeServer() else super.onBackPressed()
    }

    companion object {
        private const val REQ_IMPORT = 7
        private const val STATE_TAB = "tab"
        private const val STATE_OPEN = "open"
    }
}
