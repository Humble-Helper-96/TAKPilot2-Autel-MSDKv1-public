package com.autel.sdksample.tak

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins [ElevatedChannel] — every live change to the Elevated connection, on one thread.
 *
 * Each test here pins a behaviour the unit suite could not
 * see before this object existed: the dial gate and its stray-split heal, the post-dial
 * re-read, the start-then-stop the shared core cannot survive, and BASIC's teardown
 * order. The runner is inline and the clock is fake, so every interleaving is
 * deterministic.
 */
class ElevatedChannelTest {

    private lateinit var seams: LiveSeams

    @Before fun install() { seams = LiveSeams() }
    @After fun restore() { seams.restore() }

    private fun enhancedOn() = FakePrefs(AppMode.ENHANCED).put(TakAutoConnect.KEY_CHB_ENABLED, true)

    private fun dial(p: FakePrefs, force: Boolean = false, certs: Boolean = true) =
        ElevatedChannel.dialNow(p, "tak.example.com", 8089, "/ts", "/cc", force) { certs }

    // ---- The gate ----

    @Test
    fun `ENHANCED with the account on dials`() {
        dial(enhancedOn())
        assertEquals(listOf("connect"), seams.live.calls)
    }

    /** ⚠ THE GATE. A BASIC badge tap must not put the split back with the stream up. */
    @Test
    fun `BASIC never dials, whatever the switch says`() {
        dial(FakePrefs(AppMode.BASIC).put(TakAutoConnect.KEY_CHB_ENABLED, true))
        assertEquals(emptyList<String>(), seams.live.calls)
    }

    /** And a BASIC dial attempt REPAIRS a stray split rather than only declining. */
    @Test
    fun `BASIC heals a stray split`() {
        seams.live.configured = true
        dial(FakePrefs(AppMode.BASIC))
        assertEquals(listOf("clear"), seams.live.calls)
    }

    @Test
    fun `the switch off, a logout or missing certificates each stop the dial`() {
        dial(FakePrefs(AppMode.ENHANCED).put(TakAutoConnect.KEY_CHB_ENABLED, false))
        dial(enhancedOn().put("chb_logged_out", true))
        dial(enhancedOn(), certs = false)
        assertEquals(emptyList<String>(), seams.live.calls)
    }

    // ---- Idempotence ----

    /** A second automatic dial over a configured channel adds nothing but a start/stop bounce. */
    @Test
    fun `an automatic dial over a configured channel is skipped`() {
        seams.live.configured = true
        dial(enhancedOn())
        assertEquals(emptyList<String>(), seams.live.calls)
    }

    /** A fresh enrollment's certificates must be dialled even over a configured channel. */
    @Test
    fun `a forced dial re-dials a configured channel`() {
        seams.live.configured = true
        dial(enhancedOn(), force = true)
        assertEquals(listOf("connect"), seams.live.calls)
    }

    // ---- The post-dial re-read ----

    /**
     * ⚠ THE RACE. The transition writes BASIC from the main thread while this dial is in
     * flight. The second read must see it and take the client down — and only after it settled.
     */
    @Test
    fun `a change to BASIC during the dial takes the new client down`() {
        val p = enhancedOn()
        seams.live.onConnect = { p.edit().putString(AppMode.KEY_MODE, AppMode.BASIC.name).apply() }
        dial(p)
        assertEquals(listOf("connect", "clear"), seams.live.calls)
    }

    // ---- Never stop a client that has not started ----

    /**
     * ⚠ The shared core's TakClient.run() resets a stop that arrived before it began, leaving an
     * orphaned, connected client nothing can stop. So the clear above WAITED out the settle
     * window instead of stopping the client the instant it was made.
     */
    @Test
    fun `a clear right after a dial waits for the client to settle`() {
        val p = enhancedOn()
        seams.live.onConnect = { p.edit().putString(AppMode.KEY_MODE, AppMode.BASIC.name).apply() }
        dial(p)
        assertEquals(listOf(ElevatedChannel.SETTLE_MS), seams.slept)
    }

    @Test
    fun `a clear long after the last dial does not wait`() {
        dial(enhancedOn())
        seams.now += ElevatedChannel.SETTLE_MS * 10
        ElevatedChannel.clearNow()
        assertEquals(emptyList<Long>(), seams.slept)
        assertEquals(listOf("connect", "clear"), seams.live.calls)
    }

    @Test
    fun `a forced re-dial right after a dial waits too`() {
        dial(enhancedOn())
        dial(enhancedOn(), force = true)
        assertEquals(listOf(ElevatedChannel.SETTLE_MS), seams.slept)
    }

    /** Each path that stops a client waits out the settle window: the plain clear, BASIC's
     *  teardown and the BASIC heal inside a dial. */
    @Test
    fun `a clear, the Basic teardown and the Basic heal each wait after a fresh dial`() {
        dial(enhancedOn())
        ElevatedChannel.clearNow()
        dial(enhancedOn())
        ElevatedChannel.teardownForBasicNow()
        dial(enhancedOn())
        dial(FakePrefs(AppMode.BASIC))
        assertEquals(List(3) { ElevatedChannel.SETTLE_MS }, seams.slept)
        assertEquals(listOf("connect", "clear", "connect", "clear", "connect", "clear"), seams.live.calls)
    }

    /** ⚠ A clock that steps BACK must not stretch the wait. Every
     *  teardown, clear and dial queues behind this one wait. */
    @Test
    fun `the settle wait is capped when the clock steps back`() {
        dial(enhancedOn())
        seams.now -= 3_600_000L
        ElevatedChannel.clearNow()
        assertEquals(listOf(ElevatedChannel.SETTLE_MS), seams.slept)
    }

    /** The application closing must close the Elevated connection, and know it did. */
    @Test
    fun `the closing clear removes the channel and reports it finished`() {
        dial(enhancedOn())
        assertTrue(ElevatedChannel.clearAndWait(1_000))
        assertEquals(listOf("connect", "clear"), seams.live.calls)
    }

    @Test
    fun `the closing clear gives up rather than hang when the channel thread is stuck`() {
        ElevatedChannel.runner = { /* never runs */ }
        seams.live.configured = true
        assertEquals(false, ElevatedChannel.clearAndWait(50))
    }

    // ---- BASIC's teardown ----

    /** ⚠ The broadcast BEFORE the split: it is an override ON the split, and its only stop
     *  control is the notice BASIC removes. clearVideoChannel does not end it. */
    @Test
    fun `the teardown cancels a running broadcast and then clears the split, in that order`() {
        seams.live.emergency = true
        seams.live.configured = true
        ElevatedChannel.teardownForBasicNow()
        assertEquals(listOf("cancelEB", "clear"), seams.live.calls)
        // The flight record says WHY, so the core's plain "cancelled" line that follows cannot
        // be read as the pilot's own stop.
        assertEquals(listOf("emergency-broadcast ended by the change to Basic mode"), seams.recorded)
    }

    @Test
    fun `the teardown does nothing on a controller with neither`() {
        ElevatedChannel.teardownForBasicNow()
        assertEquals(emptyList<String>(), seams.live.calls)
    }

    /** One queue: a dial and a clear posted in order run in order. */
    @Test
    fun `posted work runs in the order it was posted`() {
        val order = mutableListOf<String>()
        val queue = ArrayDeque<Runnable>()
        ElevatedChannel.runner = { queue.addLast(it) }
        seams.live.configured = true
        ElevatedChannel.clear()
        ElevatedChannel.dial(enhancedOn(), "h", 1, "/ts", "/cc", force = false) { true }
        while (queue.isNotEmpty()) queue.removeFirst().run()
        order += seams.live.calls
        assertTrue("the clear queued first must run first", order == listOf("clear", "connect"))
    }
}
