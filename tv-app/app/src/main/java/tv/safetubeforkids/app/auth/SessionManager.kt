package tv.safetubeforkids.app.auth

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class SessionManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSessions: Int = 20,
    private val ttlMs: Long = 90L * 24 * 60 * 60 * 1000, // 90 days
    private val persistence: SessionPersistence? = null,
) {
    private val sessions = ConcurrentHashMap<String, Long>() // token -> createdAt
    private val random = SecureRandom()

    init {
        persistence?.load()?.let { loaded ->
            sessions.putAll(loaded)
            pruneExpired()
        }
    }

    fun createSession(): String? {
        pruneExpired()
        evictOldestIfFull()

        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        sessions[token] = clock()
        persistence?.save(sessions)
        return token
    }

    /**
     * The cap bounds what a device could accumulate, but it must never be the reason a parent cannot
     * log in. Sessions live for 90 days, so ordinary use reaches the cap - and returning null there
     * made the auth route answer "success" with an empty token, locking the dashboard out silently
     * and permanently. The oldest session gives way instead, which is what a parent expects: the
     * phone they signed in from months ago yields to the one in their hand.
     */
    private fun evictOldestIfFull() {
        if (sessions.size < maxSessions) return
        sessions.entries.minByOrNull { it.value }?.let { sessions.remove(it.key) }
    }

    fun validateSession(token: String): Boolean {
        val createdAt = sessions[token] ?: return false
        if (clock() - createdAt > ttlMs) {
            sessions.remove(token)
            return false
        }
        return true
    }

    fun refreshSession(token: String): String? {
        if (!validateSession(token)) return null
        sessions.remove(token)

        pruneExpired()
        evictOldestIfFull()

        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val newToken = bytes.joinToString("") { "%02x".format(it) }
        sessions[newToken] = clock()
        persistence?.save(sessions)
        return newToken
    }

    fun invalidateAll() {
        sessions.clear()
        persistence?.save(sessions)
    }

    fun getActiveSessionCount(): Int {
        pruneExpired()
        return sessions.size
    }

    private fun pruneExpired() {
        val now = clock()
        sessions.entries.removeIf { now - it.value > ttlMs }
    }
}
