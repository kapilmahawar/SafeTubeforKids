package tv.safetubeforkids.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A full session store used to make every later login fail silently: `createSession` returned null,
 * the auth route turned that into `success: true` with an empty token, and the dashboard was locked
 * out until somebody cleared sessions from the TV. Sessions last 90 days, so a family reaches the
 * cap through ordinary use.
 */
class SessionEvictionTest {

    @Test
    fun `a full session store still issues a token`() {
        var now = 1_000L
        val manager = SessionManager(clock = { now }, maxSessions = 3)

        val oldest = manager.createSession()
        assertNotNull(oldest)
        now += 1_000
        manager.createSession()
        now += 1_000
        manager.createSession()
        assertEquals(3, manager.getActiveSessionCount())

        now += 1_000
        val newest = manager.createSession()

        assertNotNull("logging in must work even at the cap", newest)
        assertTrue(manager.validateSession(newest!!))
        assertEquals("the cap still holds", 3, manager.getActiveSessionCount())
        assertFalse("the oldest session gives way", manager.validateSession(oldest!!))
    }

    @Test
    fun `rotating a session at the cap also succeeds`() {
        var now = 1_000L
        val manager = SessionManager(clock = { now }, maxSessions = 2)
        manager.createSession()
        now += 1_000
        val token = manager.createSession()!!
        now += 1_000
        manager.createSession()

        assertNotNull("refreshing must not fail because the store is full", manager.refreshSession(token))
    }

    @Test
    fun `expired sessions are still dropped rather than counted`() {
        var now = 1_000L
        val ninetyOneDays = 91L * 24 * 60 * 60 * 1000
        val manager = SessionManager(clock = { now }, maxSessions = 2)

        val old = manager.createSession()!!
        now += ninetyOneDays
        assertFalse(manager.validateSession(old))
        assertEquals(0, manager.getActiveSessionCount())
    }
}
