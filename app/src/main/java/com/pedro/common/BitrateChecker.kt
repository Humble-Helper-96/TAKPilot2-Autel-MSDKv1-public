package com.pedro.common

/**
 * TAKPILOT2 CHANGE: this was `BitrateChecker.java`, a Java interface with a default method.
 * kapt could not read it from a stub in this application ("cannot access BitrateChecker") and
 * the build stopped before compilation. It is the same interface in Kotlin, and nothing else
 * changes: [ConnectChecker] extends it and [BitrateManager] takes it.
 */
interface BitrateChecker {
  fun onNewBitrate(bitrate: Long) {}
}
