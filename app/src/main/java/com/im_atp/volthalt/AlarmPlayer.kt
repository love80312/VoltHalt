package com.im_atp.volthalt

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class AlarmPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null

    private var tts: TextToSpeech? = null
    private var ttsScope: CoroutineScope? = null
    @Volatile private var ttsActive = false
    @Volatile private var playbackGeneration = 0L

    // Saved before we change it so we can restore it when the alarm stops.
    private var savedAlarmStreamVolume = -1

    companion object {
        private const val TTS_UTTERANCE_ID = "volthalt_alarm"
        private const val TTS_REPEAT_GAP_MS = 3_000L
    }

    init {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * Starts ringtone playback without blocking the calling (main) thread while
     * the audio source is prepared. All player state changes and callbacks are
     * expected to run on the main thread.
     */
    fun play(ringtoneUriString: String?, enableVibration: Boolean, volumePercent: Int = 80) {
        // A non-null player also represents a player that is still preparing.
        if (mediaPlayer != null || ttsActive) return

        val generation = ++playbackGeneration
        val player = MediaPlayer()
        mediaPlayer = player

        val uri = if (ringtoneUriString.isNullOrEmpty()) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        } else {
            Uri.parse(ringtoneUriString)
        }

        try {
            applyAlarmStreamVolume(volumePercent)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.isLooping = true
            player.setOnPreparedListener { preparedPlayer ->
                // stop() or a newer playback session may have invalidated this
                // callback while the audio source was being prepared.
                if (generation != playbackGeneration || mediaPlayer !== preparedPlayer) {
                    return@setOnPreparedListener
                }

                try {
                    preparedPlayer.setVolume(1.0f, 1.0f)
                    preparedPlayer.start()
                    if (enableVibration) startVibration()
                } catch (exception: Exception) {
                    exception.printStackTrace()
                    finishMediaPlayerSession(generation, preparedPlayer)
                }
            }
            player.setOnErrorListener { failedPlayer, _, _ ->
                finishMediaPlayerSession(generation, failedPlayer)
                true
            }
            player.setDataSource(context, uri)
            player.prepareAsync()
        } catch (exception: Exception) {
            exception.printStackTrace()
            finishMediaPlayerSession(generation, player)
        }
    }

    /** Clears and releases a failed player only if it still owns this session. */
    private fun finishMediaPlayerSession(generation: Long, player: MediaPlayer) {
        if (generation != playbackGeneration || mediaPlayer !== player) return

        mediaPlayer = null
        releaseMediaPlayerSafely(player)
        vibrator?.cancel()
        restoreStreamVolume()
    }

    /** Releases a player safely, including one that failed during preparation. */
    private fun releaseMediaPlayerSafely(player: MediaPlayer) {
        try {
            if (player.isPlaying) player.stop()
        } catch (_: Exception) {
            // The player may not have reached a state in which stop() is valid.
        }

        try {
            player.release()
        } catch (_: Exception) {
            // A late callback may refer to a player already released by stop().
        }
    }

    // Plays a TTS message on repeat with a short gap between each utterance.
    // The loop runs until stop() is called. AlarmPlayer playback is driven from
    // the main thread; the repeat coroutine uses the same dispatcher so stop()
    // cannot race a queued TTS speak() call.
    fun playTts(text: String, enableVibration: Boolean, volumePercent: Int = 80) {
        if (ttsActive || mediaPlayer != null) return
        val generation = ++playbackGeneration
        ttsActive = true

        try {
            applyAlarmStreamVolume(volumePercent)
            if (enableVibration) startVibration()

            tts = TextToSpeech(context) { status ->
                // A delayed callback from an earlier TTS instance must not start
                // speech after stop() or after a newer playback session begins.
                if (generation != playbackGeneration || !ttsActive) return@TextToSpeech

                if (status != TextToSpeech.SUCCESS) {
                    finishTtsSession(generation, tts)
                    return@TextToSpeech
                }

                val engine = tts ?: run {
                    finishTtsSession(generation, null)
                    return@TextToSpeech
                }

                try {
                    val languageResult = engine.setLanguage(Locale.getDefault())
                    if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                        languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        engine.setLanguage(Locale.ENGLISH)
                    }

                    // Route through the alarm stream so it plays in silent/DND mode.
                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )

                    // After each utterance finishes, wait the gap then speak again.
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            if (!ttsActive || generation != playbackGeneration) return
                            ttsScope?.launch {
                                delay(TTS_REPEAT_GAP_MS)
                                if (ttsActive && generation == playbackGeneration) {
                                    val currentEngine = tts ?: return@launch
                                    val result = currentEngine.speak(
                                        text,
                                        TextToSpeech.QUEUE_FLUSH,
                                        buildTtsBundle(),
                                        TTS_UTTERANCE_ID
                                    )
                                    if (result == TextToSpeech.ERROR) {
                                        finishTtsSession(generation, currentEngine)
                                    }
                                }
                            }
                        }

                        @Deprecated("Deprecated in API 21")
                        override fun onError(utteranceId: String?) {
                            finishTtsSession(generation, engine)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            finishTtsSession(generation, engine)
                        }
                    })

                    ttsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                    val result = engine.speak(
                        text,
                        TextToSpeech.QUEUE_FLUSH,
                        buildTtsBundle(),
                        TTS_UTTERANCE_ID
                    )
                    if (result == TextToSpeech.ERROR) {
                        finishTtsSession(generation, engine)
                    }
                } catch (exception: Exception) {
                    exception.printStackTrace()
                    finishTtsSession(generation, engine)
                }
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
            finishTtsSession(generation, tts)
        }
    }

    /** Clears a failed TTS session without allowing stale callbacks to affect a newer one. */
    private fun finishTtsSession(generation: Long, expectedEngine: TextToSpeech?) {
        if (generation != playbackGeneration || !ttsActive) return
        if (expectedEngine != null && tts !== expectedEngine) return

        ttsActive = false
        ttsScope?.cancel()
        ttsScope = null

        val engine = tts
        tts = null
        try {
            engine?.stop()
        } catch (_: Exception) {
            // Ignore errors while shutting down a failed TTS session.
        }
        try {
            engine?.shutdown()
        } catch (_: Exception) {
            // Ignore errors while shutting down a failed TTS session.
        }

        vibrator?.cancel()
        restoreStreamVolume()
    }

    fun stop() {
        // Invalidate pending MediaPlayer preparation and TTS callbacks first.
        playbackGeneration++
        ttsActive = false

        // Detach the player before releasing it so any late callback is stale.
        mediaPlayer?.let { player ->
            mediaPlayer = null
            releaseMediaPlayerSafely(player)
        }

        // Cancel the repeat loop before stopping TTS, so onDone() cannot re-queue speech.
        ttsScope?.cancel()
        ttsScope = null
        tts?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.shutdown()
            } catch (_: Exception) {
            }
        }
        tts = null

        vibrator?.cancel()
        restoreStreamVolume()
    }

    private fun applyAlarmStreamVolume(volumePercent: Int) {
        val clamped = volumePercent.coerceIn(0, 100)
        audioManager?.let { am ->
            try {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                savedAlarmStreamVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                val target = ((clamped / 100f) * max).toInt().coerceIn(0, max)
                am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            } catch (exception: SecurityException) {
                // A device may reject a stream-volume change. Keep playback available
                // at the current system volume rather than aborting the alarm.
                exception.printStackTrace()
                savedAlarmStreamVolume = -1
            }
        }
    }

    private fun restoreStreamVolume() {
        if (savedAlarmStreamVolume >= 0) {
            try {
                audioManager?.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    savedAlarmStreamVolume,
                    0
                )
            } catch (exception: SecurityException) {
                // The system may reject restoration; do not keep a stale saved value.
                exception.printStackTrace()
            } finally {
                savedAlarmStreamVolume = -1
            }
        }
    }

    private fun startVibration() {
        val pattern = longArrayOf(0, 1000, 1000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    // Bundle required by TextToSpeech.speak() for utterance callbacks to fire.
    private fun buildTtsBundle() = android.os.Bundle().apply {
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
    }
}
