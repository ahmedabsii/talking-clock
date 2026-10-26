package com.absi.talkingclock.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import com.absi.talkingclock.data.MusicMode
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface PlayResult {
    data object Played : PlayResult
    data class Missing(val path: String) : PlayResult
    data class Failed(val error: Throwable) : PlayResult
}

/** Plays the bundled clips. Call on the main thread so MediaPlayer callbacks have a looper. */
class ClipPlayer(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)

    // The alarm channel: it plays in silent and vibrate mode, has the volume VolumeController sets,
    // and is the channel Android 17 still lets an exact-alarm app use from the background.
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    suspend fun play(phrasePath: String, withChime: Boolean, gain: Float, boost: Boolean, musicMode: MusicMode): PlayResult {
        val focusGain = when (musicMode) {
            MusicMode.DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            MusicMode.PAUSE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }
        val focus = AudioFocusRequest.Builder(focusGain)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        // Like an alarm, the time is spoken even if another app won't give up audio focus.
        audio.requestAudioFocus(focus)
        return try {
            if (withChime) playClip(ClipLibrary.CHIME, gain, boost) // best effort; the phrase is what matters
            playClip(phrasePath, gain, boost)
        } finally {
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private suspend fun playClip(path: String, gain: Float, boost: Boolean): PlayResult {
        val fd = try {
            context.assets.openFd(path)
        } catch (e: IOException) {
            return PlayResult.Missing(path)
        }
        val player = MediaPlayer()
        var enhancer: LoudnessEnhancer? = null
        return try {
            fd.use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            player.setAudioAttributes(attributes)
            player.setVolume(gain, gain)
            player.prepare()
            if (boost) enhancer = createBoost(player.audioSessionId)
            withTimeout(CLIP_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    player.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                    player.setOnErrorListener { _, what, extra ->
                        if (cont.isActive) cont.resumeWithException(IOException("MediaPlayer error $what/$extra"))
                        true
                    }
                    player.start()
                }
            }
            PlayResult.Played
        } catch (e: TimeoutCancellationException) {
            PlayResult.Failed(e)
        } catch (e: IOException) {
            PlayResult.Failed(e)
        } catch (e: IllegalStateException) {
            PlayResult.Failed(e)
        } finally {
            enhancer?.release()
            player.release()
        }
    }

    private fun createBoost(sessionId: Int): LoudnessEnhancer? = try {
        LoudnessEnhancer(sessionId).apply {
            setTargetGain(BOOST_MILLIBELS)
            enabled = true
        }
    } catch (e: RuntimeException) {
        null // the effect isn't available on every device
    }

    private companion object {
        const val BOOST_MILLIBELS = 600 // +6 dB
        const val CLIP_TIMEOUT_MS = 10_000L
    }
}
