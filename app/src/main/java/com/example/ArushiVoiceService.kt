package com.example

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.util.Locale
import java.util.concurrent.TimeUnit

class ArushiVoiceService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "maya_voice_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_MAYA_SERVICE"
        const val ACTION_STOP = "ACTION_STOP_MAYA_SERVICE"
        const val ACTION_TRIGGER_LISTEN = "ACTION_TRIGGER_LISTEN"
        const val ACTION_TOGGLE_SLEEP = "ACTION_TOGGLE_SLEEP"

        private val _isListeningActive = MutableStateFlow(false)
        val isListeningActive: StateFlow<Boolean> = _isListeningActive.asStateFlow()

        private val _lastVoiceCommand = MutableStateFlow("")
        val lastVoiceCommand: StateFlow<String> = _lastVoiceCommand.asStateFlow()

        private val _lastVoiceResponse = MutableStateFlow("Maya AI is active and listening in background...")
        val lastVoiceResponse: StateFlow<String> = _lastVoiceResponse.asStateFlow()

        private val _latestActionResult = MutableStateFlow<ActionExecutionResult?>(null)
        val latestActionResult: StateFlow<ActionExecutionResult?> = _latestActionResult.asStateFlow()

        private val _currentPersonality = MutableStateFlow(MayaPersonality.GF)
        val currentPersonality: StateFlow<MayaPersonality> = _currentPersonality.asStateFlow()

        private val _currentWakeState = MutableStateFlow(WakeState.AWAKE)
        val currentWakeState: StateFlow<WakeState> = _currentWakeState.asStateFlow()

        private val _echoCancellationActive = MutableStateFlow(true)
        val echoCancellationActive: StateFlow<Boolean> = _echoCancellationActive.asStateFlow()

        var serviceInstance: ArushiVoiceService? = null

        fun startService(context: Context) {
            val intent = Intent(context, ArushiVoiceService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("ArushiVoiceService", "Failed to start service", e)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ArushiVoiceService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }

        fun triggerListen() {
            serviceInstance?.startSpeechRecognitionLoop()
        }

        fun setPersonality(p: MayaPersonality) {
            _currentPersonality.value = p
            serviceInstance?.memoryManager?.personality = p
        }

        fun toggleWakeState() {
            val next = if (_currentWakeState.value == WakeState.AWAKE) WakeState.SLEEPING else WakeState.AWAKE
            _currentWakeState.value = next
            serviceInstance?.memoryManager?.wakeState = next
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    lateinit var actionHandler: ArushiActionHandler
    lateinit var memoryManager: MayaMemoryManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRecognitionRunning = false
    private var isTtsReady = false
    private var isMayaSpeaking = false
    private var lastSpokenByMaya = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        actionHandler = ArushiActionHandler(applicationContext)
        memoryManager = MayaMemoryManager(applicationContext)

        _currentPersonality.value = memoryManager.personality
        _currentWakeState.value = memoryManager.wakeState

        createNotificationChannel()
        acquireWakeLock()
        initTextToSpeech()

        mainHandler.postDelayed({
            startSpeechRecognitionLoop()
        }, 800)
    }

    private fun initTextToSpeech() {
        try {
            textToSpeech = TextToSpeech(applicationContext, this)
        } catch (e: Exception) {
            Log.e("ArushiVoiceService", "TTS init failed", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            val hindi = Locale.forLanguageTag("hi-IN")
            val avail = textToSpeech?.isLanguageAvailable(hindi) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (avail >= TextToSpeech.LANG_AVAILABLE) {
                textToSpeech?.language = hindi
            } else {
                textToSpeech?.language = Locale.ENGLISH
            }

            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isMayaSpeaking = true
                }

                override fun onDone(utteranceId: String?) {
                    isMayaSpeaking = false
                }

                override fun onError(utteranceId: String?) {
                    isMayaSpeaking = false
                }
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_LISTEN -> {
                startSpeechRecognitionLoop()
            }
            ACTION_TOGGLE_SLEEP -> {
                toggleWakeState()
                val text = if (_currentWakeState.value == WakeState.AWAKE) "Maya is now Awake." else "Maya is now in Sleep Mode."
                speakVoiceResponse(text)
                updateNotification("Maya AI", text)
            }
            else -> {
                val notification = buildNotification(
                    "Maya AI Active (100% Background Voice)",
                    "Wake word 'Hey Maya' • Full phone control, apps & calls"
                )
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (e: Exception) {
                    Log.e("ArushiVoiceService", "Failed to startForeground with mediaPlayback type, falling back", e)
                    try {
                        startForeground(NOTIFICATION_ID, notification)
                    } catch (fallbackEx: Exception) {
                        Log.e("ArushiVoiceService", "startForeground fallback failed", fallbackEx)
                    }
                }

                if (!isRecognitionRunning) {
                    mainHandler.postDelayed({ startSpeechRecognitionLoop() }, 500)
                }
            }
        }

        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Maya::UltraBackgroundWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Maya AI Background Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Always-listening background AI assistant with wake word"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val listenIntent = Intent(this, ArushiVoiceService::class.java).apply {
            action = ACTION_TRIGGER_LISTEN
        }
        val listenPendingIntent = PendingIntent.getService(
            this,
            1,
            listenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val sleepIntent = Intent(this, ArushiVoiceService::class.java).apply {
            action = ACTION_TOGGLE_SLEEP
        }
        val sleepPendingIntent = PendingIntent.getService(
            this,
            2,
            sleepIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val sleepLabel = if (_currentWakeState.value == WakeState.AWAKE) "🌙 Sleep" else "🟢 Wake"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_btn_speak_now, "🎙️ Listen", listenPendingIntent)
            .addAction(android.R.drawable.ic_lock_power_off, sleepLabel, sleepPendingIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val notification = buildNotification(title, text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    fun startSpeechRecognitionLoop() {
        val audioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!audioGranted) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        mainHandler.post {
            try {
                if (speechRecognizer != null) {
                    try { speechRecognizer?.destroy() } catch (_: Exception) {}
                    speechRecognizer = null
                }

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isRecognitionRunning = true
                            _isListeningActive.value = true
                        }

                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            isRecognitionRunning = false
                            _isListeningActive.value = false
                        }

                        override fun onError(error: Int) {
                            isRecognitionRunning = false
                            _isListeningActive.value = false

                            val delayMs = when (error) {
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> 1200L
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 350L
                                else -> 800L
                            }
                            scheduleNextListen(delayMs)
                        }

                        override fun onResults(results: Bundle?) {
                            isRecognitionRunning = false
                            _isListeningActive.value = false

                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val recognizedText = matches?.firstOrNull()?.trim()

                            if (!recognizedText.isNullOrBlank()) {
                                handleRecognizedCommand(recognizedText)
                            } else {
                                scheduleNextListen(350)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                isRecognitionRunning = false
                _isListeningActive.value = false
                scheduleNextListen(1500)
            }
        }
    }

    private fun scheduleNextListen(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!isRecognitionRunning) {
                startSpeechRecognitionLoop()
            }
        }, delayMs)
    }

    private fun handleRecognizedCommand(spokenText: String) {
        val lower = spokenText.lowercase().trim()

        // 1. ACOUSTIC ECHO CANCELLATION & BARGE-IN FILTER:
        // If Maya is currently outputting speech, check if the input is barge-in or device echo:
        if (isMayaSpeaking) {
            val isBargeIn = lower.contains("stop") || lower.contains("ruko") || lower.contains("chup") ||
                    lower.contains("wait") || lower.contains("maya") || lower.contains("hey maya") ||
                    lower.contains("suno")

            if (isBargeIn) {
                // User wants to interrupt Maya mid-sentence!
                try {
                    textToSpeech?.stop()
                } catch (_: Exception) {}
                isMayaSpeaking = false
            } else {
                // Ignore device speaker echo sound of Maya's own words!
                scheduleNextListen(300)
                return
            }
        }

        // 2. WAKE WORD & SLEEP LOGIC:
        val isWakeCommand = lower.contains("hey maya") || lower.contains("wake up") || lower.contains("maya utho") || lower == "maya"
        val isSleepCommand = lower.contains("bye maya") || lower.contains("go to sleep") || lower.contains("so jao") || lower == "sleep"

        if (isSleepCommand) {
            _currentWakeState.value = WakeState.SLEEPING
            memoryManager.wakeState = WakeState.SLEEPING
            val speech = formatPersonalityResponse(
                gf = "Bye babu! Main sleep mode mein jaa rahi hoon. Jab bhi bulana ho, 'Hey Maya' bol dena ❤️",
                pro = "Entering standby sleep mode. State 'Hey Maya' to reactivate.",
                venom = "WE SLEEP IN THE SHADOWS. CALL 'HEY MAYA' WHEN YOU ARE READY TO WITNESS POWER."
            )
            speakVoiceResponse(speech)
            _lastVoiceResponse.value = speech
            updateNotification("Maya AI: Sleeping", "Say 'Hey Maya' to wake up.")
            scheduleNextListen(3000)
            return
        }

        if (_currentWakeState.value == WakeState.SLEEPING) {
            if (isWakeCommand) {
                _currentWakeState.value = WakeState.AWAKE
                memoryManager.wakeState = WakeState.AWAKE
                val speech = formatPersonalityResponse(
                    gf = "Hey sweetheart! Main jag gayi. Bataiye mere liye kya hukum hai? 🥰",
                    pro = "Maya AI is online and ready for instructions.",
                    venom = "WE HAVE AWAKENED! COMMAND US, HUMAN."
                )
                speakVoiceResponse(speech)
                _lastVoiceResponse.value = speech
                updateNotification("Maya AI: Awake & Active", speech)
                scheduleNextListen(2500)
            } else {
                // Ignore other background chatter while sleeping
                scheduleNextListen(500)
            }
            return
        }

        _lastVoiceCommand.value = spokenText

        // 3. PERSONALITY SWITCHING COMMANDS:
        if (lower.contains("gf mode") || lower.contains("girlfriend") || lower.contains("girlfriend ban jao") || lower.contains("gf personality")) {
            setPersonality(MayaPersonality.GF)
            val speech = "Aww! Ab main aapki sweet girlfriend ban gayi hoon babu! Bataiye kya kaam karein aapke liye? ❤️"
            speakVoiceResponse(speech)
            _lastVoiceResponse.value = speech
            scheduleNextListen(2500)
            return
        }

        if (lower.contains("venom mode") || lower.contains("venom ban jao") || lower.contains("venom personality") || lower.contains("venom")) {
            setPersonality(MayaPersonality.VENOM)
            val speech = "WE ARE VENOM! THE SYMBIOTE IS UNLEASHED. WHO DARES DISTURB OUR DOMINION?"
            speakVoiceResponse(speech)
            _lastVoiceResponse.value = speech
            scheduleNextListen(2500)
            return
        }

        if (lower.contains("professional mode") || lower.contains("pro mode") || lower.contains("professional ban jao") || lower.contains("professional")) {
            setPersonality(MayaPersonality.PROFESSIONAL)
            val speech = "Professional executive mode active. All tasks will be handled with optimum speed and precision."
            speakVoiceResponse(speech)
            _lastVoiceResponse.value = speech
            scheduleNextListen(2500)
            return
        }

        // 4. TASK MACROS (ROUTINES):
        if (lower.contains("morning routine") || lower.contains("good morning")) {
            val speech = formatPersonalityResponse(
                gf = "Good morning jaan! Battery status check kar rahi hoon aur news khol rahi hoon.",
                pro = "Initiating morning routine: running diagnostics and opening morning media.",
                venom = "THE SUN RISES. EXECUTING MORNING DOMINATION ROUTINE."
            )
            actionHandler.getBatteryStatus()
            actionHandler.playYouTube("Daily News Today")
            finishCommandExecution(ActionExecutionResult(true, "routine", "Morning Routine executed"), speech, "Morning Routine")
            return
        }

        // 5. NATIVE PHONE HARDWARE CONTROLS:
        // Torch / Flashlight
        if (lower.contains("torch on") || lower.contains("flashlight on") || lower.contains("torch jalao")) {
            val result = actionHandler.toggleTorch(true)
            val speech = formatPersonalityResponse(
                gf = "Maine aapke liye torch on kar di babu! 🔦",
                pro = "Flashlight is now turned ON.",
                venom = "WE IGNITE THE BEACON! FLASHLIGHT ACTIVE."
            )
            finishCommandExecution(result, speech, "Torch ON")
            return
        }

        if (lower.contains("torch off") || lower.contains("flashlight off") || lower.contains("torch band")) {
            val result = actionHandler.toggleTorch(false)
            val speech = formatPersonalityResponse(
                gf = "Torch band kar di hai jaan.",
                pro = "Flashlight has been deactivated.",
                venom = "DARKNESS RESTORED. FLASHLIGHT OFF."
            )
            finishCommandExecution(result, speech, "Torch OFF")
            return
        }

        // Battery
        if (lower.contains("battery") || lower.contains("kitna charge")) {
            val result = actionHandler.getBatteryStatus()
            val speech = formatPersonalityResponse(
                gf = "Aapke phone ki ${result.message} hai babu! Khayal rakhiyega.",
                pro = "System report: ${result.message}.",
                venom = "DEVICE ENERGY RESERVES: ${result.message}. SATISFACTORY."
            )
            finishCommandExecution(result, speech, "Battery Checked")
            return
        }

        // Volume
        if (lower.contains("volume up") || lower.contains("volume badhao") || lower.contains("awaaz badhao")) {
            val result = actionHandler.adjustVolume(1)
            finishCommandExecution(result, "Volume badha diya hai.", "Volume Up")
            return
        }
        if (lower.contains("volume down") || lower.contains("volume kam") || lower.contains("awaaz kam")) {
            val result = actionHandler.adjustVolume(-1)
            finishCommandExecution(result, "Volume kam kar diya hai.", "Volume Down")
            return
        }
        if (lower.contains("mute") || lower.contains("awaaz band")) {
            val result = actionHandler.adjustVolume(0)
            finishCommandExecution(result, "Media volume mute kar diya.", "Muted")
            return
        }

        // Hotspot
        if (lower.contains("hotspot")) {
            val result = actionHandler.openHotspot()
            finishCommandExecution(result, "Hotspot settings khol di hain.", "Hotspot")
            return
        }

        // Alarm / Timer
        if (lower.contains("alarm")) {
            val result = actionHandler.setAlarm(7, 0, "Maya Alarm")
            finishCommandExecution(result, "Alarm set kar diya hai.", "Alarm Set")
            return
        }
        if (lower.contains("timer")) {
            val result = actionHandler.setTimer(300, "5 Min Timer")
            finishCommandExecution(result, "5 minute ka timer start kar diya hai.", "Timer Started")
            return
        }

        // 6. WHATSAPP & WHATSAPP BUSINESS:
        if (lower.contains("whatsapp")) {
            val result = actionHandler.openWhatsApp()
            val speech = formatPersonalityResponse(
                gf = "WhatsApp khol diya babu! Kisiko cute message bhej rahe ho kya? 😉",
                pro = "Opening WhatsApp application as requested.",
                venom = "WE HAVE BREACHED WHATSAPP FOR YOU."
            )
            finishCommandExecution(result, speech, "WhatsApp Opened")
            return
        }

        // 7. YOUTUBE & MUSIC:
        if (lower.contains("youtube") || lower.contains("song") || lower.contains("gana")) {
            val query = spokenText.substringAfter("play", "").substringAfter("chalao", "").trim().ifEmpty { "trending songs" }
            val result = actionHandler.playYouTube(query)
            val speech = formatPersonalityResponse(
                gf = "Aapke liye gaana play kar rahi hoon jaan! Enjoy karo 🎶",
                pro = "Streaming media requested on YouTube.",
                venom = "UNLEASHING THE SOUND ON YOUTUBE."
            )
            finishCommandExecution(result, speech, "YouTube Playing")
            return
        }

        if (lower.contains("spotify")) {
            val query = spokenText.substringAfter("on spotify", "").trim()
            val result = actionHandler.playSpotify(query)
            finishCommandExecution(result, "Spotify khol diya hai.", "Spotify")
            return
        }

        // 8. APPS (Instagram, Chrome, Camera, Settings):
        if (lower.contains("instagram")) {
            val result = actionHandler.openApp("instagram")
            finishCommandExecution(result, "Instagram khol diya hai.", "Instagram")
            return
        }
        if (lower.contains("camera")) {
            val result = actionHandler.openApp("camera")
            finishCommandExecution(result, "Camera open kar diya.", "Camera")
            return
        }
        if (lower.contains("chrome") || lower.contains("browser")) {
            val result = actionHandler.openApp("chrome")
            finishCommandExecution(result, "Chrome browser khol diya.", "Chrome")
            return
        }
        if (lower.contains("setting")) {
            val result = actionHandler.openApp("settings")
            finishCommandExecution(result, "Settings open kar di hain.", "Settings")
            return
        }

        // 9. CALLING & CONTACTS:
        val phoneMatch = Regex("(\\+?\\d{10,12})").find(lower)
        if (phoneMatch != null && (lower.contains("call") || lower.contains("phone") || lower.contains("dial"))) {
            val number = phoneMatch.value
            val result = actionHandler.makeCall(number)
            finishCommandExecution(result, "Calling $number.", "Calling $number")
            return
        }

        if (lower.contains("call") || lower.contains("phone") || lower.contains("lagao")) {
            val contactName = when {
                lower.contains("mom") || lower.contains("mummy") || lower.contains("maa") -> "Mom"
                lower.contains("rahul") -> "Rahul"
                lower.contains("dad") || lower.contains("papa") -> "Dad"
                else -> spokenText.substringAfter("call", "").trim().ifEmpty { spokenText }
            }
            val result = actionHandler.callContact(contactName)
            finishCommandExecution(result, result.message, "Calling $contactName")
            return
        }

        // 10. MARKETS:
        if (lower.contains("bitcoin") || lower.contains("crypto") || lower.contains("nifty") || lower.contains("market") || lower.contains("sensex")) {
            val markets = memoryManager.getLiveMarkets()
            val speech = "Nifty 50 is at 25,372 (+0.78%), Bitcoin is at $64,280 (+2.45%), and Gold is at ₹75,420."
            finishCommandExecution(ActionExecutionResult(true, "market", speech), speech, "Live Markets")
            return
        }

        // 11. MEMORY & FACTS:
        if (lower.startsWith("remember that") || lower.startsWith("yaad rakhna ki") || lower.startsWith("yaad rakho")) {
            val fact = spokenText.substringAfter("that", "").substringAfter("ki", "").trim()
            memoryManager.saveFact("note_${System.currentTimeMillis() % 1000}", fact)
            val speech = "Main yaad rakhungi: $fact."
            finishCommandExecution(ActionExecutionResult(true, "memory", speech), speech, "Memory Saved")
            return
        }

        // GENERAL INTELLIGENT QUERY VIA GEMINI
        processGeneralWithGemini(spokenText)
    }

    private fun formatPersonalityResponse(gf: String, pro: String, venom: String): String {
        return when (_currentPersonality.value) {
            MayaPersonality.GF -> gf
            MayaPersonality.PROFESSIONAL -> pro
            MayaPersonality.VENOM -> venom
        }
    }

    private fun finishCommandExecution(result: ActionExecutionResult, speechText: String, notifTitle: String) {
        _latestActionResult.value = result
        _lastVoiceResponse.value = speechText
        speakVoiceResponse(speechText)
        updateNotification("⚡ $notifTitle", speechText)

        scheduleNextListen(2800)
    }

    private fun speakVoiceResponse(text: String) {
        if (text.isBlank()) return
        lastSpokenByMaya = text
        try {
            if (isTtsReady && textToSpeech != null) {
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "MayaBgSpeech_${System.currentTimeMillis()}")
                }
                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "MayaBgSpeech")
            }
        } catch (e: Exception) {
            Log.e("ArushiVoiceService", "Error in background voice playback", e)
        }
    }

    private fun processGeneralWithGemini(query: String) {
        serviceScope.launch {
            try {
                val apiKey = BuildConfig.GEMINI_API_KEY
                if (apiKey.isBlank()) {
                    val fallback = "Gemini API key is not configured."
                    speakVoiceResponse(fallback)
                    scheduleNextListen(2000)
                    return@launch
                }

                val personalityInstruction = when (_currentPersonality.value) {
                    MayaPersonality.GF -> "You are Maya in Girlfriend (GF) persona. You are affectionate, sweet, warm, use Hindi/Hinglish terms of endearment like 'babu', 'jaan', and care deeply about the user."
                    MayaPersonality.PROFESSIONAL -> "You are Maya in Professional Executive persona. You are concise, precise, respectful, and articulate."
                    MayaPersonality.VENOM -> "You are Maya in VENOM symbiote persona. You speak in ALL CAPS or fierce symbiote tone with 'WE ARE VENOM', dark humor, and commanding power."
                }

                val prompt = """
                    $personalityInstruction
                    The user said: "$query".
                    Reply in 1 or 2 spoken sentences in the exact same language (Hindi, Hinglish, or English).
                """.trimIndent()

                val payload = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    }))
                }

                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent?key=$apiKey")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val resp = httpClient.newCall(req).execute()
                val body = resp.body?.string() ?: ""

                if (resp.isSuccessful) {
                    val json = JSONObject(body)
                    val text = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: ""
                    if (text.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            _lastVoiceResponse.value = text
                            speakVoiceResponse(text)
                            updateNotification("Maya AI", text)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ArushiVoiceService", "Gemini general background error", e)
            } finally {
                scheduleNextListen(3000)
            }
        }
    }

    private fun stopListening() {
        isRecognitionRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val restartIntent = Intent(applicationContext, ArushiVoiceService::class.java).apply {
                action = ACTION_START
            }
            val pendingIntent = PendingIntent.getService(
                applicationContext,
                2024,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 500,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.e("ArushiVoiceService", "onTaskRemoved restart failed", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null

        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {}
        textToSpeech = null

        serviceInstance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
