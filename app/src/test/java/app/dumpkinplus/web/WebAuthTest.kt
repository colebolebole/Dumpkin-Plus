package app.dumpkinplus.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAuthTest {
    private var now = 1_000_000L
    private var password = "correct-horse"
    private var changes = 0
    private val auth = WebAuth(password = { password }, clock = { now }, onSessionsChanged = { changes++ })

    private fun ok(r: LoginResult) = (r as LoginResult.Ok).token

    @Test fun tokens_are_long_random_and_unique() {
        val a = ok(auth.login("1.1.1.1", password)); val b = ok(auth.login("1.1.1.1", password))
        assertEquals(64, a.length)
        assertNotEquals(a, b)
        assertTrue(auth.isValid(a) && auth.isValid(b))
        assertEquals(2, auth.sessionCount)
    }

    @Test fun wrong_passwords_never_create_sessions() {
        assertEquals(LoginResult.Wrong, auth.login("1.1.1.1", "wrong"))
        assertEquals(LoginResult.Wrong, auth.login("1.1.1.1", ""))
        assertEquals(0, auth.sessionCount)
        assertFalse(auth.isValid(null)); assertFalse(auth.isValid(""))
    }

    @Test fun lockout_after_five_failures_then_doubles_each_time() {
        repeat(4) { assertEquals(LoginResult.Wrong, auth.login("9.9.9.9", "x")) }
        assertEquals(60L, (auth.login("9.9.9.9", "x") as LoginResult.Locked).secondsLeft)
        // still locked, even for the right password
        now += 30_000
        assertEquals(30L, (auth.login("9.9.9.9", password) as LoginResult.Locked).secondsLeft)
        // after it expires five more failures lock for twice as long
        now += 31_000
        repeat(4) { assertEquals(LoginResult.Wrong, auth.login("9.9.9.9", "x")) }
        assertEquals(120L, (auth.login("9.9.9.9", "x") as LoginResult.Locked).secondsLeft)
    }

    @Test fun lockout_is_capped_at_fifteen_minutes() {
        var last = 0L
        repeat(8) {
            repeat(5) { last = (auth.login("9.9.9.9", "x") as? LoginResult.Locked)?.secondsLeft ?: last }
            now += 16 * 60_000
        }
        assertEquals(15 * 60L, last)
    }

    @Test fun lockouts_are_per_address_and_cleared_by_success() {
        repeat(5) { auth.login("9.9.9.9", "x") }
        assertTrue(auth.login("9.9.9.9", password) is LoginResult.Locked)
        ok(auth.login("8.8.8.8", password)) // someone else is unaffected
        repeat(3) { auth.login("7.7.7.7", "x") }
        ok(auth.login("7.7.7.7", password))
        repeat(4) { assertEquals(LoginResult.Wrong, auth.login("7.7.7.7", "x")) } // counter was reset by the success
    }

    @Test fun sessions_expire_after_a_week() {
        val t = ok(auth.login("1.1.1.1", password))
        now += WebAuth.SESSION_MS - 1
        assertTrue(auth.isValid(t))
        now += 2
        assertFalse(auth.isValid(t))
        assertEquals(0, auth.sessionCount)
    }

    @Test fun logout_and_revoke_all_end_sessions_and_report_changes() {
        val a = ok(auth.login("1.1.1.1", password)); val b = ok(auth.login("1.1.1.1", password))
        val before = changes
        auth.logout(a)
        assertFalse(auth.isValid(a)); assertTrue(auth.isValid(b))
        auth.revokeAll()
        assertFalse(auth.isValid(b))
        assertTrue(changes > before)
    }

    @Test fun a_changed_password_applies_immediately() {
        val t = ok(auth.login("1.1.1.1", password))
        password = "new-password"
        auth.revokeAll()
        assertFalse(auth.isValid(t))
        assertEquals(LoginResult.Wrong, auth.login("2.2.2.2", "correct-horse"))
        ok(auth.login("2.2.2.2", "new-password"))
    }

    @Test fun the_session_table_is_bounded() {
        repeat(WebAuth.MAX_SESSIONS + 10) { now += 1; ok(auth.login("1.1.1.1", password)) }
        assertEquals(WebAuth.MAX_SESSIONS, auth.sessionCount)
    }
}
