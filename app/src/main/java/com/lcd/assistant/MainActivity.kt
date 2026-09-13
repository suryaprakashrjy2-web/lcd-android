package com.lcd.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
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

/**
 * LCD - tap-to-talk personal voice assistant.
 *
 * Flow:
 *   1. User taps the button -> Android's built-in speech recognizer listens (Telugu)
 *   2. Recognized text is sent to the LCD backend's /chat endpoint
 *   3. Backend replies with { reply, action }
 *   4. This activity speaks "reply" out loud (Telugu TTS) and executes "action"
 *      (open WhatsApp, call, send a WhatsApp message, open gallery)
 */
class MainActivity : AppCompatActivity() {

    // ---------------------------------------------------------------
    // IMPORTANT: this is your live Render backend URL. If you ever
    // redeploy under a different URL, update it here.
    // ---------------------------------------------------------------
    private val BACKEND_URL = "https://lcd-backend.onrender.com/chat"

    private val TELUGU_LOCALE = Locale("te", "IN")

    private lateinit var statusText: TextView
    private lateinit var heardText: TextView
    private lateinit var replyText: TextView
    private lateinit var talkButton: Button

    private lateinit var tts: TextToSpeech
    private val httpClient = OkHttpClient()

    private val speechLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val spokenText = results?.firstOrNull()
                if (!spokenText.isNullOrBlank()) {
                    heardText.text = "You said: $spokenText"
                    sendToBackend(spokenText)
                } else {
                    statusText.text = "Didn't catch that — tap and try again"
                }
            } else {
                statusText.text = "Tap the button and speak"
            }
        }

    private val permissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { /* no-op, we check again on use */ }

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

        // Ask for the permissions we need up front (mic + calling).
        permissionLauncher.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE)
        )

        talkButton.setOnClickListener { startListening() }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }

        statusText.text = "Listening..."
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "te-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now (Telugu or English)")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_LONG).show()
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
                }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val bodyStr = response.body?.string()
                if (bodyStr == null) {
                    runOnUiThread { statusText.text = "Empty response from server" }
                    return
                }
                try {
                    val obj = JSONObject(bodyStr)
                    val reply = obj.optString("reply", "")
                    val action = obj.optJSONObject("action")

                    runOnUiThread {
                        replyText.text = reply
                        statusText.text = "Tap the button and speak"
                        if (reply.isNotBlank()) speak(reply)
                        if (action != null) executeAction(action)
                    }
                } catch (e: Exception) {
                    runOnUiThread { statusText.text = "Couldn't understand server response" }
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
            else -> { /* nothing to do */ }
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
            // No call permission granted - fall back to opening the dialer
            // pre-filled, so the user just taps the call button themselves.
            startActivity(Intent(Intent.ACTION_DIAL, uri))
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivity(intent)
    }

    /** Very small helper: returns the string as-is if it looks like a phone number, else null. */
    private fun extractPhoneOrNull(text: String): String? {
        val digitsOnly = text.replace(Regex("[^0-9+]"), "")
        return if (digitsOnly.length >= 7) digitsOnly else null
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
