package com.eticmax.novaismobile

import android.annotation.SuppressLint
import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.LinkedBlockingQueue

/**
 * Mikrofon kaydini (16kHz, 16-bit, mono PCM — Gemini'nin bekledigi format)
 * ve hoparlor oynatmasini (24kHz, 16-bit, mono PCM — Gemini'nin dondurdugu
 * format) yonetir. NOVAIS Windows surumundeki pyaudio kullanimiyla ayni
 * islevi gorur, ama Android'in native AudioRecord/AudioTrack API'leriyle.
 */
class AudioStreamer(
    private val onAudioCaptured: (ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "NovaisAudio"
        private const val SEND_SAMPLE_RATE = 16000
        private const val RECV_SAMPLE_RATE = 24000
        private const val CHUNK_SIZE = 1024
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordJob: Job? = null
    private var isRecording = false
    private var isMuted = false

    // Hoparlor icin: NOVAIS Windows surumundeki gibi, her ses parcasi icin
    // ayri ayri thread/coroutine baslatmak yerine KALICI, TEK bir isci
    // uzerinden kesintisiz calma — sesin "kesik kesik" gelmesini onlemek icin.
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()
    private var playbackThread: Thread? = null
    private val stopSentinel = ByteArray(0)

    @SuppressLint("MissingPermission")
    fun startRecording(scope: CoroutineScope) {
        val minBufSize = AudioRecord.getMinBufferSize(
            SEND_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SEND_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufSize, CHUNK_SIZE * 4)
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord başlatılamadı — mikrofon meşgul veya erişilemez olabilir.")
            return
        }

        audioRecord?.startRecording()
        isRecording = true

        recordJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(CHUNK_SIZE)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0 && !isMuted) {
                    onAudioCaptured(buffer.copyOf(read))
                }
            }
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    fun stopRecording() {
        isRecording = false
        recordJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun startPlayback() {
        val minBufSize = AudioTrack.getMinBufferSize(
            RECV_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(RECV_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            maxOf(minBufSize, CHUNK_SIZE * 4),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()

        // Kalici tek oynatma is parcacigi — NOVAIS Windows'daki duzeltmeyle
        // ayni mantik: her parca icin ayri thread/coroutine yerine surekli
        // calisan tek bir isci, jitter/kesinti riskini azaltir.
        playbackThread = Thread {
            while (true) {
                val chunk = playbackQueue.take()
                if (chunk === stopSentinel) break
                try {
                    audioTrack?.write(chunk, 0, chunk.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Ses yazma hatası: ${e.message}")
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun playChunk(pcm: ByteArray) {
        playbackQueue.put(pcm)
    }

    /** NOVAIS konusurken kullanicinin sozunu kesmesi durumunda kuyrugu bosalt. */
    fun clearPlaybackQueue() {
        playbackQueue.clear()
    }

    fun stopPlayback() {
        playbackQueue.put(stopSentinel)
        playbackThread?.join(500)
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
