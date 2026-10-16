package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the flight-record line for each Emergency Broadcast event. This is what
 * someone reads after the fact, so the words and the shape must not drift.
 */
class EmergencyBroadcastPolicyFormatTest {

    private val expiresMs = 1_760_000_000_000L // 2025-10-09T08:53:20Z

    @Test
    fun startAndRenewCarryTheExpiry() {
        assertEquals("emergency-broadcast started (expires 2025-10-09T08:53:20Z)",
            EmergencyBroadcastPolicy.eventText("started", expiresMs))
        assertEquals("emergency-broadcast renewed (expires 2025-10-09T08:53:20Z)",
            EmergencyBroadcastPolicy.eventText("renewed", expiresMs))
    }

    @Test
    fun theThreeEndsCarryNoExpiry() {
        assertEquals("emergency-broadcast cancelled", EmergencyBroadcastPolicy.eventText("cancelled", 0L))
        assertEquals("emergency-broadcast expired", EmergencyBroadcastPolicy.eventText("expired", 0L))
        assertEquals("emergency-broadcast reset-on-reconnect",
            EmergencyBroadcastPolicy.eventText("reset-on-reconnect", 0L))
    }
}
