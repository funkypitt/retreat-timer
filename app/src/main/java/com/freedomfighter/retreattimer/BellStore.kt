package com.freedomfighter.retreattimer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A scheduled playback at a wall-clock time of day. When [talkUri] is null it
 * rings the built-in bells; otherwise it plays the chosen dharma talk.
 *
 * [singleStrike] chooses this slot's bell length: three strikes (the default, and
 * what every bell was before 1.11) or one. It is per slot on purpose — the same
 * retreat often wants three bells to open and close a sitting but a single bell
 * for a smaller marker — and it is independent of which bowl is selected.
 */
data class BellTime(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val talkUri: String? = null,
    val talkTitle: String? = null,
    val singleStrike: Boolean = false,
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
    private const val KEY_BELL_GAIN = "bell_gain_pct" // 0–100 software trim, default 100
    private const val KEY_TALK_GAIN = "talk_gain_pct" // 0–100 software trim, default 100
    private const val KEY_KDRIVE_URL = "kdrive_url"
    private const val KEY_PODCAST_URL = "podcast_url"
    private const val KEY_BELL_SOUND = "bell_sound"
    private const val KEY_KEEP_SPEAKER_AWAKE = "keep_speaker_awake"

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
                    // Absent in schedules written before 1.11 — those are all three-bell.
                    singleStrike = o.optBoolean("single", false),
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
                if (b.singleStrike) put("single", true)
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

    /** Per-source loudness trim, 0–100%, default 100 (full). Overall loudness is
     *  the phone's / Bluetooth speaker's own volume; this only attenuates the
     *  bells *below* that, applied as a software gain inside the player so it works
     *  over Bluetooth where the alarm-stream volume has no effect. Talks trim
     *  separately — see [talkGain] — so the two can be matched for an unattended day. */
    fun bellGain(ctx: Context): Int = prefs(ctx).getInt(KEY_BELL_GAIN, 100)

    fun setBellGain(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_BELL_GAIN, value.coerceIn(0, 100)).apply()
    }

    /** Per-source loudness trim for dharma talks, 0–100%, default 100. Spoken
     *  recordings and bowl strikes are rarely at the same recorded level, so this
     *  is trimmed independently of the bells to keep an unattended day balanced. */
    fun talkGain(ctx: Context): Int = prefs(ctx).getInt(KEY_TALK_GAIN, 100)

    fun setTalkGain(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_TALK_GAIN, value.coerceIn(0, 100)).apply()
    }

    /** Key of the chosen bell sound (see [BellSounds]); defaults to the first. */
    fun bellSoundKey(ctx: Context): String = prefs(ctx).getString(KEY_BELL_SOUND, "") ?: ""

    fun setBellSoundKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString(KEY_BELL_SOUND, key).apply()
    }

    /** Whether to keep a Bluetooth speaker awake between bells with a faint
     *  continuous sound (see [KeepAliveService]). Off by default — it only helps
     *  when ringing through an external speaker that sleeps on silence. */
    fun keepSpeakerAwake(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_KEEP_SPEAKER_AWAKE, false)

    fun setKeepSpeakerAwake(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_KEEP_SPEAKER_AWAKE, value).apply()
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
