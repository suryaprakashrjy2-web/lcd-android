package com.lcd.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val BACKEND_URL = "https://lcd-backend.onrender.com/chat"
    private val TELUGU_LOCALE = Locale("te", "IN")
    private val WAKE_WORD = "hey lcd"

    private lateinit var statusText: TextView
    private lateinit var heardText: TextView
    private lateinit var replyText: TextView
    private lateinit var talkButton: Button

    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private val httpClient = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isListeningModeOn = false
    private var isAwaitingCommand = false

    private val permissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true
            if (!micGranted) {
                Toast.makeText(this, "Microphone permission is needed for 'Hey LCD'", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        heardText = findViewById(R.id.heardText)
        replyText = findViewById(R.id.replyText)
        talkButton = findViewById(R.id.talkButton)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(TELUGU_LOCALE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(this, "Telugu voice not installed on this device — using default", Toast.LENGTH_LONG).show()
                }
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (isListeningModeOn) {
                    mainHandler.post { startWakeWordListening() }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (isListeningModeOn) {
                    mainHandler.post { startWakeWordListening() }
                }
            }
        })

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(recognitionListener)

        permissionLauncher.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE)
        )

        talkButton.text = "▶ Start listening for \"Hey LCD\""
        talkButton.setOnClickListener { toggleListeningMode() }
    }

    private fun toggleListeningMode() {
        if (isListeningModeOn) {
            stopListeningMode()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
                return
            }
            isListeningModeOn = true
            talkButton.text = "⏹ Stop listening"
            startWakeWordListening()
        }
    }

    private fun stopListeningMode() {
        isListeningModeOn = false
        isAwaitingCommand = false
        talkButton.text = "▶ Start listening for \"Hey LCD\""
        statusText.text = "Stopped"
        try {
            speechRecognizer.stopListening()
            speechRecognizer.cancel()
        } catch (e: Exception) {
        }
    }

    private fun startWakeWordListening() {
        if (!isListeningModeOn) return
        statusText.text = if (isAwaitingCommand) "Yes? Listening for your command..." else "Listening for \"Hey LCD\"..."

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "te-IN")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2000)
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 2000)
        }
        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            mainHandler.postDelayed({ if (isListeningModeOn) startWakeWordListening() }, 500)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onError(error: Int) {
            if (isListeningModeOn) {
                mainHandler.postDelayed({ startWakeWordListening() }, 400)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                ?: ""

            if (!isListeningModeOn) return

            if (!isAwaitingCommand) {
                val lower = text.lowercase(Locale.getDefault())
                if (lower.contains(WAKE_WORD) || lower.contains("lcd")) {
                    val afterWake = stripWakeWord(text)
                    if (afterWake.isNotBlank()) {
                        heardText.text = "You said: $afterWake"
                        sendToBackend(afterWake)
                    } else {
                        isAwaitingCommand = true
                        startWakeWordListening()
                    }
                } else {
                    startWakeWordListening()
                }
            } else {
                isAwaitingCommand = false
                if (text.isNotBlank()) {
                    heardText.text = "You said: $text"
                    sendToBackend(text)
                } else {
                    startWakeWordListening()
                }
            }
        }
    }

    private fun stripWakeWord(text: String): String {
        val lower = text.lowercase(Locale.getDefault())
        val idx = lower.indexOf(WAKE_WORD)
        return if (idx >= 0) {
            text.substring(idx + WAKE_WORD.length).trim()
        } else {
            ""
        }
    }

    private fun sendToBackend(message: String) {
        statusText.text = "Thinking..."

        val json = JSONObject().put("message", message).toString()
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(BACKEND_URL).post(body).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    statusText.text = "Couldn't reach LCD — check your internet"
                    if (isListeningModeOn) startWakeWordListening()
                }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val bodyStr = response.body?.string()
                if (bodyStr == null) {
                    runOnUiThread {
                        statusText.text = "Empty response from server"
                        if (isListeningModeOn) startWakeWordListening()
                    }
                    return
                }
                try {
                    val obj = JSONObject(bodyStr)
                    val reply = obj.optString("reply", "")
                    val action = obj.optJSONObject("action")

                    runOnUiThread {
                        replyText.text = reply
                        if (action != null) executeAction(action)
                        if (reply.isNotBlank()) {
                            speak(reply)
                        } else if (isListeningModeOn) {
                            startWakeWordListening()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusText.text = "Couldn't understand server response"
                        if (isListeningModeOn) startWakeWordListening()
                    }
                }
            }
        })
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lcd_reply")
    }

    private fun executeAction(action: JSONObject) {
        val type = action.optString("type", "none")
        val target = action.optString("target", "")
        val message = action.optString("message", "")

        when (type) {
            "open_whatsapp" -> openWhatsApp(target)
            "open_gallery" -> openGallery()
            "call" -> placeCall(target)
            "send_message" -> sendWhatsAppMessage(target, message)
            else -> { }
        }
    }

    private fun openWhatsApp(target: String) {
        val phone = extractPhoneOrNull(target)
        if (phone != null) {
            openWhatsAppChat(phone, null)
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "WhatsApp isn't installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendWhatsAppMessage(target: String, message: String) {
        val phone = extractPhoneOrNull(target)
        if (phone == null) {
            Toast.makeText(this, "Don't have a phone number for \"$target\" yet — save it as a contact first", Toast.LENGTH_LONG).show()
            return
        }
        openWhatsAppChat(phone, message)
    }

    private fun openWhatsAppChat(phone: String, message: String?) {
        val cleanPhone = phone.replace(Regex("[^0-9+]"), "")
        val uriBuilder = StringBuilder("https://wa.me/$cleanPhone")
        if (!message.isNullOrBlank()) {
            uriBuilder.append("?text=").append(Uri.encode(message))
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriBuilder.toString()))
        startActivity(intent)
    }

    private fun placeCall(target: String) {
        val phone = extractPhoneOrNull(target) ?: target
        val uri = Uri.parse("tel:${Uri.encode(phone)}")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(Intent(Intent.ACTION_CALL, uri))
        } else {
            startActivity(Intent(Intent.ACTION_DIAL, uri))
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivity(intent)
    }

    private fun extractPhoneOrNull(text: String): String? {
        val digitsOnly = text.replace(Regex("[^0-9+]"), "")
        return if (digitsOnly.length >= 7) digitsOnly else null
    }

    override fun onPause() {
        super.onPause()
        if (isListeningModeOn) {
            try {
                speechRecognizer.stopListening()
                speechRecognizer.cancel()
            } catch (e: Exception) { }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isListeningModeOn) {
            startWakeWordListening()
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        speechRecognizer.destroy()
        super.onDestroy()
    }
}
