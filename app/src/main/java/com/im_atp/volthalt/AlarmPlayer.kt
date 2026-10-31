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

    fun play(ringtoneUriString: String?, enableVibration: Boolean, volumePercent: Int = 80) {
        if (mediaPlayer?.isPlaying == true) return

        val uri = if (ringtoneUriString.isNullOrEmpty()) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        } else {
            Uri.parse(ringtoneUriString)
        }

        applyAlarmStreamVolume(volumePercent)

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                setVolume(1.0f, 1.0f)
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            restoreStreamVolume()
        }

        if (enableVibration) startVibration()
    }

    // Plays a TTS message on repeat with a short gap between each utterance.
    // The loop runs until stop() is called. AlarmPlayer playback is driven from
    // the main thread; the repeat coroutine uses the same dispatcher so stop()
    // cannot race a queued TTS speak() call.
    fun playTts(text: String, enableVibration: Boolean, volumePercent: Int = 80) {
        if (ttsActive) return
        val generation = ++playbackGeneration
        ttsActive = true

        applyAlarmStreamVolume(volumePercent)
        if (enableVibration) startVibration()

        tts = TextToSpeech(context) { status ->
            // A delayed callback from an earlier TTS instance must not start
            // speech after stop() or after a newer playback session begins.
            if (generation != playbackGeneration || !ttsActive) return@TextToSpeech

            if (status != TextToSpeech.SUCCESS) {
                tts?.shutdown()
                tts = null
                restoreStreamVolume()
                return@TextToSpeech
            }

            val engine = tts ?: return@TextToSpeech

            val result = engine.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
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
                            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, buildTtsBundle(), TTS_UTTERANCE_ID)
                        }
                    }
                }

                @Deprecated("Deprecated in API 21")
                override fun onError(utteranceId: String?) {}
            })

            ttsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, buildTtsBundle(), TTS_UTTERANCE_ID)
        }
    }

    fun stop() {
        // Invalidate pending TTS initialization and repeat callbacks first.
        playbackGeneration++
        ttsActive = false

        // Stop ringtone player.
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: IllegalStateException) {
                // MediaPlayer can be in an invalid state if the source had an error.
            }
            it.release()
        }
        mediaPlayer = null

        // Cancel the repeat loop before stopping TTS, so onDone() cannot re-queue speech.
        ttsScope?.cancel()
        ttsScope = null
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null

        vibrator?.cancel()
        restoreStreamVolume()
    }

    private fun applyAlarmStreamVolume(volumePercent: Int) {
        val clamped = volumePercent.coerceIn(0, 100)
        audioManager?.let { am ->
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            savedAlarmStreamVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val target = ((clamped / 100f) * max).toInt().coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        }
    }

    private fun restoreStreamVolume() {
        if (savedAlarmStreamVolume >= 0) {
            audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmStreamVolume, 0)
            savedAlarmStreamVolume = -1
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
