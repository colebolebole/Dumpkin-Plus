package app.dumpkinplus.web

import app.dumpkinplus.data.GameMode
import app.dumpkinplus.data.RuntimeState
import app.dumpkinplus.data.ServerProfile
import app.dumpkinplus.data.Status
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a real test: with DUMPKIN_PREVIEW set it serves the real dashboard (real server, real web files) on
 * http://127.0.0.1:18080 over a fake backend, so the UI can be looked at in a browser. Password: preview-1234
 */
class WebPreview {
    @Test fun serve() {
        assumeTrue(System.getenv("DUMPKIN_PREVIEW") != null)
        val backend = FakeBackend(instant = false)
        val now = System.currentTimeMillis()
        backend.seed(
            ServerProfile(id = "smp", name = "Survival SMP", port = 25565, motd = "Welcome to the SMP"),
            RuntimeState(Status.Running, online = 2, max = 20, version = "26.3", rssMb = 612, startedAt = now - 754_000),
            listOf(
                "▶ Starting \"Survival SMP\" on port 25565",
                "23:59:43 INFO Loaded 26 int and 4 float embedded context provider(s)",
                "23:59:43 INFO Started server; took 26ms",
                "23:59:43 INFO Server is now running. Connect using port: Java Edition: 0.0.0.0:25565",
                "23:59:51 INFO Alex joined the game",
                "00:01:12 WARN Chunk generation is falling behind",
                "00:02:30 INFO Steve joined the game",
                "00:02:41 INFO <Steve> anyone up for a nether run?",
                "00:02:58 INFO <Alex> yes! grabbing my pickaxe",
            ),
        )
        backend.seed(
            ServerProfile(id = "cre", name = "Creative Build", port = 25566, gameMode = GameMode.Creative, bedrock = true, bedrockPort = 19132),
            RuntimeState(Status.Running, online = 0, max = 20, version = "26.3", rssMb = 388, startedAt = now - 2_280_000),
        )
        backend.seed(
            ServerProfile(id = "crash", name = "Hardcore Test", port = 25567, memoryLimitMb = 1024),
            RuntimeState(Status.Crashed, error = "Exited with code 101"),
            listOf("▶ Starting \"Hardcore Test\" on port 25567", "thread 'main' panicked at config.rs:42", "✖ Could not launch the engine: Permission denied", "■ Process exited with code 101"),
        )
        val auth = WebAuth(password = { "preview-1234" })
        val web = File("src/main/assets/web")
        val api = WebApi(backend, auth) { File(web, it).takeIf { f -> f.isFile }?.readBytes() }
        val server = HttpServer(18080, "127.0.0.1") { api.handle(it) }
        server.start()
        println("PREVIEW READY http://127.0.0.1:18080  password: preview-1234")
        Thread.sleep(20 * 60_000)
        server.stop()
    }
}
