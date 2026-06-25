package com.freedomfighter.retreattimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Calendar

/**
 * The reliability core. Every enabled bell is registered with
 * [AlarmManager.setAlarmClock] — the ONE alarm type Android guarantees will fire
 * at the exact time even in Doze / deep sleep / when the screen has been locked
 * for hours. It is the same mechanism the stock Clock app uses, so the OS treats
 * it as a user alarm and never defers it.
 */
object BellScheduler {

    const val EXTRA_BELL_ID = "bell_id"
    const val EXTRA_TALK_URI = "talk_uri"
    const val EXTRA_TALK_TITLE = "talk_title"
    private const val RING_ACTION = "com.freedomfighter.retreattimer.RING"

    /** Cancel everything we previously scheduled, then arm the next occurrence of
     *  each enabled bell. Safe to call as often as we like — it is the single
     *  source of truth and is invoked after every edit, every fire, and boot. */
    fun rescheduleAll(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val bells = BellStore.load(ctx)

        // Cancel any alarm we could ever have created. ids are monotonic from 1,
        // so walking 1..highWatermark covers deleted bells too.
        val highWatermark = BellStore.highWatermarkId(ctx).toInt()
        for (id in 1..highWatermark) {
            am.cancel(cancelPendingIntent(ctx, id.toLong()))
        }

        bells.filter { it.enabled }.forEach { bell ->
            val triggerAt = nextTriggerMillis(bell)
            val showPi = PendingIntent.getActivity(
                ctx, bell.id.toInt(),
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val firePi = firePendingIntent(ctx, bell)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showPi), firePi)
        }
    }

    /** Next wall-clock instant for this bell: today if still ahead, else tomorrow. */
    fun nextTriggerMillis(bell: BellTime): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, bell.hour)
            set(Calendar.MINUTE, bell.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (next.timeInMillis <= now.timeInMillis) {
            next.add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis
    }

    /** The soonest enabled bell, used to show "next bell in …" on the home screen. */
    fun nextUpcoming(ctx: Context): Pair<BellTime, Long>? =
        BellStore.load(ctx)
            .filter { it.enabled }
            .map { it to nextTriggerMillis(it) }
            .minByOrNull { it.second }

    private fun firePendingIntent(ctx: Context, bell: BellTime): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = RING_ACTION
            putExtra(EXTRA_BELL_ID, bell.id)
            bell.talkUri?.let { putExtra(EXTRA_TALK_URI, it) }
            bell.talkTitle?.let { putExtra(EXTRA_TALK_TITLE, it) }
            // Unique data per item so PendingIntents never collapse into one.
            data = Uri.parse("retreat://bell/${bell.id}")
        }
        return PendingIntent.getBroadcast(
            ctx, bell.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Matches an existing alarm PendingIntent for cancellation. PendingIntent
     *  equality ignores extras, so only action + data + request code must match. */
    private fun cancelPendingIntent(ctx: Context, id: Long): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = RING_ACTION
            data = Uri.parse("retreat://bell/$id")
        }
        return PendingIntent.getBroadcast(
            ctx, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
