package com.absi.talkingclock.audio

import android.content.Context
import android.os.PowerManager

/** Keeps the CPU awake from the moment the alarm fires until the announcement has finished. */
object AnnounceWakeLock {
    private const val TIMEOUT_MS = 20_000L
    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        val wakeLock = lock ?: context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TalkingClock:announce")
            .apply { setReferenceCounted(false) }
            .also { lock = it }
        wakeLock.acquire(TIMEOUT_MS)
    }

    @Synchronized
    fun release() {
        lock?.takeIf { it.isHeld }?.release()
    }
}
