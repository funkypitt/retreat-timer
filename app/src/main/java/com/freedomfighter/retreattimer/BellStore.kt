package com.freedomfighter.retreattimer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A single scheduled ringing of the three bells, at a wall-clock time of day. */
data class BellTime(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
) {
    /** Minutes since midnight — used for sorting. */
    val minuteOfDay: Int get() = hour * 60 + minute

    fun label(): String = "%02d:%02d".format(hour, minute)
}

/**
 * Plain-SharedPreferences persistence. Deliberately dependency-free and tiny so
 * there is nothing that can fail between "teacher set the times" and "bells ring".
 */
object BellStore {
    private const val PREFS = "retreat_timer"
    private const val KEY_BELLS = "bells"
    private const val KEY_NEXT_ID = "next_id"
    private const val KEY_ALARM_VOLUME = "alarm_volume" // -1 = leave system alarm volume untouched

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<BellTime> {
        val raw = prefs(ctx).getString(KEY_BELLS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BellTime(
                    id = o.getLong("id"),
                    hour = o.getInt("hour"),
                    minute = o.getInt("minute"),
                    enabled = o.optBoolean("enabled", true),
                )
            }.sortedBy { it.minuteOfDay }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, bells: List<BellTime>) {
        val arr = JSONArray()
        bells.sortedBy { it.minuteOfDay }.forEach { b ->
            arr.put(JSONObject().apply {
                put("id", b.id)
                put("hour", b.hour)
                put("minute", b.minute)
                put("enabled", b.enabled)
            })
        }
        prefs(ctx).edit().putString(KEY_BELLS, arr.toString()).apply()
    }

    /** Monotonic id generator so every bell keeps a stable AlarmManager request code. */
    fun nextId(ctx: Context): Long {
        val p = prefs(ctx)
        val id = p.getLong(KEY_NEXT_ID, 1L)
        p.edit().putLong(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    /** Highest id ever issued, without consuming a new one. Lets the scheduler
     *  cancel alarms for bells that have since been deleted. */
    fun highWatermarkId(ctx: Context): Long = prefs(ctx).getLong(KEY_NEXT_ID, 1L)

    /** Desired STREAM_ALARM volume, or -1 to leave whatever the system has. */
    fun alarmVolume(ctx: Context): Int = prefs(ctx).getInt(KEY_ALARM_VOLUME, -1)

    fun setAlarmVolume(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_ALARM_VOLUME, value).apply()
    }
}
