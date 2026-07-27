package com.freedomfighter.retreattimer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Downloads dharma talks in the background so a transfer survives the screen
 * going black. The old path ran the download inside the dialog's coroutine scope
 * with no wake lock; when the screen turned off the CPU idled (and on battery,
 * Doze cut the network outright), the socket read timed out, and the partial file
 * was thrown away.
 *
 * As a foreground service of type dataSync it is exempt from Doze's network
 * restrictions and won't be killed; a PARTIAL_WAKE_LOCK keeps the CPU running for
 * the whole transfer; and the queue lives in the service, not the dialog, so
 * closing the dialog (or the app) no longer aborts anything. Downloads run one at
 * a time and land in the library as each completes, mirrored to the UI through
 * [DownloadState].
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = ConcurrentLinkedQueue<Req>()
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var total = 0
    @Volatile private var done = 0
    @Volatile private var current: String = ""

    private data class Req(val key: String, val url: String, val filename: String, val title: String)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val key = it.getStringExtra(EXTRA_KEY)
            val url = it.getStringExtra(EXTRA_URL)
            val filename = it.getStringExtra(EXTRA_FILENAME)
            val title = it.getStringExtra(EXTRA_TITLE)
            if (key != null && url != null && filename != null && title != null) {
                queue.add(Req(key, url, filename, title))
                total++
                scope.launch(Dispatchers.Main) {
                    DownloadState.inFlight = DownloadState.inFlight + key
                    DownloadState.errors = DownloadState.errors - key
                }
            }
        }
        // startForeground must happen promptly on every start, before any work.
        startDownloadForeground()
        ensureWorker()
        // In-memory queue: a null-intent relaunch would have nothing to do.
        return START_NOT_STICKY
    }

    @Synchronized
    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch { runQueue() }
    }

    private suspend fun runQueue() {
        acquireWakeLock()
        try {
            while (true) {
                val req = queue.poll() ?: break
                current = req.title
                pushNotification()
                val result = runCatching {
                    val dest = File(BellStore.talksDir(this@DownloadService), req.filename)
                    Http.download(req.url, dest)
                    dest
                }
                done++
                withContext(Dispatchers.Main) { record(req, result) }
            }
        } finally {
            releaseWakeLock()
            finishIfIdle()
        }
    }

    /** Register the finished file (or its error) and reflect it to the UI. Runs on
     *  the main thread so the read-modify-write of the talk list stays consistent
     *  with the UI's own edits. */
    private fun record(req: Req, result: Result<File>) {
        DownloadState.inFlight = DownloadState.inFlight - req.key
        result.onSuccess { dest ->
            val talk = DharmaTalk(BellStore.nextId(this), Uri.fromFile(dest).toString(), req.title)
            BellStore.saveTalks(this, BellStore.loadTalks(this) + talk)
            DownloadState.justAdded = DownloadState.justAdded + req.title
            DownloadState.libraryVersion++
        }.onFailure {
            DownloadState.errors = DownloadState.errors + (req.key to (it.message ?: "Download failed"))
        }
    }

    /** Stop once the queue is truly empty; if an item arrived during teardown,
     *  pick the worker back up instead of stranding it. */
    @Synchronized
    private fun finishIfIdle() {
        if (queue.isEmpty()) {
            total = 0
            done = 0
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            worker = scope.launch { runQueue() }
        }
    }

    private fun startDownloadForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RetreatTimer:download").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // safety cap; released as soon as the queue drains
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun pushNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_download),
                    NotificationManager.IMPORTANCE_LOW, // silent: a retreat must stay quiet
                ).apply { description = getString(R.string.channel_download_desc) },
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = current.ifBlank { getString(R.string.app_name) }
        val text = if (total > 1) "${getString(R.string.download_downloading)} (${(done + 1).coerceAtMost(total)}/$total)"
        else getString(R.string.download_downloading)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true) // indeterminate: the HTTP helper streams without a byte count
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "retreat_download"
        private const val NOTIF_ID = 9
        private const val EXTRA_KEY = "key"
        private const val EXTRA_URL = "url"
        private const val EXTRA_FILENAME = "filename"
        private const val EXTRA_TITLE = "title"

        /** Queue a download. [key] is the caller's stable id for the item (kDrive
         *  file id, podcast episode URL) used to drive the UI via [DownloadState];
         *  [filename] is the destination name inside [BellStore.talksDir]. */
        fun enqueue(ctx: Context, key: String, url: String, filename: String, title: String) {
            val i = Intent(ctx, DownloadService::class.java).apply {
                putExtra(EXTRA_KEY, key)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FILENAME, filename)
                putExtra(EXTRA_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }
}
