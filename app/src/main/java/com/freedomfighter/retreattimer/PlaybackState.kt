package com.freedomfighter.retreattimer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Process-wide snapshot of what [BellService] is currently playing. The service
 * (same process) writes it on the main thread; Compose observes it to draw the
 * in-app Now Playing bar. Null [title] means nothing is playing.
 */
object PlaybackState {
    var title by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableStateOf(0)
    var durationMs by mutableStateOf(0)

    val remainingMs: Int get() = (durationMs - positionMs).coerceAtLeast(0)

    fun clear() {
        title = null
        isPlaying = false
        positionMs = 0
        durationMs = 0
    }
}

/** Format milliseconds as M:SS, or H:MM:SS once past an hour. */
fun formatClock(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
