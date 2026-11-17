package app.dumpkinplus.web

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

sealed interface LoginResult {
    data class Ok(val token: String) : LoginResult
    data object Wrong : LoginResult
    data class Locked(val secondsLeft: Long) : LoginResult
}

/**
 * Password login with random session tokens.
 *
 * - The password is compared in constant time (via SHA-256 digests).
 * - After [MAX_FAILURES] wrong passwords from one address that address is locked out, for a time that doubles
 *   each time it happens again (1 min, 2 min, 4 min ... up to 15 min).
 * - Sessions last [SESSION_MS] and live in memory only, so restarting the app signs everyone out.
 */
class WebAuth(
    private val password: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onSessionsChanged: () -> Unit = {},
) {
    private class Failures(var count: Int = 0, var lockedUntil: Long = 0, var lockouts: Int = 0, var lastAt: Long = 0)

    private val sessions = ConcurrentHashMap<String, Long>() // token -> expiry
    private val failures = ConcurrentHashMap<String, Failures>()
    private val random = SecureRandom()

    val sessionCount: Int get() = purgeExpired().let { sessions.size }

    @Synchronized
    fun login(ip: String, attempt: String): LoginResult {
        val now = clock()
        val f = failures.getOrPut(ip) { Failures() }
        if (f.lockedUntil > now) return LoginResult.Locked((f.lockedUntil - now + 999) / 1000)

        if (!constantTimeEquals(attempt, password())) {
            f.count++
            f.lastAt = now
            if (f.count >= MAX_FAILURES) {
                val minutes = minOf(1L shl f.lockouts, MAX_LOCK_MINUTES)
                f.lockedUntil = now + minutes * 60_000
                f.lockouts++
                f.count = 0
                return LoginResult.Locked(minutes * 60)
            }
            pruneFailures(now)
            return LoginResult.Wrong
        }

        failures.remove(ip)
        purgeExpired()
        if (sessions.size >= MAX_SESSIONS) sessions.entries.minByOrNull { it.value }?.let { sessions.remove(it.key) }
        val token = newToken()
        sessions[token] = now + SESSION_MS
        onSessionsChanged()
        return LoginResult.Ok(token)
    }

    fun isValid(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val expiry = sessions[token] ?: return false
        if (expiry <= clock()) {
            sessions.remove(token)
            onSessionsChanged()
            return false
        }
        return true
    }

    fun logout(token: String?) {
        if (token != null && sessions.remove(token) != null) onSessionsChanged()
    }

    /** Signs every browser out, e.g. after the password changed. */
    fun revokeAll() {
        sessions.clear()
        onSessionsChanged()
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun purgeExpired() {
        val now = clock()
        // Collection.removeIf() is Android 7+, so go through an iterator.
        val it = sessions.entries.iterator()
        while (it.hasNext()) if (it.next().value <= now) it.remove()
    }

    private fun pruneFailures(now: Long) {
        if (failures.size < 256) return
        val it = failures.entries.iterator()
        while (it.hasNext()) {
            val f = it.next().value
            if (f.lockedUntil < now && now - f.lastAt > 10 * 60_000) it.remove()
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        return MessageDigest.isEqual(md.digest(a.toByteArray()), md.digest(b.toByteArray()))
    }

    companion object {
        const val MAX_FAILURES = 5
        const val MAX_LOCK_MINUTES = 15L
        const val MAX_SESSIONS = 50
        const val SESSION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
