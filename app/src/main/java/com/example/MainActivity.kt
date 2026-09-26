package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private lateinit var actionHandler: ArushiActionHandler
    private lateinit var viewModel: ArushiViewModel
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningState = false

    private var hasContactsPermission by mutableStateOf(false)
    private var hasCallPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        hasContactsPermission = permissions[Manifest.permission.READ_CONTACTS] == true
        hasCallPermission = permissions[Manifest.permission.CALL_PHONE] == true

        if (audioGranted) {
            startSpeechInput()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        actionHandler = ArushiActionHandler(this)
        viewModel = ArushiViewModel(actionHandler)

        checkCurrentPermissions()
        startBackgroundVoiceService()

        val audioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!audioGranted) {
            requestAppPermissions()
        }

        setContent {
            MyApplicationTheme {
                ArushiScreen(
                    viewModel = viewModel,
                    hasContactsPermission = hasContactsPermission,
                    hasCallPermission = hasCallPermission,
                    onRequestPermissions = { requestAppPermissions() },
                    onStartSpeechRecognition = { triggerSpeechRecognition() }
                )

                BackHandler {
                    finish()
                }
            }
        }
    }

    private fun startBackgroundVoiceService() {
        try {
            ArushiVoiceService.startService(this)
        } catch (_: Exception) {}
    }

    private fun checkCurrentPermissions() {
        hasContactsPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        hasCallPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    fun requestContactsPermission() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE
            )
        )
    }

    private fun triggerSpeechRecognition() {
        if (isListeningState) {
            stopSpeechInput()
            return
        }

        val audioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!audioGranted) {
            requestAppPermissions()
        } else {
            startSpeechInput()
        }
    }

    private fun startSpeechInput() {
        viewModel.setListening(true)
        isListeningState = true

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available. Please type your query.", Toast.LENGTH_SHORT).show()
            viewModel.setListening(false)
            isListeningState = false
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        isListeningState = false
                        viewModel.setListening(false)
                    }

                    override fun onError(error: Int) {
                        isListeningState = false
                        viewModel.setListening(false)

                        // Recreate recognizer if client or busy error to ensure 100% stability on next tap
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                            try {
                                speechRecognizer?.destroy()
                                speechRecognizer = null
                            } catch (_: Exception) {}
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListeningState = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            viewModel.submitQuery(text)
                        } else {
                            viewModel.setListening(false)
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
            isListeningState = false
            viewModel.setListening(false)
        }
    }

    private fun stopSpeechInput() {
        isListeningState = false
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        viewModel.setListening(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }
}
