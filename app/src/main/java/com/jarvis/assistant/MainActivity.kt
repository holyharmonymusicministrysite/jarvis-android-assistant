package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private var listening = false
    private var activated = false

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
        setContentView(root)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
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
                    if (listening) status.postDelayed({ listenAgain() }, 900)
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
            "hello" in c || "hi" in c -> speak("Hello. Good to hear from you.")
            else -> speak("I heard you say $command. I am still learning that command.")
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
