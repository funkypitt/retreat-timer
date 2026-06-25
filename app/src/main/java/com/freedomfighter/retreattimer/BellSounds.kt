package com.freedomfighter.retreattimer

import android.content.Context

/** A selectable bell recording. Each is three sequential strikes of one bowl,
 *  loudness-matched to the others so switching between them keeps the same volume. */
data class BellSound(val key: String, val label: String, val rawRes: Int)

object BellSounds {
    val ALL = listOf(
        BellSound("singing", "Singing bell", R.raw.bell_singing),
        BellSound("eflat", "Tibetan bowl (E♭)", R.raw.bell_eflat),
        BellSound("gong", "Gong bowl", R.raw.bell_gong),
    )

    /** The teacher's chosen sound, falling back to the first. */
    fun selected(ctx: Context): BellSound {
        val key = BellStore.bellSoundKey(ctx)
        return ALL.firstOrNull { it.key == key } ?: ALL.first()
    }
}
