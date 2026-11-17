package app.dumpkinplus.ui

import android.view.View
import app.dumpkinplus.DumpkinApp
import app.dumpkinplus.MainActivity
import app.dumpkinplus.dumpkin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * One screen of the app. [build] creates its views once; [onStart] then subscribes to whatever it displays, using
 * [scope], which is cancelled in [stop]. Nothing is collected while the app is in the background.
 */
abstract class Screen(protected val act: MainActivity) {
    protected val app: DumpkinApp = act.dumpkin
    lateinit var view: View
        private set
    protected lateinit var scope: CoroutineScope
        private set

    fun create(): View {
        view = build()
        return view
    }

    fun start() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        onStart()
    }

    fun stop() {
        if (::scope.isInitialized) scope.cancel()
        onStop()
    }

    protected abstract fun build(): View
    protected open fun onStart() {}
    protected open fun onStop() {}

    /** @return true if the screen handled the back button itself. */
    open fun onBack(): Boolean = false
}
