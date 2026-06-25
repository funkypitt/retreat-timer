package com.freedomfighter.retreattimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Fired by AlarmManager at the exact scheduled instant. Its only jobs are to (1)
 * hand off to the foreground [BellService] which actually rings, and (2) re-arm
 * the whole schedule so the same bell rings again tomorrow.
 *
 * Starting a foreground service from an exact-alarm broadcast is explicitly
 * allowed by the OS even from the background / while idle, so this is reliable.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(BellScheduler.EXTRA_BELL_ID, -1L)

        val serviceIntent = Intent(context, BellService::class.java).apply {
            putExtra(BellScheduler.EXTRA_BELL_ID, id)
            intent.getStringExtra(BellScheduler.EXTRA_TALK_URI)?.let {
                putExtra(BellScheduler.EXTRA_TALK_URI, it)
            }
            intent.getStringExtra(BellScheduler.EXTRA_TALK_TITLE)?.let {
                putExtra(BellScheduler.EXTRA_TALK_TITLE, it)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // Re-arm the next occurrence of every bell (this one is now in the past,
        // so it rolls to tomorrow). Doing a full reschedule keeps it idempotent.
        BellScheduler.rescheduleAll(context)
    }
}
