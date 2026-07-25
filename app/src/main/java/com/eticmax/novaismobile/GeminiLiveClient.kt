package com.eticmax.novaismobile

import android.util.Base64
import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini Live API'ye DOGRUDAN WebSocket ile baglanan istemci — resmi
 * Python/JS SDK'lari kullanmadan, dokumante edilmis BidiGenerateContent
 * WebSocket protokolunu (ws/.../v1alpha.GenerativeService.BidiGenerateContent)
 * uygular. NOVAIS Windows uygulamasindaki main.py'nin ayni islevi Python'da
 * yaptigi seyin, Android icin ham/native karsiligidir.
 *
 * NOT: Bu protokol dogrudan Google'in resmi WebSocket API belgelerinden
 * (ai.google.dev/api/live) alinmistir, ama gercek bir cihazda test
 * edilmemistir — ilk calistirmada kucuk uyumsuzluklar cikabilir, logcat
 * ciktisini (TAG = "NovaisLive") kontrol etmen gerekebilir.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val systemInstruction: String,
    private val voiceName: String = "Charon",
    private val listener: Listener,
) {
    companion object {
        private const val TAG = "NovaisLive"
        // v1alpha kullaniyoruz — gemini-3.1-flash-live-preview bir "preview"
        // model oldugu icin NOVAIS Windows surumu de ayni surumu kullaniyor.
        private const val MODEL = "models/gemini-3.1-flash-live-preview"
        private const val WS_HOST = "generativelanguage.googleapis.com"
        private const val WS_PATH =
            "/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
    }

    interface Listener {
        fun onSetupComplete()
        /** 24kHz 16-bit PCM ham ses verisi — dogrudan AudioTrack'e yazilabilir. */
        fun onAudioChunk(pcm: ByteArray)
        fun onOutputTranscript(text: String)
        fun onTurnComplete()
        fun onInterrupted()
        fun onError(message: String)
        fun onClosed()
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)  // WebSocket icin zaman asimi yok
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun connect() {
        val url = "wss://$WS_HOST$WS_PATH?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket açıldı, setup mesajı gönderiliyor...")
                sendSetupMessage(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // Sunucu bazen mesaji binary frame olarak da gonderebilir —
                // UTF-8 metne cevirip ayni sekilde isle.
                handleServerMessage(bytes.utf8())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket hatası: ${t.message}", t)
                listener.onError("Bağlantı hatası: ${t.message}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket kapandı: $code $reason")
                listener.onClosed()
            }
        })
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val setup = JSONObject().apply {
            put("model", MODEL)
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            })
            put("speechConfig", JSONObject().apply {
                put("voiceConfig", JSONObject().apply {
                    put("prebuiltVoiceConfig", JSONObject().apply {
                        put("voiceName", voiceName)
                    })
                })
            })
            // Dusuk gecikme icin dusunme derinligini minimumda tut (NOVAIS
            // Windows surumundeki thinking_level="minimal" ile ayni mantik).
            put("thinkingConfig", JSONObject().apply {
                put("thinkingLevel", "MINIMAL")
            })
        }
        val message = JSONObject().put("setup", setup)
        ws.send(message.toString())
    }

    /** Mikrofon PCM verisini (16-bit, 16kHz, mono) sunucuya gonderir. */
    fun sendAudioChunk(pcm: ByteArray) {
        val base64Audio = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val message = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("data", base64Audio)
                    put("mimeType", "audio/pcm;rate=16000")
                })
            })
        }
        webSocket?.send(message.toString())
    }

    /** Yazili bir komut gonderir (sesle ayni "realtimeInput" kanaliyla). */
    fun sendText(text: String) {
        val message = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("text", text)
            })
        }
        webSocket?.send(message.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "Kullanıcı kapattı")
        webSocket = null
    }

    private fun handleServerMessage(raw: String) {
        try {
            val json = JSONObject(raw)

            if (json.has("setupComplete")) {
                Log.i(TAG, "Setup tamamlandı — oturum hazır")
                listener.onSetupComplete()
                return
            }

            if (json.has("serverContent")) {
                val sc = json.getJSONObject("serverContent")

                if (sc.has("modelTurn")) {
                    val parts = sc.getJSONObject("modelTurn").optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("inlineData")) {
                                val inline = part.getJSONObject("inlineData")
                                val data = inline.optString("data", "")
                                if (data.isNotEmpty()) {
                                    val pcm = Base64.decode(data, Base64.NO_WRAP)
                                    listener.onAudioChunk(pcm)
                                }
                            }
                        }
                    }
                }

                if (sc.has("outputTranscription")) {
                    val txt = sc.getJSONObject("outputTranscription").optString("text", "")
                    if (txt.isNotEmpty()) {
                        listener.onOutputTranscript(txt)
                    }
                }

                if (sc.optBoolean("turnComplete", false)) {
                    listener.onTurnComplete()
                }

                if (sc.optBoolean("interrupted", false)) {
                    listener.onInterrupted()
                }
            }

            if (json.has("toolCall")) {
                // NOT: Mobil surumde varsayilan olarak arac (tool) tanimlanmadi
                // — bu yuzden normalde bu blok tetiklenmemeli. Ileride mobil
                // icin anlamli araclar (ör. hatirlatici, hava durumu) eklenirse
                // buraya toolResponse gonderme mantigi yazilmali.
                Log.w(TAG, "toolCall alındı ama mobil sürümde henüz araç desteği yok.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sunucu mesajı ayrıştırılamadı: ${e.message}", e)
        }
    }
}
