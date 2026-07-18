package com.freedomfighter.retreattimer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A scheduled playback at a wall-clock time of day. When [talkUri] is null it
 * rings the built-in three bells; otherwise it plays the chosen dharma talk.
 */
data class BellTime(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val talkUri: String? = null,
    val talkTitle: String? = null,
) {
    /** Minutes since midnight — used for sorting. */
    val minuteOfDay: Int get() = hour * 60 + minute

    val isTalk: Boolean get() = talkUri != null

    fun time(): String = "%02d:%02d".format(hour, minute)
}

/** An imported audio file (a dharma talk) the teacher can play or schedule. */
data class DharmaTalk(
    val id: Long,
    val uri: String,
    val title: String,
)

/**
 * Plain-SharedPreferences persistence. Deliberately dependency-free and tiny so
 * there is nothing that can fail between "teacher set it up" and "it plays".
 */
object BellStore {
    private const val PREFS = "retreat_timer"
    private const val KEY_BELLS = "bells"
    private const val KEY_TALKS = "talks"
    private const val KEY_NEXT_ID = "next_id"
    private const val KEY_ALARM_VOLUME = "alarm_volume" // -1 = leave system alarm volume untouched
    private const val KEY_TALK_VOLUME = "talk_volume"   // -1 = leave system alarm volume untouched
    private const val KEY_KDRIVE_URL = "kdrive_url"
    private const val KEY_PODCAST_URL = "podcast_url"
    private const val KEY_BELL_SOUND = "bell_sound"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Scheduled items (bells and scheduled talks) ----

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
                    talkUri = o.optString("talkUri", "").ifEmpty { null },
                    talkTitle = o.optString("talkTitle", "").ifEmpty { null },
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
                b.talkUri?.let { put("talkUri", it) }
                b.talkTitle?.let { put("talkTitle", it) }
            })
        }
        prefs(ctx).edit().putString(KEY_BELLS, arr.toString()).apply()
    }

    /** The matching scheduled item, used by the service to know what to play. */
    fun find(ctx: Context, id: Long): BellTime? = load(ctx).firstOrNull { it.id == id }

    // ---- Dharma talk library ----

    fun loadTalks(ctx: Context): List<DharmaTalk> {
        val raw = prefs(ctx).getString(KEY_TALKS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DharmaTalk(o.getLong("id"), o.getString("uri"), o.getString("title"))
            }
        }.getOrDefault(emptyList())
    }

    fun saveTalks(ctx: Context, talks: List<DharmaTalk>) {
        val arr = JSONArray()
        talks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("uri", t.uri)
                put("title", t.title)
            })
        }
        prefs(ctx).edit().putString(KEY_TALKS, arr.toString()).apply()
    }

    // ---- Shared helpers ----

    /** Monotonic id generator so every entry keeps a stable AlarmManager request code. */
    fun nextId(ctx: Context): Long {
        val p = prefs(ctx)
        val id = p.getLong(KEY_NEXT_ID, 1L)
        p.edit().putLong(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    /** Highest id ever issued, without consuming a new one. Lets the scheduler
     *  cancel alarms for items that have since been deleted. */
    fun highWatermarkId(ctx: Context): Long = prefs(ctx).getLong(KEY_NEXT_ID, 1L)

    /** Desired STREAM_ALARM volume for the bells, or -1 to leave whatever the
     *  system has. Talks have their own level — see [talkVolume]. */
    fun alarmVolume(ctx: Context): Int = prefs(ctx).getInt(KEY_ALARM_VOLUME, -1)

    fun setAlarmVolume(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_ALARM_VOLUME, value).apply()
    }

    /** Desired STREAM_ALARM volume for dharma talks, or -1 to leave whatever the
     *  system has. Spoken recordings usually need to be markedly louder than a
     *  bowl strike to carry across a hall, so this is set independently of the
     *  bells. Until the teacher sets it, it follows the bell level — which is
     *  what talks used before the two were split, so upgrading changes nothing. */
    fun talkVolume(ctx: Context): Int = prefs(ctx).getInt(KEY_TALK_VOLUME, alarmVolume(ctx))

    fun setTalkVolume(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_TALK_VOLUME, value).apply()
    }

    /** Key of the chosen bell sound (see [BellSounds]); defaults to the first. */
    fun bellSoundKey(ctx: Context): String = prefs(ctx).getString(KEY_BELL_SOUND, "") ?: ""

    fun setBellSoundKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString(KEY_BELL_SOUND, key).apply()
    }

    /** Last kDrive public-share link the teacher used, pre-filled next time. */
    fun kdriveUrl(ctx: Context): String = prefs(ctx).getString(KEY_KDRIVE_URL, "") ?: ""

    fun setKdriveUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_KDRIVE_URL, url).apply()
    }

    /** Last podcast RSS feed URL the teacher used, pre-filled next time. */
    fun podcastUrl(ctx: Context): String = prefs(ctx).getString(KEY_PODCAST_URL, "") ?: ""

    fun setPodcastUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_PODCAST_URL, url).apply()
    }

    /** Private folder where talks downloaded from kDrive are stored. Files here
     *  are owned by the app, so scheduled playback never loses access to them. */
    fun talksDir(ctx: Context): File = File(ctx.filesDir, "talks").apply { mkdirs() }
}
