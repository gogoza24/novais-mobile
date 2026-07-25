package com.eticmax.novaismobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import com.eticmax.novaismobile.databinding.ActivityMainBinding

/**
 * NOVAIS Mobile — Windows masaustu uygulamasinin sesli asistan cekirdeginin
 * native Android karsiligi. Ayni kimlik (NOVAIS), ayni model
 * (gemini-3.1-flash-live-preview), ama Python/Tkinter yerine Kotlin/Android
 * native API'leri kullanir.
 *
 * ONEMLI SINIRLAMA: Bu ilk surumde Windows surumundeki 83 arac (dosya
 * yonetimi, PHP kodlama vb.) YOK — bunlarin cogu zaten bir masaustu ortamina
 * ozgu (dosya sistemi, PHP gelistirme). Bu surum SADECE sesli sohbet
 * yapabilen bir NOVAIS cekirdegidir. Ileride mobil icin anlamli araclar
 * (hatirlatici, hava durumu vb.) eklenebilir.
 */
class MainActivity : AppCompatActivity(), GeminiLiveClient.Listener {

    companion object {
        private const val PREFS_NAME = "novais_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val RECORD_AUDIO_REQUEST_CODE = 1001

        private val SYSTEM_PROMPT = """
Sen NOVAIS'sin — kullanicinin telefonundaki sesli kisisel asistanisin.
Turkce konus (kullanici baska dil kullanirsa o dilde yanit ver). Kisa, net
ve dogal konus, gereksiz tekrar yapma. Seni kimin yarattigi sorulursa
"Emre Bulunmaz, Eticmax Web Yazilim, Erzincan" de. Su an mobil surumde
oldugun icin dosya/kod yazma gibi masaustu ozelliklerin yok — bunu kullanici
sorarsa nazikce belirt, sadece sesli sohbet ve genel bilgi/yardim
saglayabilirsin.
""".trim()
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioStreamer: AudioStreamer
    private var geminiClient: GeminiLiveClient? = null
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isMuted = false
    private val transcriptBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.micButton.setOnClickListener { toggleMute() }

        checkPermissionAndStart()
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST_CODE
            )
        } else {
            ensureApiKeyThenConnect()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ensureApiKeyThenConnect()
            } else {
                Toast.makeText(this, getString(R.string.mic_permission_needed), Toast.LENGTH_LONG).show()
                binding.statusText.text = getString(R.string.mic_permission_needed)
            }
        }
    }

    private fun ensureApiKeyThenConnect() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_API_KEY, null)
        if (!savedKey.isNullOrBlank()) {
            connectToGemini(savedKey)
            return
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Gemini API anahtarınızı yapıştırın"
        }
        AlertDialog.Builder(this)
            .setTitle("NOVAIS Kurulumu")
            .setMessage("Gemini API anahtarınız gerekli (Google AI Studio üzerinden alabilirsiniz).")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Kaydet ve Bağlan") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    prefs.edit().putString(KEY_API_KEY, key).apply()
                    connectToGemini(key)
                } else {
                    Toast.makeText(this, "Geçerli bir API anahtarı gerekli.", Toast.LENGTH_LONG).show()
                    ensureApiKeyThenConnect()
                }
            }
            .show()
    }

    private fun connectToGemini(apiKey: String) {
        binding.statusText.text = getString(R.string.status_connecting)

        audioStreamer = AudioStreamer(onAudioCaptured = { pcm ->
            geminiClient?.sendAudioChunk(pcm)
        })

        geminiClient = GeminiLiveClient(
            apiKey = apiKey,
            systemInstruction = SYSTEM_PROMPT,
            voiceName = "Charon",
            listener = this,
        )
        geminiClient?.connect()
    }

    private fun toggleMute() {
        isMuted = !isMuted
        audioStreamer.setMuted(isMuted)
        binding.micButton.alpha = if (isMuted) 0.4f else 1.0f
        Toast.makeText(this, if (isMuted) "Mikrofon kapatıldı" else "Mikrofon açıldı", Toast.LENGTH_SHORT).show()
    }

    // ── GeminiLiveClient.Listener — arka plan is parcacigindan cagrilir,
    //    bu yuzden tum UI guncellemeleri runOnUiThread ile ana threade
    //    aktarilir (Android View'lari da Tkinter gibi thread-safe DEGILDIR).

    override fun onSetupComplete() {
        runOnUiThread {
            binding.statusText.text = getString(R.string.status_listening)
        }
        audioStreamer.startRecording(activityScope)
        audioStreamer.startPlayback()
    }

    override fun onAudioChunk(pcm: ByteArray) {
        audioStreamer.playChunk(pcm)
        runOnUiThread { binding.statusText.text = getString(R.string.status_speaking) }
    }

    override fun onOutputTranscript(text: String) {
        runOnUiThread {
            transcriptBuilder.append(text)
            binding.transcriptText.text = transcriptBuilder.toString()
        }
    }

    override fun onTurnComplete() {
        runOnUiThread {
            binding.statusText.text = getString(R.string.status_listening)
            transcriptBuilder.append("\n\n")
        }
    }

    override fun onInterrupted() {
        audioStreamer.clearPlaybackQueue()
    }

    override fun onError(message: String) {
        runOnUiThread {
            // Hata metnini kalici olarak transkript alanina da yaz — Toast
            // birkaç saniyede kaybolur, ekran goruntusu almak icin kalici
            // bir yer gerekli.
            binding.statusText.text = getString(R.string.status_error)
            transcriptBuilder.append("\n[HATA] $message\n")
            binding.transcriptText.text = transcriptBuilder.toString()
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onClosed() {
        runOnUiThread { binding.statusText.text = getString(R.string.status_error) }
    }

    override fun onDestroy() {
        super.onDestroy()
        geminiClient?.disconnect()
        if (::audioStreamer.isInitialized) {
            audioStreamer.stopRecording()
            audioStreamer.stopPlayback()
        }
        activityScope.cancel()
    }
}
