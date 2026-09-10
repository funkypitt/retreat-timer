package com.freedomfighter.retreattimer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** A selectable bell recording, in both lengths a scheduled slot can ring:
 *  [rawRes] is three sequential strikes of the bowl, [singleRawRes] one strike of
 *  the same bowl. Every recording — both lengths, all five bowls — is
 *  loudness-matched to the others, so neither switching bowl nor switching
 *  between one and three strikes changes the volume in the room. */
data class BellSound(val key: String, val label: String, val rawRes: Int, val singleRawRes: Int)

object BellSounds {
    val ALL = listOf(
        BellSound("singing", "Singing bell", R.raw.bell_singing, R.raw.bell_singing_one),
        BellSound("eflat", "Tibetan bowl (E♭)", R.raw.bell_eflat, R.raw.bell_eflat_one),
        BellSound("gong", "Gong bowl", R.raw.bell_gong, R.raw.bell_gong_one),
        BellSound("satipanya", "Satipanya", R.raw.bell_satipanya, R.raw.bell_satipanya_one),
        BellSound("enpleineconscience", "enpleineconscience.ch", R.raw.bell_enpleineconscience, R.raw.bell_enpleineconscience_one),
    )

    /** Key of the teacher's own recording (see [customFile]). */
    const val CUSTOM = "custom"

    /** The teacher's chosen sound, falling back to the first. */
    fun selected(ctx: Context): BellSound {
        val key = BellStore.bellSoundKey(ctx)
        return ALL.firstOrNull { it.key == key } ?: ALL.first()
    }

    /** True when the teacher's own recording is chosen and present. */
    fun isCustom(ctx: Context): Boolean =
        BellStore.bellSoundKey(ctx) == CUSTOM && customFile(ctx) != null

    /** The imported recording — an mp3 or wav picked from the phone, copied into the
     *  app's files so it stays readable from an alarm after a reboot. It stands in
     *  for the single strike; a three-bell slot plays it three times over. */
    fun customFile(ctx: Context): File? =
        File(ctx.filesDir, "custom_bell").takeIf { it.exists() && it.length() > 0 }

    fun customName(ctx: Context): String = BellStore.customBellName(ctx)

    /** Copy the picked file in, check MediaPlayer can open it, and choose it. */
    fun importCustom(ctx: Context, uri: Uri): Boolean = runCatching {
        var name: String? = null
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        val tmp = File(ctx.filesDir, "custom_bell.tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        MediaMetadataRetriever().use { r ->
            r.setDataSource(tmp.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        }
        if (!tmp.renameTo(File(ctx.filesDir, "custom_bell"))) error("rename")
        BellStore.setCustomBellName(ctx, (name ?: uri.lastPathSegment ?: "your sound").substringBeforeLast('.').ifBlank { "your sound" })
        BellStore.setBellSoundKey(ctx, CUSTOM)
        true
    }.getOrDefault(false)

    /** How many times the custom recording plays for a slot: three, or one. */
    fun customRepeats(single: Boolean): Int = if (single) 1 else 3

    /** Recording for the chosen bowl at the requested length — the bell sound is a
     *  single global choice, the strike count is per scheduled slot. */
    fun rawRes(ctx: Context, single: Boolean): Int =
        selected(ctx).let { if (single) it.singleRawRes else it.rawRes }
}
