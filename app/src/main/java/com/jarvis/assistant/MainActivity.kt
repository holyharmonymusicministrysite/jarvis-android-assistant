package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private var listening = false
    private var activated = false
    private var speechReady = false
    private val preferences by lazy { getSharedPreferences("jarvis_private", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 40, 32, 40)
            setBackgroundColor(0xFF07111F.toInt())
        }
        root.addView(TextView(this).apply {
            text = "J.A.R.V.I.S"; textSize = 34f; gravity = Gravity.CENTER
            setTextColor(0xFF55D6FF.toInt())
        })
        status = TextView(this).apply {
            text = "Ready when you are."; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(0xFFEAF7FF.toInt()); setPadding(0, 28, 0, 24)
        }
        transcript = TextView(this).apply {
            text = "Press START, then say Hey JARVIS."; textSize = 16f
            gravity = Gravity.CENTER; setTextColor(0xFFB9C9D8.toInt())
            setPadding(0, 16, 0, 32)
        }
        root.addView(status); root.addView(transcript)
        root.addView(Button(this).apply { text = "START LISTENING"; setOnClickListener { startListening() } })
        root.addView(Button(this).apply { text = "STOP LISTENING"; setOnClickListener { stopListening() } })
        root.addView(Button(this).apply { text = "AI SETTINGS"; setOnClickListener { showAiSettings() } })
        setContentView(root)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
    }

    private fun showAiSettings() {
        val field = EditText(this).apply {
            hint = "Paste your Google AI Studio API key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(preferences.getString("gemini_key", "").orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle("Connect JARVIS AI")
            .setMessage("Add your Google AI Studio API key to enable general questions and natural conversation. Basic phone commands work without it.")
            .setView(field)
            .setPositiveButton("SAVE KEY") { _, _ ->
                val key = field.text.toString().trim()
                if (key.isBlank()) {
                    preferences.edit().remove("gemini_key").apply()
                    Toast.makeText(this, "AI key removed.", Toast.LENGTH_SHORT).show()
                } else {
                    preferences.edit().putString("gemini_key", key).apply()
                    Toast.makeText(this, "AI key saved on this device.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            val languageResult = tts?.setLanguage(Locale.UK)
            speechReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                languageResult != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(utteranceId: String) {
                    runOnUiThread {
                        if (listening) {
                            status.text = if (activated) "Listening for your next request…" else "Listening… say Hey JARVIS."
                            listenAgain()
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    runOnUiThread { if (listening) listenAgain() }
                }
            })
        }
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            status.text = "Allow microphone access, then press START LISTENING."
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Speech recognition is unavailable. Enable Google's speech service on your phone."
            return
        }
        listening = true
        activated = false
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    status.text = if (activated) "JARVIS is listening. Speak now." else "Listening… say Hey JARVIS."
                }
                override fun onBeginningOfSpeech() { status.text = "I can hear you…" }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "Understanding…" }
                override fun onError(error: Int) {
                    if (!listening) return
                    status.text = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Microphone error. Check microphone access."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is blocked."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service needs an internet connection."
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Try speaking clearly."
                        else -> "Restarting voice listening…"
                    }
                    status.postDelayed({ listenAgain() }, 900)
                }
                override fun onResults(results: Bundle?) {
                    val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (words.isNotBlank()) {
                        transcript.text = "You said: $words"
                        val normalized = words.lowercase(Locale.getDefault())
                        val wake = normalized.contains("hey jarvis") || normalized == "jarvis"
                        if (!activated && wake) {
                            activated = true
                            val remainder = normalized.replace("hey jarvis", "").replace("jarvis", "").trim()
                            if (remainder.isNotBlank()) handleCommand(remainder)
                            else speak("I'm listening. What do you need?")
                            return
                        } else if (activated && words.isNotBlank()) {
                            handleCommand(words)
                            return
                        }
                    }
                    if (listening) status.postDelayed({ listenAgain() }, 500)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        status.text = "Starting microphone…"
        listenAgain()
    }

    private fun listenAgain() {
        if (!listening || isFinishing) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            status.text = "Could not start listening. Please press START again."
            status.postDelayed({ if (listening) listenAgain() }, 1200)
        }
    }

    private fun handleCommand(command: String) {
        val c = command.lowercase(Locale.getDefault()).trim()
        when {
            c == "stop listening" || c == "go to sleep" || c == "goodbye jarvis" -> {
                stopListening()
                speak("Voice listening stopped.")
            }
            "time" in c || "what time" in c -> speak("The time is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()))
            "open youtube" in c || c == "youtube" -> openApp("com.google.android.youtube", "YouTube")
            "open whatsapp" in c || c == "whatsapp" -> openApp("com.whatsapp", "WhatsApp")
            "open settings" in c || c == "settings" -> {
                speak("Opening settings.")
                status.postDelayed({ try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) {} }, 500)
            }
            c in listOf("go home", "home screen", "press home", "return home") -> {
                val done = JarvisAccessibilityService.perform(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                speak(if (done) "Returning to the home screen." else "Please enable JARVIS Accessibility in Android Accessibility settings first.")
            }
            c in listOf("go back", "press back", "back") -> {
                val done = JarvisAccessibilityService.perform(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                speak(if (done) "Going back." else "Please enable JARVIS Accessibility in Android Accessibility settings first.")
            }
            c in listOf("show recent apps", "open recent apps", "recent apps") -> {
                val done = JarvisAccessibilityService.perform(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                speak(if (done) "Showing recent apps." else "Please enable JARVIS Accessibility in Android Accessibility settings first.")
            }
            c in listOf("lock phone", "lock screen", "lock my phone") -> {
                val done = JarvisAccessibilityService.perform(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                speak(if (done) "Locking the phone." else "Please enable JARVIS Accessibility in Android Accessibility settings first.")
            }
            c in listOf("enable phone control", "accessibility settings", "set up phone control") -> {
                speak("Opening Accessibility settings. Find JARVIS and switch it on only if you agree.")
                status.postDelayed({ try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {} }, 500)
            }
            c.startsWith("search ") || c.startsWith("search for ") -> {
                val q = c.removePrefix("search for ").removePrefix("search ").trim()
                if (q.isBlank()) speak("What should I search for?") else {
                    speak("Searching the web for $q.")
                    status.postDelayed({
                        try { startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", q)) }
                        catch (_: Exception) { speak("I couldn't open web search.") }
                    }, 500)
                }
            }
            else -> askAi(command)
        }
    }

    private fun askAi(question: String) {
        val key = preferences.getString("gemini_key", null)
        if (key.isNullOrBlank()) {
            speak("I heard you. To answer questions and have a conversation, add your Google AI Studio key in AI Settings. Basic phone commands are available now.")
            return
        }
        status.text = "JARVIS is thinking…"
        thread {
            var answer = ""
            var failure = ""
            var connection: HttpURLConnection? = null
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("x-goog-api-key", key)
                }
                val body = JSONObject()
                    .put("contents", org.json.JSONArray().put(JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", question)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", 500))
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) {
                    val json = JSONObject(response)
                    answer = json.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                        ?.optString("text").orEmpty()
                    if (answer.isBlank()) failure = "The AI returned an empty response."
                } else {
                    failure = when (code) {
                        400, 403 -> "The AI key was rejected. Check your key in AI Settings."
                        429 -> "The free AI limit has been reached. Try again later."
                        else -> "The AI service is temporarily unavailable. Please try again."
                    }
                }
            } catch (_: Exception) {
                failure = "I could not reach the AI service. Check your internet connection."
            } finally {
                connection?.disconnect()
            }
            runOnUiThread {
                if (answer.isNotBlank()) speak(answer)
                else speak(failure.ifBlank { "I could not prepare an answer." })
            }
        }
    }

    private fun openApp(pkg: String, label: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            speak("Opening $label.")
            status.postDelayed({ try { startActivity(launch) } catch (_: Exception) {} }, 500)
        } else speak("$label is not installed.")
    }

    private fun speak(message: String) {
        status.text = message
        if (speechReady && tts != null) {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${System.currentTimeMillis()}")
        } else if (listening) {
            status.postDelayed({ listenAgain() }, 700)
        }
    }

    private fun stopListening() {
        listening = false
        recognizer?.cancel()
        status.text = "Listening stopped."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            status.text = "Microphone allowed. Press START LISTENING."
        } else if (requestCode == 10) {
            status.text = "Microphone permission is required for voice control."
        }
    }

    override fun onPause() {
        stopListening()
        super.onPause()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
