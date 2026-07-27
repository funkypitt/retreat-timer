package com.freedomfighter.retreattimer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Process-wide snapshot of what [DownloadService] is doing, observed by the
 * Library dialogs so a download's spinner / ✓ / error survives closing and
 * reopening the dialog — the download now outlives the dialog that started it.
 *
 * Keys are the stable per-item identifiers the dialogs already have: a kDrive
 * file id, or a podcast episode URL.
 */
object DownloadState {
    /** Items currently queued or downloading. */
    var inFlight by mutableStateOf<Set<String>>(emptySet())

    /** Titles that finished this session, so the dialog shows ✓ even though the
     *  service, not the dialog, did the work. */
    var justAdded by mutableStateOf<Set<String>>(emptySet())

    /** Key → last error message, cleared when that key is re-queued or succeeds. */
    var errors by mutableStateOf<Map<String, String>>(emptyMap())

    /** Bumped on every library change so the Library tab reloads from storage. */
    var libraryVersion by mutableStateOf(0)
}
