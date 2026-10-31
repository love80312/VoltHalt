package com.im_atp.volthalt

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Owns the single audio/vibration playback session used by VoltHalt.
 *
 * Playback callbacks are delivered on the main thread. A failure callback is
 * invoked once for a failed session, but is never invoked by an intentional
 * stop(). Generation checks prevent late callbacks from an old session from
 * affecting a newer session.
 */
class AlarmPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null

    private var tts: TextToSpeech? = null
    private var ttsScope: CoroutineScope? = null
    @Volatile private var ttsActive = false
    @Volatile private var playbackGeneration = 0L

    private var mediaPlayerStartedCallback: (() -> Unit)? = null
    private var mediaPlayerFailureCallback: ((String) -> Unit)? = null
    private var ttsStartedCallback: (() -> Unit)? = null
    private var ttsFailureCallback: ((String) -> Unit)? = null
    private var ttsStartedReported = false

    // Saved before changing the system alarm-stream volume.
    private var savedAlarmStreamVolume = -1

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "AlarmPlayer"
        private const val TTS_UTTERANCE_ID = "volthalt_alarm"
        private const val TTS_REPEAT_GAP_MS = 3_000L
    }

    init {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * Starts ringtone playback asynchronously.
     *
     * [onPlaybackStarted] is called only after MediaPlayer.start() succeeds.
     * [onPlaybackError] is called once if setup, preparation or playback fails.
     */
    fun play(
        ringtoneUriString: String?,
        enableVibration: Boolean,
        volumePercent: Int = 80,
        onPlaybackStarted: (() -> Unit)? = null,
        onPlaybackError: ((String) -> Unit)? = null
    ) {
        if (mediaPlayer != null || ttsActive) {
            notifyFailure(onPlaybackError, "Another alarm playback session is already active.")
            return
        }

        val generation = ++playbackGeneration
        mediaPlayerStartedCallback = onPlaybackStarted
        mediaPlayerFailureCallback = onPlaybackError

        val player = try {
            MediaPlayer()
        } catch (exception: Exception) {
            finishMediaPlayerCreationFailure(generation, exception)
            return
        }
        mediaPlayer = player

        try {
            val uri: Uri = if (ringtoneUriString.isNullOrEmpty()) {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: throw IllegalStateException("No default alarm ringtone is available.")
            } else {
                Uri.parse(ringtoneUriString)
            }

            applyAlarmStreamVolume(volumePercent)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.isLooping = true

            player.setOnPreparedListener { preparedPlayer ->
                if (generation != playbackGeneration || mediaPlayer !== preparedPlayer) {
                    return@setOnPreparedListener
                }

                try {
                    preparedPlayer.setVolume(1.0f, 1.0f)
                    preparedPlayer.start()
                    if (enableVibration) startVibrationSafely()
                    invokeStartedCallback(mediaPlayerStartedCallback)
                } catch (exception: Exception) {
                    finishMediaPlayerSession(
                        generation,
                        preparedPlayer,
                        "Unable to start alarm audio: ${exception.message ?: exception.javaClass.simpleName}"
                    )
                }
            }

            player.setOnErrorListener { failedPlayer, what, extra ->
                finishMediaPlayerSession(
                    generation,
                    failedPlayer,
                    "MediaPlayer error (what=$what, extra=$extra)."
                )
                true
            }

            player.setDataSource(context, uri)
            player.prepareAsync()
        } catch (exception: Exception) {
            finishMediaPlayerSession(
                generation,
                player,
                "Unable to prepare alarm audio: ${exception.message ?: exception.javaClass.simpleName}"
            )
        }
    }

    private fun finishMediaPlayerCreationFailure(generation: Long, exception: Exception) {
        if (generation != playbackGeneration) return

        mediaPlayerStartedCallback = null
        val failureCallback = mediaPlayerFailureCallback
        mediaPlayerFailureCallback = null
        vibrator?.cancel()
        restoreStreamVolume()

        notifyFailure(
            failureCallback,
            "Unable to create MediaPlayer: ${exception.message ?: exception.javaClass.simpleName}"
        )
    }

    /** Releases a failed MediaPlayer session and reports its failure exactly once. */
    private fun finishMediaPlayerSession(
        generation: Long,
        player: MediaPlayer,
        errorMessage: String?
    ) {
        if (generation != playbackGeneration || mediaPlayer !== player) return

        mediaPlayer = null
        releaseMediaPlayerSafely(player)
        vibrator?.cancel()
        restoreStreamVolume()

        mediaPlayerStartedCallback = null
        val failureCallback = mediaPlayerFailureCallback
        mediaPlayerFailureCallback = null
        if (errorMessage != null) notifyFailure(failureCallback, errorMessage)
    }

    /** Releases a player safely, including one that failed during preparation. */
    private fun releaseMediaPlayerSafely(player: MediaPlayer) {
        try {
            if (player.isPlaying) player.stop()
        } catch (_: Exception) {
            // stop() is not valid before the player reaches the Started state.
        }

        try {
            player.release()
        } catch (_: Exception) {
            // Ignore a late callback referring to an already released player.
        }
    }

    /** Starts a TTS message repeatedly until stop() is called or playback fails. */
    fun playTts(
        text: String,
        enableVibration: Boolean,
        volumePercent: Int = 80,
        onPlaybackStarted: (() -> Unit)? = null,
        onPlaybackError: ((String) -> Unit)? = null
    ) {
        if (ttsActive || mediaPlayer != null) {
            notifyFailure(onPlaybackError, "Another alarm playback session is already active.")
            return
        }

        val generation = ++playbackGeneration
        ttsActive = true
        ttsStartedCallback = onPlaybackStarted
        ttsFailureCallback = onPlaybackError
        ttsStartedReported = false

        try {
            applyAlarmStreamVolume(volumePercent)
            if (enableVibration) startVibrationSafely()

            val engine = TextToSpeech(context) { status ->
                // Always post initialization handling so tts has been assigned first.
                mainHandler.post {
                    if (generation != playbackGeneration || !ttsActive) return@post

                    if (status != TextToSpeech.SUCCESS) {
                        finishTtsSession(
                            generation,
                            tts,
                            "TextToSpeech initialization failed (status=$status)."
                        )
                        return@post
                    }

                    val activeEngine = tts
                    if (activeEngine == null) {
                        finishTtsSession(generation, null, "TextToSpeech engine is unavailable.")
                        return@post
                    }

                    configureAndSpeakTts(generation, activeEngine, text)
                }
            }
            tts = engine
        } catch (exception: Exception) {
            finishTtsSession(
                generation,
                tts,
                "Unable to initialize TextToSpeech: ${exception.message ?: exception.javaClass.simpleName}"
            )
        }
    }

    private fun configureAndSpeakTts(
        generation: Long,
        engine: TextToSpeech,
        text: String
    ) {
        if (generation != playbackGeneration || !ttsActive || tts !== engine) return

        try {
            var languageResult = engine.setLanguage(Locale.getDefault())
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                languageResult = engine.setLanguage(Locale.ENGLISH)
            }
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                throw IllegalStateException("The default and English TTS languages are unavailable.")
            }

            val audioResult = engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            if (audioResult == TextToSpeech.ERROR) {
                throw IllegalStateException("Unable to configure TTS alarm audio attributes.")
            }

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId != TTS_UTTERANCE_ID) return
                    runOnMain {
                        if (generation != playbackGeneration || !ttsActive || tts !== engine) {
                            return@runOnMain
                        }
                        if (!ttsStartedReported) {
                            ttsStartedReported = true
                            invokeStartedCallback(ttsStartedCallback)
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != TTS_UTTERANCE_ID) return
                    runOnMain {
                        if (generation != playbackGeneration || !ttsActive || tts !== engine) {
                            return@runOnMain
                        }
                        ttsScope?.launch {
                            delay(TTS_REPEAT_GAP_MS)
                            if (generation != playbackGeneration || !ttsActive || tts !== engine) {
                                return@launch
                            }

                            try {
                                val result = engine.speak(
                                    text,
                                    TextToSpeech.QUEUE_FLUSH,
                                    buildTtsBundle(),
                                    TTS_UTTERANCE_ID
                                )
                                if (result == TextToSpeech.ERROR) {
                                    finishTtsSession(
                                        generation,
                                        engine,
                                        "TextToSpeech rejected a repeat utterance."
                                    )
                                }
                            } catch (exception: Exception) {
                                finishTtsSession(
                                    generation,
                                    engine,
                                    "Unable to repeat TTS alarm: ${exception.message ?: exception.javaClass.simpleName}"
                                )
                            }
                        }
                    }
                }

                @Deprecated("Deprecated in API 21")
                override fun onError(utteranceId: String?) {
                    handleTtsPlaybackError(generation, engine, "TextToSpeech reported an utterance error.")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    handleTtsPlaybackError(
                        generation,
                        engine,
                        "TextToSpeech playback error (code=$errorCode)."
                    )
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
                finishTtsSession(generation, engine, "TextToSpeech rejected the initial utterance.")
            }
        } catch (exception: Exception) {
            finishTtsSession(
                generation,
                engine,
                "Unable to start TTS alarm: ${exception.message ?: exception.javaClass.simpleName}"
            )
        }
    }

    private fun handleTtsPlaybackError(
        generation: Long,
        engine: TextToSpeech,
        message: String
    ) {
        runOnMain {
            finishTtsSession(generation, engine, message)
        }
    }

    /**
     * Clears a failed TTS session, restores shared audio state, and reports once.
     * A stale engine callback cannot stop or report against a newer session.
     */
    private fun finishTtsSession(
        generation: Long,
        expectedEngine: TextToSpeech?,
        errorMessage: String?
    ) {
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

        ttsStartedCallback = null
        val failureCallback = ttsFailureCallback
        ttsFailureCallback = null
        ttsStartedReported = false
        if (errorMessage != null) notifyFailure(failureCallback, errorMessage)
    }

    /** Stops playback intentionally. This path never reports a playback failure. */
    fun stop() {
        playbackGeneration++
        ttsActive = false

        mediaPlayerStartedCallback = null
        mediaPlayerFailureCallback = null
        mediaPlayer?.let { player ->
            mediaPlayer = null
            releaseMediaPlayerSafely(player)
        }

        ttsStartedCallback = null
        ttsFailureCallback = null
        ttsStartedReported = false
        ttsScope?.cancel()
        ttsScope = null
        tts?.let { engine ->
            try {
                engine.stop()
            } catch (_: Exception) {
            }
            try {
                engine.shutdown()
            } catch (_: Exception) {
            }
        }
        tts = null

        vibrator?.cancel()
        restoreStreamVolume()
    }

    private fun applyAlarmStreamVolume(volumePercent: Int) {
        val clamped = volumePercent.coerceIn(0, 100)
        val manager = audioManager ?: return
        try {
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            savedAlarmStreamVolume = manager.getStreamVolume(AudioManager.STREAM_ALARM)
            val target = ((clamped / 100f) * max).toInt().coerceIn(0, max)
            manager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (exception: Exception) {
            // Volume-control failure must not prevent alarm audio from playing.
            Log.w(TAG, "Unable to set alarm-stream volume; keeping the current volume.", exception)
            savedAlarmStreamVolume = -1
        }
    }

    private fun restoreStreamVolume() {
        if (savedAlarmStreamVolume < 0) return

        try {
            audioManager?.setStreamVolume(
                AudioManager.STREAM_ALARM,
                savedAlarmStreamVolume,
                0
            )
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to restore the previous alarm-stream volume.", exception)
        } finally {
            savedAlarmStreamVolume = -1
        }
    }

    private fun startVibrationSafely() {
        try {
            startVibration()
        } catch (exception: Exception) {
            // Vibration failure must not be treated as an audio playback failure.
            Log.w(TAG, "Unable to start alarm vibration.", exception)
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

    private fun invokeStartedCallback(callback: (() -> Unit)?) {
        if (callback == null) return
        try {
            callback.invoke()
        } catch (exception: Exception) {
            Log.e(TAG, "Playback-start callback failed.", exception)
        }
    }

    private fun notifyFailure(callback: ((String) -> Unit)?, message: String) {
        if (callback == null) {
            Log.e(TAG, message)
            return
        }
        runOnMain {
            try {
                callback.invoke(message)
            } catch (exception: Exception) {
                Log.e(TAG, "Playback-error callback failed.", exception)
            }
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    // Bundle required by TextToSpeech.speak() for utterance callbacks to fire.
    private fun buildTtsBundle() = android.os.Bundle().apply {
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
    }
}
