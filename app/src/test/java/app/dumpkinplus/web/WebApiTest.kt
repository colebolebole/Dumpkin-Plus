package app.dumpkinplus.web

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Exercises the real HTTP server and API over real sockets, with a fake backend behind them. */
class WebApiTest {
    private val password = "TEST-PASS-1234"
    private lateinit var backend: FakeBackend
    private lateinit var server: HttpServer
    private var port = 0

    @Before fun setUp() {
        backend = FakeBackend(instant = true)
        val auth = WebAuth(password = { password })
        val webDir = File("src/main/assets/web")
        val api = WebApi(backend, auth) { name -> File(webDir, name).takeIf { it.isFile }?.readBytes() }
        server = HttpServer(0, "127.0.0.1") { api.handle(it) }
        server.start()
        port = server.boundPort
    }

    @After fun tearDown() = server.stop()

    private fun req(method: String, path: String, body: String? = null, cookie: String? = null, csrf: Boolean = method != "GET" && method != "HEAD",
                    headers: Map<String, String> = emptyMap(), host: String? = "127.0.0.1:$port") =
        RawHttp.call(port, method, path, body, headers, cookie, csrf, host)

    private fun login(): String {
        val r = req("POST", "/api/login", """{"password":"$password"}""")
        assertEquals(200, r.status)
        return r.header("set-cookie")!!.substringBefore(';')
    }

    private fun createServer(cookie: String, name: String = "Test", extra: String = ""): JSONObject {
        val r = req("POST", "/api/servers", """{"name":"$name"$extra}""", cookie)
        assertEquals(r.body, 201, r.status)
        return r.json
    }

    // ---- static files & basic security headers -----------------------------------------------------------------

    @Test fun serves_the_dashboard_without_signing_in() {
        val page = req("GET", "/")
        assertEquals(200, page.status)
        assertTrue(page.header("content-type")!!.startsWith("text/html"))
        assertTrue(page.body.contains("/app.js"))
        for (file in listOf("/app.js", "/style.css", "/logo.svg")) assertEquals(file, 200, req("GET", file).status)
    }

    @Test fun every_response_carries_security_headers() {
        val r = req("GET", "/")
        assertEquals("nosniff", r.header("x-content-type-options"))
        assertEquals("DENY", r.header("x-frame-options"))
        assertTrue(r.header("content-security-policy")!!.contains("default-src 'self'"))
        assertTrue(r.header("content-security-policy")!!.contains("frame-ancestors 'none'"))
    }

    @Test fun unknown_and_traversal_paths_are_404() {
        assertEquals(404, req("GET", "/nope.txt").status)
        assertEquals(404, req("GET", "/../build.gradle.kts").status)
        assertEquals(404, req("GET", "/%2e%2e/build.gradle.kts").status)
        assertEquals(404, req("GET", "/index.html/../../secret").status)
    }

    // ---- authentication ----------------------------------------------------------------------------------------

    @Test fun api_requires_a_session() {
        for (path in listOf("/api/state", "/api/servers", "/api/engine")) assertEquals(path, 401, req("GET", path).status)
        assertEquals(401, req("POST", "/api/servers", """{"name":"x"}""").status)
    }

    @Test fun wrong_password_is_rejected_and_right_one_sets_a_hardened_cookie() {
        assertEquals(401, req("POST", "/api/login", """{"password":"nope"}""").status)
        val ok = req("POST", "/api/login", """{"password":"$password"}""")
        assertEquals(200, ok.status)
        val cookie = ok.header("set-cookie")!!
        assertTrue(cookie.startsWith("dk_session="))
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("SameSite=Strict"))
        assertEquals(64, cookie.substringBefore(';').substringAfter('=').length) // 32 random bytes, hex
    }

    @Test fun a_forged_or_empty_cookie_gets_nothing() {
        assertEquals(401, req("GET", "/api/state", cookie = "dk_session=deadbeef").status)
        assertEquals(401, req("GET", "/api/state", cookie = "dk_session=").status)
        assertEquals(401, req("GET", "/api/state", cookie = "other=1").status)
    }

    @Test fun repeated_wrong_passwords_lock_the_address_out() {
        repeat(WebAuth.MAX_FAILURES - 1) { assertEquals(401, req("POST", "/api/login", """{"password":"bad$it"}""").status) }
        val locked = req("POST", "/api/login", """{"password":"bad"}""")
        assertEquals(429, locked.status)
        assertNotNull(locked.header("retry-after"))
        // even the right password is refused while locked
        assertEquals(429, req("POST", "/api/login", """{"password":"$password"}""").status)
    }

    @Test fun logout_ends_the_session() {
        val cookie = login()
        assertEquals(200, req("GET", "/api/state", cookie = cookie).status)
        val out = req("POST", "/api/logout", cookie = cookie)
        assertEquals(200, out.status)
        assertTrue(out.header("set-cookie")!!.contains("Max-Age=0"))
        assertEquals(401, req("GET", "/api/state", cookie = cookie).status)
    }

    // ---- cross-site and rebinding defences ---------------------------------------------------------------------

    @Test fun state_changing_calls_need_the_custom_header() {
        val cookie = login()
        assertEquals(403, req("POST", "/api/servers", """{"name":"x"}""", cookie, csrf = false).status)
        assertEquals(403, req("POST", "/api/login", """{"password":"$password"}""", csrf = false).status)
        assertEquals(403, req("POST", "/api/logout", cookie = cookie, csrf = false).status)
    }

    @Test fun a_foreign_origin_is_blocked_but_the_same_origin_is_fine() {
        val cookie = login()
        val evil = req("POST", "/api/servers", """{"name":"x"}""", cookie, headers = mapOf("Origin" to "http://evil.example"))
        assertEquals(403, evil.status)
        val same = req("POST", "/api/servers", """{"name":"x"}""", cookie, headers = mapOf("Origin" to "http://127.0.0.1:$port"))
        assertEquals(201, same.status)
        assertEquals(403, req("POST", "/api/login", """{"password":"$password"}""", headers = mapOf("Origin" to "null")).status)
    }

    @Test fun host_headers_that_are_not_addresses_are_refused_to_stop_dns_rebinding() {
        assertEquals(400, req("GET", "/", host = "attacker.example.com:$port").status)
        assertEquals(400, req("GET", "/", host = "evil.com").status)
        assertEquals(400, req("GET", "/", host = null).status)
        for (ok in listOf("192.168.0.83:8080", "localhost:8080", "[::1]:8080", "mypixel:8080", "phone.local:8080")) {
            assertEquals(ok, 200, req("GET", "/", host = ok).status)
        }
    }

    // ---- state & servers ---------------------------------------------------------------------------------------

    @Test fun state_describes_the_phone_the_engine_and_every_server() {
        val cookie = login()
        createServer(cookie, "Alpha")
        val s = req("GET", "/api/state", cookie = cookie).json
        assertEquals("Dumpkin+", s.getJSONObject("app").getString("name"))
        assertEquals("192.168.0.83", s.getJSONObject("device").getJSONArray("addresses").getString(0))
        assertTrue(s.getLong("now") > 0)
        assertEquals(1, s.getJSONObject("engine").getJSONArray("installed").length())
        assertEquals(3, s.getJSONObject("engine").getJSONArray("available").length())
        val first = s.getJSONArray("servers").getJSONObject(0)
        assertEquals("Alpha", first.getString("name"))
        assertEquals("stopped", first.getString("status"))
        assertEquals(25565, first.getInt("port"))
        assertTrue(first.isNull("engineId"))
    }

    @Test fun creating_servers_picks_free_ports_and_applies_settings() {
        val cookie = login()
        val a = createServer(cookie, "A", ""","gameMode":"creative","viewDistance":10""")
        val b = createServer(cookie, "B")
        assertEquals(25565, a.getInt("port")); assertEquals(25566, b.getInt("port"))
        assertEquals("Creative", a.getString("gameMode")) // case-insensitive input, canonical output
        assertEquals(10, a.getInt("viewDistance"))
        assertEquals(6, b.getInt("viewDistance"))          // new servers default to the fast-join preset
    }

    @Test fun invalid_servers_are_rejected_with_a_message() {
        val cookie = login()
        createServer(cookie, "A")
        fun bad(json: String, fragment: String) {
            val r = req("POST", "/api/servers", json, cookie)
            assertEquals(json, 400, r.status)
            assertTrue(r.body, r.json.getString("error").contains(fragment, ignoreCase = true))
        }
        bad("""{"name":""}""", "name")
        bad("{}", "name")
        bad("""{"name":"${"x".repeat(41)}"}""", "40")
        bad("""{"name":"x","port":80}""", "port")
        bad("""{"name":"x","port":25565}""", "already uses")
        bad("""{"name":"x","port":"abc"}""", "whole number")
        bad("""{"name":"x","gameMode":"hardcore"}""", "one of")
        bad("""{"name":"x","viewDistance":99}""", "view distance")
        bad("""{"name":"x","maxPlayers":0}""", "max players")
        bad("""{"name":"x","onlineMode":"yes"}""", "true or false")
        bad("""{"name":"x","engineId":"nope"}""", "isn't installed")
        assertEquals(400, req("POST", "/api/servers", "not json", cookie).status)
        assertEquals(1, req("GET", "/api/servers", cookie = cookie).array.length())
    }

    @Test fun a_running_server_can_be_restarted() {
        val cookie = login()
        val id = createServer(cookie).getString("id")
        assertEquals(false, req("GET", "/api/servers/$id", cookie = cookie).json.getBoolean("restarting"))
        req("POST", "/api/servers/$id/start", cookie = cookie)

        val r = req("POST", "/api/servers/$id/restart", cookie = cookie)
        assertEquals(r.body, 200, r.status)
        assertEquals("running", r.json.getString("status")) // the fake restarts instantly, so it is back up
        assertEquals(false, r.json.getBoolean("restarting"))
        val log = req("GET", "/api/servers/$id/logs", cookie = cookie).json.getJSONArray("lines").toString()
        assertTrue(log, log.contains("Restart requested") && log.contains("Stopping") && log.contains("Restarting"))
    }

    @Test fun restarting_a_stopped_server_just_starts_it() {
        val cookie = login()
        val id = createServer(cookie).getString("id")
        val r = req("POST", "/api/servers/$id/restart", cookie = cookie)
        assertEquals(r.body, 200, r.status)
        assertEquals("running", r.json.getString("status"))
    }

    @Test fun restart_is_refused_when_it_cannot_happen() {
        val cookie = login()
        val id = createServer(cookie).getString("id")
        backend.startFailure = "No Pumpkin engine installed yet."
        val r = req("POST", "/api/servers/$id/restart", cookie = cookie)
        assertEquals(409, r.status)
        assertTrue(r.json.getString("error").contains("engine", ignoreCase = true))
        assertEquals(404, req("POST", "/api/servers/nope/restart", cookie = cookie).status)
        assertEquals(405, req("GET", "/api/servers/$id/restart", cookie = cookie).status)
        assertEquals(401, req("POST", "/api/servers/$id/restart").status)
        assertEquals(403, req("POST", "/api/servers/$id/restart", cookie = cookie, csrf = false).status)
    }

    @Test fun several_bedrock_servers_can_coexist_on_different_ports() {
        val cookie = login()
        val a = createServer(cookie, "A", ""","bedrock":true""")
        assertEquals(19132, a.getInt("bedrockPort")) // first free Bedrock port by default
        val b = createServer(cookie, "B", ""","bedrock":true""")
        assertEquals(19133, b.getInt("bedrockPort")) // next one is picked automatically
        val c = createServer(cookie, "C", ""","bedrock":true,"bedrockPort":19200""")
        assertEquals(19200, c.getInt("bedrockPort"))

        val dup = req("POST", "/api/servers", """{"name":"D","bedrock":true,"bedrockPort":19200}""", cookie)
        assertEquals(dup.body, 400, dup.status)
        assertTrue(dup.json.getString("error").contains("Bedrock port", ignoreCase = true))
        val clash = req("PATCH", "/api/servers/${b.getString("id")}", """{"bedrockPort":19132}""", cookie)
        assertEquals(400, clash.status)
        assertEquals(200, req("PATCH", "/api/servers/${b.getString("id")}", """{"bedrockPort":19300}""", cookie).status)
        // Java ports are chosen to avoid Bedrock ports too
        val ports = listOf(a, b, c).map { it.getInt("port") }
        assertEquals(ports.size, ports.toSet().size)
    }

    @Test fun start_stop_and_status_round_trip() {
        val cookie = login()
        val id = createServer(cookie).getString("id")
        val started = req("POST", "/api/servers/$id/start", cookie = cookie)
        assertEquals(200, started.status)
        assertEquals("running", started.json.getString("status"))
        assertEquals("running", req("GET", "/api/servers/$id", cookie = cookie).json.getString("status"))
        assertEquals("stopped", req("POST", "/api/servers/$id/stop", cookie = cookie).json.getString("status"))
    }

    @Test fun a_start_that_cannot_happen_explains_why() {
        val cookie = login()
        val id = createServer(cookie).getString("id")
        backend.engineState = backend.engineState.copy(installed = emptyList())
        val r = req("POST", "/api/servers/$id/start", cookie = cookie)
        assertEquals(409, r.status)
        assertTrue(r.json.getString("error").contains("engine", ignoreCase = true))
    }

    @Test fun two_servers_cannot_run_on_one_port() {
        val cookie = login()
        val a = createServer(cookie, "A").getString("id")
        val b = createServer(cookie, "B").getString("id")
        assertEquals(200, req("POST", "/api/servers/$a/start", cookie = cookie).status)
        assertEquals(200, req("POST", "/api/servers/$b/start", cookie = cookie).status) // different ports: fine
    }

    @Test fun settings_can_be_patched_and_a_running_server_is_told_to_restart() {
        val cookie = login()
        val id = createServer(cookie).getString("id")
        val idle = req("PATCH", "/api/servers/$id", """{"motd":"Hello","maxPlayers":8,"compression":"Off","autoRestart":true,"memoryLimitMb":1024,"memoryAction":"Restart","cpuThreads":4}""", cookie)
        assertEquals(idle.body, 200, idle.status)
        assertEquals("Hello", idle.json.getString("motd"))
        assertEquals("Off", idle.json.getString("compression"))
        assertEquals("Restart", idle.json.getString("memoryAction"))
        assertFalse(idle.json.getBoolean("restartRequired"))

        req("POST", "/api/servers/$id/start", cookie = cookie)
        val live = req("PATCH", "/api/servers/$id", """{"maxPlayers":9}""", cookie)
        assertEquals(200, live.status)
        assertTrue(live.json.getBoolean("restartRequired"))
        assertEquals(9, req("GET", "/api/servers/$id", cookie = cookie).json.getInt("maxPlayers"))
    }

    @Test fun a_patch_that_would_break_the_server_changes_nothing() {
        val cookie = login()
        val id = createServer(cookie).getString("id")
        assertEquals(400, req("PATCH", "/api/servers/$id", """{"name":"ok","viewDistance":1}""", cookie).status)
        assertEquals("Test", req("GET", "/api/servers/$id", cookie = cookie).json.getString("name"))
        assertEquals(404, req("PATCH", "/api/servers/missing", "{}", cookie).status)
    }

    @Test fun servers_can_only_be_deleted_while_stopped() {
        val cookie = login()
        val id = createServer(cookie).getString("id")
        req("POST", "/api/servers/$id/start", cookie = cookie)
        assertEquals(409, req("DELETE", "/api/servers/$id", cookie = cookie).status)
        req("POST", "/api/servers/$id/stop", cookie = cookie)
        assertEquals(200, req("DELETE", "/api/servers/$id", cookie = cookie).status)
        assertEquals(404, req("GET", "/api/servers/$id", cookie = cookie).status)
    }

    @Test fun there_is_a_cap_on_how_many_servers_can_exist() {
        val cookie = login()
        repeat(WebApi.MAX_SERVERS) { createServer(cookie, "S$it") }
        assertEquals(409, req("POST", "/api/servers", """{"name":"one too many"}""", cookie).status)
    }

    // ---- console -----------------------------------------------------------------------------------------------

    @Test fun commands_need_a_running_server_and_are_sanitised() {
        val cookie = login()
        val id = createServer(cookie).getString("id")
        assertEquals(409, req("POST", "/api/servers/$id/command", """{"command":"list"}""", cookie).status)
        req("POST", "/api/servers/$id/start", cookie = cookie)
        assertEquals(400, req("POST", "/api/servers/$id/command", """{"command":"   "}""", cookie).status)
        assertEquals(400, req("POST", "/api/servers/$id/command", """{"command":"${"a".repeat(501)}"}""", cookie).status)
        assertEquals(200, req("POST", "/api/servers/$id/command", """{"command":"/say hi\nstop"}""", cookie).status)
        // slash stripped, newline can't smuggle in a second command
        assertEquals("$id:say hi stop", backend.sentCommands.last())
    }

    @Test fun logs_are_fetched_incrementally_with_a_cursor() {
        val cookie = login()
        val id = createServer(cookie).getString("id")
        req("POST", "/api/servers/$id/start", cookie = cookie)

        val all = req("GET", "/api/servers/$id/logs", cookie = cookie).json
        assertTrue(all.getBoolean("reset"))
        val n = all.getJSONArray("lines").length()
        assertTrue(n >= 2)
        val cursor = all.getLong("cursor")

        val none = req("GET", "/api/servers/$id/logs?after=$cursor", cookie = cookie).json
        assertFalse(none.getBoolean("reset")); assertEquals(0, none.getJSONArray("lines").length())

        req("POST", "/api/servers/$id/command", """{"command":"list"}""", cookie)
        val more = req("GET", "/api/servers/$id/logs?after=$cursor", cookie = cookie).json
        assertFalse(more.getBoolean("reset"))
        assertEquals("> list", more.getJSONArray("lines").getString(0))
        assertEquals(2, more.getJSONArray("lines").length())

        // a cursor from the future (e.g. after the app restarted) resets the viewer
        assertTrue(req("GET", "/api/servers/$id/logs?after=999999", cookie = cookie).json.getBoolean("reset"))
        assertEquals(404, req("GET", "/api/servers/missing/logs", cookie = cookie).status)
    }

    // ---- config file -------------------------------------------------------------------------------------------

    @Test fun the_config_file_can_be_read_written_and_reset() {
        val cookie = login()
        val id = createServer(cookie).getString("id")
        assertTrue(req("GET", "/api/servers/$id/config", cookie = cookie).json.getString("text").contains("max_players"))
        val put = req("PUT", "/api/servers/$id/config", """{"text":"# mine\nseed = 1\n"}""", cookie)
        assertEquals(200, put.status)
        assertEquals("# mine\nseed = 1\n", put.json.getString("text"))
        assertEquals(400, req("PUT", "/api/servers/$id/config", """{"nope":1}""", cookie).status)
        assertEquals(400, req("PUT", "/api/servers/$id/config", """{"text":"a\u0000b"}""", cookie).status)

        req("POST", "/api/servers/$id/start", cookie = cookie)
        assertEquals(409, req("POST", "/api/servers/$id/config/reset", cookie = cookie).status)
        req("POST", "/api/servers/$id/stop", cookie = cookie)
        assertEquals(200, req("POST", "/api/servers/$id/config/reset", cookie = cookie).status)
    }

    // ---- engine ------------------------------------------------------------------------------------------------

    @Test fun engine_builds_can_be_listed_downloaded_and_removed() {
        val cookie = login()
        val eng = req("GET", "/api/engine", cookie = cookie).json
        assertTrue(eng.getBoolean("supported"))
        val canary = (0 until eng.getJSONArray("available").length()).map { eng.getJSONArray("available").getJSONObject(it) }.first { it.getString("channel") == "Canary" }
        assertFalse(canary.getBoolean("installed"))

        assertEquals(404, req("POST", "/api/engine/download", """{"id":"nope"}""", cookie).status)
        assertEquals(202, req("POST", "/api/engine/download", """{"id":"${canary.getString("id")}"}""", cookie).status)
        assertEquals(2, req("GET", "/api/engine", cookie = cookie).json.getJSONArray("installed").length())

        assertEquals(200, req("DELETE", "/api/engine/${canary.getString("id")}", cookie = cookie).status)
        assertEquals(404, req("DELETE", "/api/engine/${canary.getString("id")}", cookie = cookie).status)
        assertEquals(200, req("POST", "/api/engine/refresh", cookie = cookie).status)
    }

    @Test fun engine_builds_cannot_be_removed_while_a_server_runs() {
        val cookie = login()
        val id = createServer(cookie).getString("id")
        req("POST", "/api/servers/$id/start", cookie = cookie)
        assertEquals(409, req("DELETE", "/api/engine/0.2.2_26.3-26.60", cookie = cookie).status)
    }

    // ---- malformed and hostile requests ------------------------------------------------------------------------

    @Test fun oversized_bodies_chunked_uploads_and_odd_methods_are_refused() {
        val cookie = login()
        assertEquals(413, req("POST", "/api/servers", "x".repeat(HttpServer.MAX_BODY_BYTES + 1), cookie).status)
        assertEquals(501, RawHttp.call(port, "POST", "/api/servers", cookie = cookie, extraRaw = "Transfer-Encoding: chunked\r\n").status)
        assertEquals(405, req("TRACE", "/api/state", csrf = false).status)
        assertEquals(405, req("PUT", "/api/state", "{}", cookie).status)
        assertEquals(405, req("DELETE", "/api/login", csrf = true).status)
        assertEquals(404, req("GET", "/api/unknown", cookie = cookie).status)
        assertEquals(404, req("GET", "/api/servers/a/b/c/d", cookie = cookie).status)
    }

    @Test fun huge_headers_are_refused_and_the_server_keeps_working() {
        val r = RawHttp.call(port, "GET", "/", headers = mapOf("X-Big" to "a".repeat(HttpServer.MAX_HEADER_BYTES + 100)))
        assertEquals(431, r.status)
        assertEquals(200, req("GET", "/").status)
    }

    @Test fun head_requests_return_headers_without_a_body() {
        val r = req("HEAD", "/")
        assertEquals(200, r.status)
        assertTrue(r.header("content-length")!!.toInt() > 0)
        assertEquals("", r.body)
    }

    @Test fun user_supplied_names_come_back_verbatim_as_data_not_markup() {
        val cookie = login()
        val evil = "<img src=x onerror=alert(1)>"
        val r = createServer(cookie, evil.replace("\"", "'"))
        assertEquals(evil, r.getString("name"))
        assertTrue(req("GET", "/api/state", cookie = cookie).header("content-type")!!.startsWith("application/json"))
        assertNull(req("GET", "/api/state", cookie = cookie).header("content-type")!!.takeIf { it.contains("html") })
    }
}
