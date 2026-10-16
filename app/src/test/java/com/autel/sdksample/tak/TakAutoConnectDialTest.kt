package com.autel.sdksample.tak

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins [TakAutoConnect]'s routes to cert B: [TakAutoConnect.dialVideoChannel], the dial the
 * reconnect makes, and the redial after a return to ENHANCED (an early return "while a
 * reconnect is in flight" must turn a test red).
 *
 * Both go through [ElevatedChannel], whose live seam records the calls. ⚠ The reconnect's CALL
 * of dialVideoChannel runs on its socket thread, which a unit test cannot reach: deleting that
 * call leaves these tests green. Only a hardware test covers it.
 */
class TakAutoConnectDialTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var seams: LiveSeams

    @Before fun install() { seams = LiveSeams(); TakAutoConnect.connecting = false }
    @After fun restore() { seams.restore(); TakAutoConnect.connecting = false }

    /** An enrolled controller: Standard host, both Elevated certificate files on disk. */
    private fun enrolled(mode: AppMode): FakePrefs = FakePrefs(mode)
        .put("host", "tak.example.com").put("cot_port", 8089)
        .put("truststore_path", tmp.newFile().path)
        .put("clientcert_path", tmp.newFile().path)
        .put(TakAutoConnect.KEY_CHB_ENABLED, true)
        .put("chb_truststore_path", tmp.newFile().path)
        .put("chb_clientcert_path", tmp.newFile().path)

    // ---- The reconnect's dial ----

    @Test
    fun `dialVideoChannel dials cert B in ENHANCED`() {
        TakAutoConnect.dialVideoChannel(enrolled(AppMode.ENHANCED), "tak.example.com", 8089)
        assertEquals(listOf("connect"), seams.live.calls)
    }

    /** ⚠ The badge tap in flight: BASIC must never bring the split back with the stream up. */
    @Test
    fun `dialVideoChannel never dials cert B in BASIC`() {
        TakAutoConnect.dialVideoChannel(enrolled(AppMode.BASIC), "tak.example.com", 8089)
        assertEquals(emptyList<String>(), seams.live.calls)
    }

    @Test
    fun `dialVideoChannel does not dial when the certificate files are gone`() {
        val p = enrolled(AppMode.ENHANCED).put("chb_clientcert_path", tmp.root.path + "/missing")
        TakAutoConnect.dialVideoChannel(p, "tak.example.com", 8089)
        assertEquals(emptyList<String>(), seams.live.calls)
    }

    // ---- The redial after a return to ENHANCED ----

    /** ⚠ A reconnect already past its own cert-B step while the mode was BASIC
     *  finishes with no cert B. So the redial queues the dial even while one is in flight. */
    @Test
    fun `the redial dials cert B even while a reconnect is in flight`() {
        TakAutoConnect.connecting = true
        var reconnects = 0
        TakAutoConnect.redialAfterEnhanced(enrolled(AppMode.ENHANCED), isConnected = { false }) {
            reconnects++
        }
        assertEquals(listOf("connect"), seams.live.calls)
        assertEquals("no second reconnect over the one in flight", 0, reconnects)
    }

    @Test
    fun `the redial brings cert A up when it is down and nothing is in flight`() {
        var reconnects = 0
        TakAutoConnect.redialAfterEnhanced(enrolled(AppMode.ENHANCED), isConnected = { false }) {
            reconnects++
        }
        assertEquals(1, reconnects)
    }

    @Test
    fun `the redial leaves a connected cert A alone`() {
        var reconnects = 0
        TakAutoConnect.redialAfterEnhanced(enrolled(AppMode.ENHANCED), isConnected = { true }) {
            reconnects++
        }
        assertEquals(listOf("connect"), seams.live.calls)
        assertEquals(0, reconnects)
    }

    /** A Log Out made in BASIC keeps the host; the return must not reconnect. */
    @Test
    fun `the redial does not reconnect a controller that logged out`() {
        var reconnects = 0
        TakAutoConnect.redialAfterEnhanced(
            enrolled(AppMode.ENHANCED).put("logged_out", true), isConnected = { false }) {
            reconnects++
        }
        assertEquals(0, reconnects)
    }

    @Test
    fun `the redial does not reconnect with no saved certificates`() {
        var reconnects = 0
        TakAutoConnect.redialAfterEnhanced(
            enrolled(AppMode.ENHANCED).put("truststore_path", ""), isConnected = { false }) {
            reconnects++
        }
        assertEquals(0, reconnects)
    }
}
