package dev.stride.hud

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

/**
 * Speaks a coach line through the console's own speaker.
 *
 * The console has **no text-to-speech engine at all** — `tts_default_synth` is
 * null and there is not a single speech package installed — so Android's
 * `TextToSpeech` cannot be used here however much it looks like the obvious
 * answer. Home Assistant synthesises the line instead and hands over a URL to
 * the resulting mp3; this streams it.
 *
 * Two deliberate details:
 *
 * * **Plain HTTP, on the LAN.** The console's clock reads 2022, so TLS
 *   certificate validation would fail on anything served over HTTPS. The URL
 *   points at Home Assistant on the local network and never leaves it.
 * * **Playback happens here, not in the WebView.** The HUD's Chromium is a 2020
 *   build behind a `file://` origin; the page is for pixels, the platform media
 *   stack is for sound.
 */
class CoachVoice(private val context: Context) {

    companion object {
        const val TAG = FitProConnection.TAG
    }

    private var player: MediaPlayer? = null

    @Volatile private var speaking = false

    /**
     * Play a synthesised line.
     *
     * A line arriving while another is still being spoken is **dropped, not
     * queued**. Coaching that stacks up is worse than coaching that misses one:
     * by the time a backed-up line is heard it is describing a kilometre that is
     * already behind you. The rate limiting upstream should mean this never
     * happens; if it does, the log says so.
     */
    /**
     * @return true if playback started. False means something was already
     *         speaking and this line was dropped — which the caller may need to
     *         know, because a caller that latches "said it" on a dropped line
     *         never says it at all.
     */
    fun play(url: String): Boolean {
        if (speaking) {
            Log.i(TAG, "coach: still speaking, dropped $url")
            return false
        }
        speaking = true
        try {
            release()
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    // USAGE_ASSISTANT is API 26; older AudioAttributes.Builder rejects it
                    .setUsage(
                        if (android.os.Build.VERSION.SDK_INT >= 26) AudioAttributes.USAGE_ASSISTANT
                        else AudioAttributes.USAGE_MEDIA
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                duck(true)
                it.start()
                Log.i(TAG, "coach: speaking (${it.duration} ms)")
            }
            mp.setOnCompletionListener { done() }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "coach: playback failed what=$what extra=$extra url=$url")
                done()
                true
            }
            mp.prepareAsync()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "coach: could not play $url — ${e.message}")
            done()
            return false
        }
    }

    /** Cut it off mid-sentence. */
    fun stop() {
        if (!speaking) return
        Log.i(TAG, "coach: hushed")
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        done()
    }

    private fun done() {
        speaking = false
        duck(false)
        release()
    }

    /** Nothing else makes noise on this console today, but be a good citizen. */
    @Suppress("DEPRECATION")
    private fun duck(on: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (on) {
                am.requestAudioFocus(
                    null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            } else {
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
    }

    private fun release() {
        try {
            player?.reset()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    fun close() {
        speaking = false
        release()
    }
}
