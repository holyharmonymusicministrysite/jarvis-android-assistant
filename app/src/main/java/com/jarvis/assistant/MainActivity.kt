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
            .setMessage("Create a key in Google AI Studio, then save it here. The key is stored privately on this phone and is not included in the GitHub source.")
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

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { status.text = "Speech recognition unavailable."; return }
        listening = true; activated = false
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = if (activated) "JARVIS is listening." else "Listening… say Hey JARVIS." }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "Processing…" }
                override fun onError(error: Int) { if (listening) status.postDelayed({ listenAgain() }, 700) }
                override fun onResults(results: Bundle?) {
                    val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    transcript.text = words
                    if (words.contains("hey jarvis", true) || words.equals("jarvis", true)) {
                        activated = true; speak("Yes. How can I help?")
                    } else if (activated && words.isNotBlank()) handleCommand(words)
                    if (listening) status.postDelayed({ listenAgain() }, 1200)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        listenAgain()
    }

    private fun listenAgain() {
        if (!listening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        try { recognizer?.startListening(intent) } catch (_: Exception) { status.text = "Could not start listening." }
    }

    private fun handleCommand(command: String) {
        val c = command.lowercase(Locale.getDefault())
        when {
            "time" in c -> speak("The time is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()))
            "open youtube" in c -> openApp("com.google.android.youtube", "YouTube")
            "open whatsapp" in c -> openApp("com.whatsapp", "WhatsApp")
            "open settings" in c -> startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            "search" in c -> {
                val q = c.substringAfter("search").trim()
                if (q.isBlank()) speak("What should I search for?") else {
                    startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", q)); speak("Searching for $q")
                }
            }
            "hello" in c || "hi" in c -> askAi(command)
            else -> askAi(command)
        }
    }

    private fun askAi(question: String) {
        val key = preferences.getString("gemini_key", null)
        if (key.isNullOrBlank()) {
            speak("I can handle phone commands. To answer general questions, connect a free Google AI Studio key in AI Settings.")
            return
        }
        status.text = "JARVIS is thinking…"
        thread {
            var answer = ""
            var failure = ""
            var connection: HttpURLConnection? = null
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + android.net.Uri.encode(key)
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val body = JSONObject()
                    .put("contents", org.json.JSONArray().put(JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", question)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", 500))
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream.bufferedReader().use { it.readText() }
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
        if (launch != null) startActivity(launch) else speak("$label is not installed.")
    }
    private fun speak(message: String) {
        status.text = message
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }
    private fun stopListening() {
        listening = false; recognizer?.cancel(); status.text = "Listening stopped."
    }
    override fun onInit(result: Int) { if (result == TextToSpeech.SUCCESS) tts?.language = Locale.UK }
    override fun onPause() { stopListening(); super.onPause() }
    override fun onDestroy() { recognizer?.destroy(); tts?.shutdown(); super.onDestroy() }
}
