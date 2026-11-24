package com.example.chat

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class RecordedVoiceDraft(
    val file: File,
    val durationMs: Long,
    val waveform: List<Int>,
    val transcript: String = ""
)

data class AudioPlaybackState(
    val activeKey: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f
)

/**
 * Manages real microphone MediaRecorder capture (.m4a), preview/in-thread MediaPlayer
 * with variable playback speed (1x / 1.5x / 2x), and on-device SpeechRecognizer transcription.
 */
object ChatAudioManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var recorder: MediaRecorder? = null
    private var activeRecordingFile: File? = null
    private var recordingStartMs: Long = 0L
    private val capturedWave = mutableListOf<Int>()
    private var meterJob: Job? = null

    private var player: MediaPlayer? = null
    private var playbackJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private val _playbackState = MutableStateFlow(AudioPlaybackState())
    val playbackState: StateFlow<AudioPlaybackState> = _playbackState.asStateFlow()

    private val _liveAmplitude = MutableStateFlow(0)
    val liveAmplitude: StateFlow<Int> = _liveAmplitude.asStateFlow()

    fun startRecording(context: Context): Result<File> = runCatching {
        stopRecordingInternal()
        stopPlayback()
        val uploadsDir = File(context.filesDir, "chat_uploads").apply { mkdirs() }
        val outFile = File(uploadsDir, "voice_${UUID.randomUUID().toString().take(8)}.m4a")
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context.applicationContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mr.setAudioEncodingBitRate(96_000)
        mr.setAudioSamplingRate(44_100)
        mr.setOutputFile(outFile.absolutePath)
        mr.prepare()
        mr.start()

        recorder = mr
        activeRecordingFile = outFile
        recordingStartMs = System.currentTimeMillis()
        capturedWave.clear()

        meterJob?.cancel()
        meterJob = scope.launch {
            while (recorder != null) {
                val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                val normalized = ((amp / 32767f) * 24f).toInt().coerceIn(4, 24)
                _liveAmplitude.value = normalized
                if (capturedWave.size < 40) capturedWave.add(normalized)
                delay(120L)
            }
        }
        outFile
    }

    fun stopRecording(): RecordedVoiceDraft? {
        val file = activeRecordingFile
        val elapsed = (System.currentTimeMillis() - recordingStartMs).coerceAtLeast(0L)
        val wave = if (capturedWave.isEmpty()) listOf(8, 12, 16, 10, 18, 14, 9) else capturedWave.take(24)
        stopRecordingInternal()
        if (file == null || !file.exists() || file.length() == 0L || elapsed < 400L) {
            runCatching { file?.delete() }
            return null
        }
        return RecordedVoiceDraft(
            file = file,
            durationMs = elapsed,
            waveform = wave
        )
    }

    fun cancelRecording() {
        val file = activeRecordingFile
        stopRecordingInternal()
        runCatching { file?.delete() }
    }

    private fun stopRecordingInternal() {
        meterJob?.cancel()
        meterJob = null
        _liveAmplitude.value = 0
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        activeRecordingFile = null
    }

    fun togglePlay(key: String, file: File) {
        val cur = _playbackState.value
        if (cur.activeKey == key && player != null) {
            val mp = player!!
            if (cur.isPlaying) {
                runCatching { mp.pause() }
                _playbackState.value = cur.copy(isPlaying = false)
            } else {
                applySpeed(mp, cur.speed)
                runCatching { mp.start() }
                _playbackState.value = cur.copy(isPlaying = true)
            }
            return
        }

        stopPlayback()
        runCatching {
            val speed = _playbackState.value.speed
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                applySpeed(this, speed)
                setOnCompletionListener {
                    playbackJob?.cancel()
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = false,
                        positionMs = 0L
                    )
                }
                start()
            }
            player = mp
            _playbackState.value = AudioPlaybackState(
                activeKey = key,
                isPlaying = true,
                positionMs = 0L,
                durationMs = mp.duration.toLong().coerceAtLeast(1L),
                speed = speed
            )
            playbackJob = scope.launch {
                while (player != null && _playbackState.value.isPlaying) {
                    val pos = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
                    _playbackState.value = _playbackState.value.copy(positionMs = pos)
                    delay(120L)
                }
            }
        }
    }

    fun cycleSpeed(): Float {
        val next = when (_playbackState.value.speed) {
            1.0f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        player?.let { applySpeed(it, next) }
        _playbackState.value = _playbackState.value.copy(speed = next)
        return next
    }

    private fun applySpeed(mp: MediaPlayer, speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val params = mp.playbackParams
                params.speed = speed
                mp.playbackParams = params
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        _playbackState.value = _playbackState.value.copy(activeKey = null, isPlaying = false, positionMs = 0L)
    }

    fun startOnDeviceSpeechToText(
        context: Context,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("On-device speech recognition is unavailable on this device.")
            return
        }
        stopSpeechToText()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                onError("Could not capture speech (code $error).")
                stopSpeechToText()
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty().trim()
                if (text.isNotBlank()) onResult(text) else onError("No speech detected.")
                stopSpeechToText()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
    }

    fun stopSpeechToText() {
        runCatching { speechRecognizer?.stopListening() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
    }
}
