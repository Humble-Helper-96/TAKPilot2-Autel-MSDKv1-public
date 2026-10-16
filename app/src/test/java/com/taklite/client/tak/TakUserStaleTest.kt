package com.taklite.client.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A placed marker never reports stale; a live client or a track does.
 *
 * The case that found it: a marker shared through TAK Aware arrives with `stale` ten minutes
 * after `time`. The sweep keeps it (it is persistent), but the map drew it grey once the ten
 * minutes passed. The colour and the sweep must read the sender's window the same way.
 */
class TakUserStaleTest {

    private fun user(staleTime: Long) =
        TakUser("uid", "Target Res", 45.187, -100.845, 0.0, null, null, staleTime)

    @Test
    fun aPersistentMarkerPastItsStaleTimeIsNotStale() {
        val u = user(System.currentTimeMillis() - 60_000)
        u.isPersistent = true
        assertFalse(u.isStale)
    }

    @Test
    fun aLiveClientPastItsStaleTimeIsStale() {
        val u = user(System.currentTimeMillis() - 60_000)
        u.isLiveClient = true
        assertTrue(u.isStale)
    }

    @Test
    fun aTrackPastItsStaleTimeIsStale() {
        val u = user(System.currentTimeMillis() - 60_000)
        assertTrue(u.isStale)
    }

    @Test
    fun aFreshItemIsNotStaleEitherWay() {
        assertFalse(user(System.currentTimeMillis() + 60_000).isStale)
        val p = user(System.currentTimeMillis() + 60_000)
        p.isPersistent = true
        assertFalse(p.isStale)
    }
}
