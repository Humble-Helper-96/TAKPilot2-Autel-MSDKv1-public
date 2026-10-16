package com.autel.sdksample.tak

/**
 * Pure decision core for connect-time avoidance enforcement. No SDK types, no Android
 * types — [AutelAvoidance] maps these switches onto the SDK's and runs the writes; THIS
 * decides what to do at each verify pass, so the policy has a JVM test.
 *
 * Written after a flight where the LANDING_PROTECT write timed out, its result was
 * ignored, and the aircraft flew the whole flight unprotected while the app showed the
 * Pre-Flight selection.
 */
object AvoidanceEnforcement {
    enum class Switch { SYSTEM, RTH, LANDING }

    sealed class Outcome {
        /** Every switch matches the aircraft: enforcement is proven, stop. */
        object Verified : Outcome()
        /** These switches still mismatch (or are unknown): write them again. */
        data class Retry(val switches: Set<Switch>) : Outcome()
        /** Attempts are spent and these still mismatch: warn the pilot, stop. */
        data class GiveUp(val switches: Set<Switch>) : Outcome()
    }

    /**
     * Decide one verify pass.
     *
     * @param desired what Pre-Flight saved.
     * @param actual the standing-listener cache. A null value means the feed went dark,
     *   which is NOT a match — unknown is its own state, so an unverifiable switch
     *   retries and, at the end, warns.
     * @param attempt 1-based pass number about to be decided.
     * @param maxAttempts write passes allowed before [Outcome.GiveUp].
     */
    /**
     * True when every switch the aircraft has reported matches what Pre-Flight wants.
     *
     * ⚠ **A SWITCH THE AIRCRAFT HAS NOT REPORTED IS NOT A MISMATCH.** Null is "we have not been
     * told", and treating it as wrong would put an amber banner up on every connect for the
     * second or two before the standing listener populates. Unknown is its own state,
     * and the caller re-asks on the next push rather than guessing now.
     */
    fun matches(
        desired: Map<Switch, Boolean>,
        actual: Map<Switch, Boolean?>,
    ): Boolean = desired.all { (sw, want) -> actual[sw]?.let { it == want } ?: true }

    fun decide(
        desired: Map<Switch, Boolean>,
        actual: Map<Switch, Boolean?>,
        attempt: Int,
        maxAttempts: Int,
    ): Outcome {
        val mismatched = desired.filter { (k, want) -> actual[k] != want }.keys
        return when {
            mismatched.isEmpty() -> Outcome.Verified
            attempt > maxAttempts -> Outcome.GiveUp(mismatched)
            else -> Outcome.Retry(mismatched)
        }
    }
}
