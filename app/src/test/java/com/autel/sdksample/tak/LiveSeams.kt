package com.autel.sdksample.tak

/**
 * The live seams of [ElevatedChannel] and [AppModeTransition], replaced for a test and put back
 * after it. Every test class that drives either object installs this in @Before and calls
 * [restore] in @After — the objects are process-wide, so a seam left in place would leak into
 * the next class.
 */
class LiveSeams {
    /** A TakManager stand-in that records the order of every live call. */
    class FakeLive : ElevatedChannel.Live {
        val calls = mutableListOf<String>()
        var configured = false
        var emergency = false
        /** Runs inside connectVideoChannel — lets a test change the world DURING a dial. */
        var onConnect: () -> Unit = {}
        override fun isVideoChannelConfigured() = configured
        override fun connectVideoChannel(host: String, port: Int, trustStore: String, clientCert: String) {
            calls += "connect"; configured = true; onConnect()
        }
        override fun clearVideoChannel() { calls += "clear"; configured = false }
        override fun isEmergencyBroadcastActive() = emergency
        override fun cancelEmergencyBroadcast() { calls += "cancelEB"; emergency = false }
    }

    val live = FakeLive()
    var now = 1_000_000L
    val slept = mutableListOf<Long>()
    var staleUrlCleared = 0
    val recorded = mutableListOf<String>()

    private val origLive = ElevatedChannel.live
    private val origRunner = ElevatedChannel.runner
    private val origClock = ElevatedChannel.clock
    private val origSleeper = ElevatedChannel.sleeper
    private val origRecord = ElevatedChannel.record
    private val origClearUrl = AppModeTransition.clearStaleVideoUrl

    init {
        ElevatedChannel.live = live
        ElevatedChannel.runner = { it.run() }     // inline: deterministic, no thread
        ElevatedChannel.clock = { now }
        ElevatedChannel.sleeper = { ms -> slept += ms; now += ms }
        ElevatedChannel.record = { recorded += it }
        AppModeTransition.clearStaleVideoUrl = { staleUrlCleared++ }
        // The object is process-wide: a dial time left by the previous test would make this
        // test's first clear wait (or not) for reasons outside the test.
        ElevatedChannel.lastDialAtMs = 0L
    }

    fun restore() {
        ElevatedChannel.live = origLive
        ElevatedChannel.runner = origRunner
        ElevatedChannel.clock = origClock
        ElevatedChannel.sleeper = origSleeper
        ElevatedChannel.record = origRecord
        AppModeTransition.clearStaleVideoUrl = origClearUrl
        ElevatedChannel.lastDialAtMs = 0L
    }
}
