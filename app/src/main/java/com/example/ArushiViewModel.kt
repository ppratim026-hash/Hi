package com.example

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val actionResult: ActionExecutionResult? = null,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageSender {
    USER, ARUSHI
}

sealed class AssistantState {
    data object Idle : AssistantState()
    data object Listening : AssistantState()
    data class Thinking(val message: String = "Maya is thinking...") : AssistantState()
    data class Speaking(val text: String) : AssistantState()
}

data class VoiceExchange(
    val userVoiceInput: String = "",
    val arushiVoiceReply: String = "Namaste! I'm Maya AI, your voice assistant. Say 'Hey Maya' or tap the mic. I can control your phone, open apps, make calls, switch personalities (GF/Venom/Pro), teach on the whiteboard, and work in 100% background!",
    val actionResult: ActionExecutionResult? = null
)

class ArushiViewModel(
    private val actionHandler: ArushiActionHandler
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _voiceExchange = MutableStateFlow(VoiceExchange())
    val voiceExchange: StateFlow<VoiceExchange> = _voiceExchange.asStateFlow()

    private val _assistantState = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    private val _detectedLanguage = MutableStateFlow("English / Hindi")
    val detectedLanguage: StateFlow<String> = _detectedLanguage.asStateFlow()

    private val _latestAction = MutableStateFlow<ActionExecutionResult?>(null)
    val latestAction: StateFlow<ActionExecutionResult?> = _latestAction.asStateFlow()

    private val _personality = MutableStateFlow(MayaPersonality.GF)
    val personality: StateFlow<MayaPersonality> = _personality.asStateFlow()

    private val _activeTab = MutableStateFlow(0) // 0: Voice, 1: Whiteboard, 2: Markets, 3: Code/Web, 4: Memory
    val activeTab: StateFlow<Int> = _activeTab.asStateFlow()

    private val _generatedCode = MutableStateFlow<String?>(null)
    val generatedCode: StateFlow<String?> = _generatedCode.asStateFlow()

    private val _whiteboardNotes = MutableStateFlow(
        listOf(
            "📚 Maya AI Study Whiteboard",
            "Formula: E = mc²",
            "Photosynthesis: 6CO₂ + 6H₂O + Sunlight ➔ C₆H₁₂O₆ + 6O₂",
            "Key takeaway: Speak anytime to ask questions!"
        )
    )
    val whiteboardNotes: StateFlow<List<String>> = _whiteboardNotes.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var currentAudioTrack: AudioTrack? = null
    private var speechJob: Job? = null
    private val conversationTurns = mutableListOf<JSONObject>()

    init {
        _messages.value = listOf(
            ChatMessage(
                sender = MessageSender.ARUSHI,
                text = "Namaste! I'm Maya AI, your voice assistant. Always active in background!"
            )
        )
    }

    fun setTab(index: Int) {
        _activeTab.value = index
    }

    fun setPersonality(p: MayaPersonality) {
        _personality.value = p
        ArushiVoiceService.setPersonality(p)
    }

    fun detectLanguage(text: String): String {
        val hindiRegex = Regex("[\u0900-\u097F]")
        val bengaliRegex = Regex("[\u0980-\u09FF]")
        val tamilRegex = Regex("[\u0B80-\u0BFF]")
        val teluguRegex = Regex("[\u0C00-\u0C7F]")
        val hinglishKeywords = Regex("\\b(kholo|karo|batao|kaisa|kaisi|hai|ho|main|aap|tum|phone|lagao|chalao|madhe|aahe|kijiye|chahiye)\\b", RegexOption.IGNORE_CASE)

        val lang = when {
            hindiRegex.containsMatchIn(text) -> "Hindi (हिंदी)"
            bengaliRegex.containsMatchIn(text) -> "Bengali (বাংলা)"
            tamilRegex.containsMatchIn(text) -> "Tamil (தமிழ்)"
            teluguRegex.containsMatchIn(text) -> "Telugu (తెలుగు)"
            hinglishKeywords.containsMatchIn(text) -> "Hinglish / Hindi"
            else -> "English"
        }
        _detectedLanguage.value = lang
        return lang
    }

    fun interrupt() {
        speechJob?.cancel()
        try {
            currentAudioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    pause()
                    flush()
                    stop()
                }
                release()
            }
        } catch (_: Exception) {}
        currentAudioTrack = null
        _assistantState.value = AssistantState.Idle
    }

    fun setListening(listening: Boolean) {
        if (listening) {
            interrupt()
            _assistantState.value = AssistantState.Listening
        } else {
            if (_assistantState.value is AssistantState.Listening) {
                _assistantState.value = AssistantState.Idle
            }
        }
    }

    fun submitQuery(userText: String) {
        val cleanText = userText.trim()
        if (cleanText.isEmpty()) return

        interrupt()
        val lang = detectLanguage(cleanText)

        _voiceExchange.value = VoiceExchange(
            userVoiceInput = cleanText,
            arushiVoiceReply = "Thinking...",
            actionResult = null
        )

        _messages.value = _messages.value + ChatMessage(
            sender = MessageSender.USER,
            text = cleanText
        )

        val directCommand = parseDirectCommand(cleanText, lang)
        if (directCommand != null) {
            executeDirectCommand(directCommand)
        } else {
            processWithGemini(cleanText)
        }
    }

    private data class DirectCommand(
        val actionType: String,
        val param: String = "",
        val speechConfirmation: String
    )

    private fun parseDirectCommand(text: String, lang: String): DirectCommand? {
        val lower = text.lowercase().trim()

        // Personality Switch
        if (lower.contains("gf mode") || lower.contains("girlfriend mode") || lower.contains("girlfriend ban jao")) {
            setPersonality(MayaPersonality.GF)
            return DirectCommand("SET_PERSONALITY", "GF", "Aww! Ab main aapki sweet girlfriend ban gayi hoon babu! ❤️")
        }
        if (lower.contains("venom mode") || lower.contains("venom ban jao")) {
            setPersonality(MayaPersonality.VENOM)
            return DirectCommand("SET_PERSONALITY", "VENOM", "WE ARE VENOM! WHO DARES COMMAND THE SYMBIOTE?")
        }
        if (lower.contains("pro mode") || lower.contains("professional mode")) {
            setPersonality(MayaPersonality.PROFESSIONAL)
            return DirectCommand("SET_PERSONALITY", "PROFESSIONAL", "Professional mode activated. Executing with high precision.")
        }

        // Torch
        if (lower.contains("torch on") || lower.contains("flashlight on") || lower.contains("torch jalao")) {
            return DirectCommand("TORCH_ON", "", "Torch on kar di hai.")
        }
        if (lower.contains("torch off") || lower.contains("flashlight off") || lower.contains("torch band")) {
            return DirectCommand("TORCH_OFF", "", "Torch band kar di hai.")
        }

        // Battery
        if (lower.contains("battery") || lower.contains("charge kitna")) {
            return DirectCommand("BATTERY", "", "Checking battery status...")
        }

        // Volume
        if (lower.contains("volume up") || lower.contains("volume badhao")) {
            return DirectCommand("VOLUME_UP", "", "Volume badha diya hai.")
        }
        if (lower.contains("volume down") || lower.contains("volume kam")) {
            return DirectCommand("VOLUME_DOWN", "", "Volume kam kar diya hai.")
        }
        if (lower.contains("mute")) {
            return DirectCommand("MUTE", "", "Media volume mute kar diya.")
        }

        // Hotspot
        if (lower.contains("hotspot")) {
            return DirectCommand("HOTSPOT", "", "Opening Hotspot settings.")
        }

        // Alarms
        if (lower.contains("alarm")) {
            return DirectCommand("ALARM", "", "Alarm set kar diya hai.")
        }
        if (lower.contains("timer")) {
            return DirectCommand("TIMER", "", "Timer set kar diya hai.")
        }

        // Whiteboard / Study
        if (lower.contains("whiteboard") || lower.contains("tutor") || lower.contains("padhao")) {
            _activeTab.value = 1
            return DirectCommand("WHITEBOARD", "", "Whiteboard tutor mode open kar diya hai.")
        }

        // Code / Website generator
        if (lower.contains("website") || lower.contains("coding") || lower.contains("code likho") || lower.contains("build web")) {
            _activeTab.value = 3
            val sampleHtml = """
<!DOCTYPE html>
<html>
<head>
  <title>Created by Maya AI</title>
  <style>
    body { font-family: sans-serif; background: #0f172a; color: #fff; text-align: center; padding: 50px; }
    h1 { color: #a855f7; }
    .btn { background: #ec4899; color: white; padding: 12px 24px; border: none; border-radius: 8px; font-size: 16px; cursor: pointer; }
  </style>
</head>
<body>
  <h1>🚀 Built by Maya AI Voice Assistant</h1>
  <p>Your responsive modern website is ready to deploy!</p>
  <button class="btn" onclick="alert('Hello from Maya AI!')">Click Me</button>
</body>
</html>
            """.trimIndent()
            _generatedCode.value = sampleHtml
            return DirectCommand("CODE_GEN", "", "Website code ready hai! Code viewer mein preview dekh sakte hain.")
        }

        // Markets
        if (lower.contains("nifty") || lower.contains("bitcoin") || lower.contains("crypto") || lower.contains("market")) {
            _activeTab.value = 2
            return DirectCommand("MARKET", "", "Nifty 50 is at 25,372 and Bitcoin is at $64,280.")
        }

        // WhatsApp
        if (lower.contains("whatsapp")) {
            val speech = if (lower.contains("kholo") || lower.contains("chalao")) "व्हाट्सएप खोल रही हूँ।" else "Opening WhatsApp for you."
            return DirectCommand("OPEN_WHATSAPP", speechConfirmation = speech)
        }

        // YouTube
        if (lower.contains("youtube") || lower.contains("song") || lower.contains("gana")) {
            val query = text.substringAfter("play", "").substringAfter("chalao", "").trim()
            return DirectCommand("PLAY_YOUTUBE", param = query, speechConfirmation = "YouTube khol rahi hoon.")
        }

        // Spotify
        if (lower.contains("spotify")) {
            val query = text.substringAfter("on spotify", "").trim()
            return DirectCommand("PLAY_SPOTIFY", param = query, speechConfirmation = "Opening Spotify.")
        }

        // Apps
        if (lower.contains("instagram")) return DirectCommand("OPEN_APP", param = "instagram", speechConfirmation = "Opening Instagram.")
        if (lower.contains("camera")) return DirectCommand("OPEN_APP", param = "camera", speechConfirmation = "Opening Camera.")
        if (lower.contains("chrome") || lower.contains("browser")) return DirectCommand("OPEN_APP", param = "chrome", speechConfirmation = "Opening Chrome.")
        if (lower.contains("settings")) return DirectCommand("OPEN_APP", param = "settings", speechConfirmation = "Opening Settings.")

        // Calling
        val phoneMatch = Regex("(\\+?\\d{10,12})").find(lower)
        if (phoneMatch != null && (lower.contains("call") || lower.contains("phone") || lower.contains("dial"))) {
            return DirectCommand("MAKE_CALL", param = phoneMatch.value, speechConfirmation = "Calling ${phoneMatch.value}.")
        }

        val contactPatterns = listOf(
            Regex("(mummy|mom|mother|maa)\\s*(ko\\s*)?(call|phone)?", RegexOption.IGNORE_CASE) to "Mom",
            Regex("call\\s*(mom|mummy|mother|maa)", RegexOption.IGNORE_CASE) to "Mom",
            Regex("(rahul)\\s*(ko\\s*)?(call|phone)?", RegexOption.IGNORE_CASE) to "Rahul",
            Regex("call\\s*(rahul)", RegexOption.IGNORE_CASE) to "Rahul",
            Regex("(papa|dad|father|baba)\\s*(ko\\s*)?(call|phone)?", RegexOption.IGNORE_CASE) to "Dad",
            Regex("call\\s*(papa|dad|father)", RegexOption.IGNORE_CASE) to "Dad"
        )
        for ((pattern, contactName) in contactPatterns) {
            if (pattern.containsMatchIn(lower)) {
                return DirectCommand("CALL_CONTACT", param = contactName, speechConfirmation = "Calling $contactName.")
            }
        }

        return null
    }

    private fun executeDirectCommand(command: DirectCommand) {
        speechJob = viewModelScope.launch(Dispatchers.IO) {
            _assistantState.value = AssistantState.Thinking("Executing...")

            var actionResult: ActionExecutionResult? = null
            var finalSpeech = command.speechConfirmation

            when (command.actionType) {
                "TORCH_ON" -> actionResult = withContext(Dispatchers.Main) { actionHandler.toggleTorch(true) }
                "TORCH_OFF" -> actionResult = withContext(Dispatchers.Main) { actionHandler.toggleTorch(false) }
                "BATTERY" -> {
                    actionResult = withContext(Dispatchers.Main) { actionHandler.getBatteryStatus() }
                    finalSpeech = actionResult.message
                }
                "VOLUME_UP" -> actionResult = withContext(Dispatchers.Main) { actionHandler.adjustVolume(1) }
                "VOLUME_DOWN" -> actionResult = withContext(Dispatchers.Main) { actionHandler.adjustVolume(-1) }
                "MUTE" -> actionResult = withContext(Dispatchers.Main) { actionHandler.adjustVolume(0) }
                "HOTSPOT" -> actionResult = withContext(Dispatchers.Main) { actionHandler.openHotspot() }
                "ALARM" -> actionResult = withContext(Dispatchers.Main) { actionHandler.setAlarm(7, 0) }
                "TIMER" -> actionResult = withContext(Dispatchers.Main) { actionHandler.setTimer(300) }
                "OPEN_WHATSAPP" -> actionResult = withContext(Dispatchers.Main) { actionHandler.openWhatsApp() }
                "PLAY_YOUTUBE" -> actionResult = withContext(Dispatchers.Main) { actionHandler.playYouTube(command.param) }
                "PLAY_SPOTIFY" -> actionResult = withContext(Dispatchers.Main) { actionHandler.playSpotify(command.param) }
                "OPEN_APP" -> actionResult = withContext(Dispatchers.Main) { actionHandler.openApp(command.param) }
                "MAKE_CALL" -> actionResult = withContext(Dispatchers.Main) { actionHandler.makeCall(command.param) }
                "CALL_CONTACT" -> actionResult = withContext(Dispatchers.Main) { actionHandler.callContact(command.param) }
                "SET_PERSONALITY" -> {
                    actionResult = ActionExecutionResult(true, "personality", "Personality updated to ${command.param}")
                }
                "WHITEBOARD", "CODE_GEN", "MARKET" -> {
                    actionResult = ActionExecutionResult(true, command.actionType, finalSpeech)
                }
            }

            _latestAction.value = actionResult

            withContext(Dispatchers.Main) {
                _voiceExchange.value = VoiceExchange(
                    userVoiceInput = _voiceExchange.value.userVoiceInput,
                    arushiVoiceReply = finalSpeech,
                    actionResult = actionResult
                )
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.ARUSHI,
                    text = finalSpeech,
                    actionResult = actionResult
                )
            }

            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isNotBlank()) {
                playVoiceWithGemini(finalSpeech, apiKey)
            } else {
                _assistantState.value = AssistantState.Idle
            }
        }
    }

    private fun processWithGemini(userText: String) {
        speechJob = viewModelScope.launch(Dispatchers.IO) {
            _assistantState.value = AssistantState.Thinking("Understanding...")

            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isBlank()) {
                withContext(Dispatchers.Main) {
                    _voiceExchange.value = VoiceExchange(
                        userVoiceInput = userText,
                        arushiVoiceReply = "Gemini API key is not configured. Please set your key in the AI Studio Secrets panel."
                    )
                    _assistantState.value = AssistantState.Idle
                }
                return@launch
            }

            try {
                val userTurn = JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userText)))
                }
                conversationTurns.add(userTurn)

                val personalityInstruction = when (_personality.value) {
                    MayaPersonality.GF -> "You are Maya in Girlfriend (GF) persona. You are affectionate, sweet, warm, use Hindi/Hinglish terms of endearment like 'babu', 'jaan', and care deeply about the user."
                    MayaPersonality.PROFESSIONAL -> "You are Maya in Professional Executive persona. You are concise, precise, respectful, and articulate."
                    MayaPersonality.VENOM -> "You are Maya in VENOM symbiote persona. You speak in ALL CAPS or fierce symbiote tone with 'WE ARE VENOM', dark humor, and commanding power."
                }

                val systemPrompt = """
                    You are Maya AI, an intelligent, multi-personality Indian AI voice assistant.
                    $personalityInstruction
                    Languages: Understand and naturally speak in Hindi, English, Hinglish, Marathi, Bengali, Tamil, Telugu, and other languages.
                    Always respond in the exact same language and dialect the user is currently speaking.
                    Keep verbal voice responses conversational and concise (1-2 spoken sentences).
                """.trimIndent()

                val requestPayload = JSONObject().apply {
                    put("contents", JSONArray(conversationTurns))
                    put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                }

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent?key=$apiKey")
                    .post(requestPayload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    throw RuntimeException("Gemini HTTP ${response.code}: $responseBody")
                }

                val responseJson = JSONObject(responseBody)
                val textResponse = responseJson.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: ""

                withContext(Dispatchers.Main) {
                    _voiceExchange.value = VoiceExchange(
                        userVoiceInput = _voiceExchange.value.userVoiceInput,
                        arushiVoiceReply = textResponse,
                        actionResult = null
                    )
                    _messages.value = _messages.value + ChatMessage(
                        sender = MessageSender.ARUSHI,
                        text = textResponse
                    )
                }

                playVoiceWithGemini(textResponse, apiKey)
            } catch (e: Exception) {
                Log.e("MayaVM", "Error in Gemini call", e)
                withContext(Dispatchers.Main) {
                    _voiceExchange.value = VoiceExchange(
                        userVoiceInput = userText,
                        arushiVoiceReply = "Sorry, I had trouble connecting. Please try again."
                    )
                    _assistantState.value = AssistantState.Idle
                }
            }
        }
    }

    private suspend fun playVoiceWithGemini(textToSpeak: String, apiKey: String) {
        if (textToSpeak.isBlank()) {
            _assistantState.value = AssistantState.Idle
            return
        }

        withContext(Dispatchers.Main) {
            _assistantState.value = AssistantState.Speaking(textToSpeak)
        }

        try {
            val voiceName = when (_personality.value) {
                MayaPersonality.GF -> "Aoede"
                MayaPersonality.PROFESSIONAL -> "Kore"
                MayaPersonality.VENOM -> "Fenrir"
            }

            val ttsPayload = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", "Read aloud in an expressive voice: $textToSpeak")
                    }))
                }))
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", voiceName)
                            })
                        })
                    })
                })
            }

            val ttsRequest = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=$apiKey")
                .post(ttsPayload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val ttsResponse = httpClient.newCall(ttsRequest).execute()
            val ttsBody = ttsResponse.body?.string() ?: ""

            if (ttsResponse.isSuccessful) {
                val ttsJson = JSONObject(ttsBody)
                val part = ttsJson.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                val inlineData = part?.optJSONObject("inlineData")
                val base64Data = inlineData?.optString("data")

                if (!base64Data.isNullOrBlank()) {
                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    playPcmAudio(pcmBytes, 24000)
                    return
                }
            }
        } catch (e: Exception) {
            Log.w("MayaVM", "TTS generation fallback", e)
        }

        withContext(Dispatchers.Main) {
            _assistantState.value = AssistantState.Idle
        }
    }

    private fun playPcmAudio(pcmBytes: ByteArray, sampleRate: Int) {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val bufferSize = maxOf(minBufferSize, pcmBytes.size)

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            currentAudioTrack = audioTrack
            audioTrack.play()
            audioTrack.write(pcmBytes, 0, pcmBytes.size)

            val durationMs = (pcmBytes.size / (sampleRate * 2.0) * 1000).toLong()
            Thread.sleep(durationMs)
        } catch (e: Exception) {
            Log.e("MayaVM", "AudioTrack playback error", e)
        } finally {
            try {
                currentAudioTrack?.apply {
                    if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                        stop()
                    }
                    release()
                }
            } catch (_: Exception) {}
            currentAudioTrack = null
            _assistantState.value = AssistantState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        interrupt()
    }
}
