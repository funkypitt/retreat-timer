package com.freedomfighter.retreattimer

import android.content.Context

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

    /** The teacher's chosen sound, falling back to the first. */
    fun selected(ctx: Context): BellSound {
        val key = BellStore.bellSoundKey(ctx)
        return ALL.firstOrNull { it.key == key } ?: ALL.first()
    }

    /** Recording for the chosen bowl at the requested length — the bell sound is a
     *  single global choice, the strike count is per scheduled slot. */
    fun rawRes(ctx: Context, single: Boolean): Int =
        selected(ctx).let { if (single) it.singleRawRes else it.rawRes }
}
