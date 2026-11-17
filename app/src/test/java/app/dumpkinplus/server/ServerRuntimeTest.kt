package app.dumpkinplus.server

import app.dumpkinplus.data.RuntimeState
import app.dumpkinplus.data.ServerProfile
import app.dumpkinplus.data.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServerRuntimeTest {
    private fun runtime() = ServerRuntime(
        File(System.getProperty("java.io.tmpdir")),
        ServerProfile(id = "t", name = "T"),
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        onStatus = { _, _ -> },
        onRestart = {},
    )

    @Test fun aServerThatIsNotRunningCannotBeRestarted() {
        val rt = runtime()
        assertFalse(rt.restart())
        assertFalse(rt.state.value.restarting)
    }

    @Test fun aServerThatHasNotFinishedLaunchingIsLeftAlone() {
        // Status says Starting but there is no process yet (the engine is still being spawned).
        val rt = runtime()
        rt.state.value = RuntimeState(status = Status.Starting)
        assertFalse(rt.restart())
        assertFalse("must not claim to be restarting", rt.state.value.restarting)
        assertEquals(Status.Starting, rt.state.value.status)
    }

    @Test fun clearingTheRestartFlagIsHarmlessWhenNothingIsRestarting() {
        val rt = runtime()
        rt.clearRestarting()
        assertFalse(rt.state.value.restarting)
        rt.state.value = RuntimeState(status = Status.Stopped, restarting = true)
        rt.clearRestarting()
        assertTrue(rt.state.value.status == Status.Stopped && !rt.state.value.restarting)
    }
}
