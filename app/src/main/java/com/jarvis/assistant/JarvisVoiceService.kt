package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class JarvisVoiceService : Service(), TextToSpeech.OnInitListener {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var activated = false
    private var ready = false
    private var recognizerActive = false
    private var speaking = false
    private var thinking = false
    private val handler by lazy { android.os.Handler(mainLooper) }
    private val conversationTimeout = Runnable {
        activated = false
        updateNotification("Listening for “Hey JARVIS”")
    }
    private val prefs by lazy { getSharedPreferences("jarvis_private", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this, this)
        startForeground(NOTIFICATION_ID, notification("Starting JARVIS voice assistant…"))
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(listener)
            listening = true
            listenAgain()
        } else {
            updateNotification("Speech recognition is unavailable on this phone")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                if (!listening) {
                    listening = true
                    activated = false
                    listenAgain()
                }
            }
        }
        return START_STICKY
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            recognizerActive = true
            updateNotification(if (activated) "Listening for your next request" else "Listening for “Hey JARVIS”")
        }
        override fun onBeginningOfSpeech() {
            recognizerActive = true
            if (speaking) {
                tts?.stop()
                speaking = false
            }
            updateNotification("I’m listening…")
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            recognizerActive = false
            updateNotification("Understanding your request…")
        }
        override fun onError(error: Int) {
            recognizerActive = false
            if (!listening || thinking) return
            updateNotification(if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) "Microphone permission is missing" else if (activated) "Ready for your next request" else "Listening for “Hey JARVIS”")
            handler.postDelayed({ listenAgain() }, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200 else 350)
        }
        override fun onResults(results: android.os.Bundle?) {
            recognizerActive = false
            val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (words.isNotBlank()) processSpeech(words) else listenAgain()
        }
        override fun onPartialResults(partialResults: android.os.Bundle?) {
            // Never execute partial hypotheses: wait for a complete utterance.
        }
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }

    private fun listenAgain() {
        if (!listening || thinking || recognizerActive) return
        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
        }
        try {
            recognizer?.startListening(speechIntent)
            recognizerActive = true
        } catch (e: Exception) {
            Log.w(TAG, "Speech recognizer restart failed", e)
            recognizerActive = false
            handler.postDelayed({ listenAgain() }, 900)
        }
    }

    private fun processSpeech(heard: String) {
        val normalized = heard.trim()
        val wakeMatch = Regex("^(?:hey\\s+)?jarvis(?:[,.!?]\\s*|\\s+|$)", RegexOption.IGNORE_CASE).find(normalized)
        if (wakeMatch != null) {
            val command = normalized.substring(wakeMatch.range.last + 1).trim().trimStart(',', '.', '!', '?')
            activated = true
            refreshConversationWindow()
            if (command.isBlank()) {
                say("I’m listening. Go ahead.")
                return
            }
            handleCommand(command)
            return
        }
        if (activated) {
            refreshConversationWindow()
            handleCommand(normalized)
            return
        }
        listenAgain()
    }

    private fun refreshConversationWindow() {
        handler.removeCallbacks(conversationTimeout)
        handler.postDelayed(conversationTimeout, 20_000)
    }

    private fun handleCommand(command: String) {
        val c = command.lowercase(Locale.getDefault()).trim()
        val politeCommand = c.replace(Regex("^(please\\s+|can you\\s+|could you\\s+|would you\\s+)"), "")
        val openMatch = Regex("^(?:open|launch|start|take me to)\\s+(.+)$").find(politeCommand)
        when {
            politeCommand in listOf("stop listening", "go to sleep", "goodbye jarvis", "turn yourself off") -> {
                say("Voice assistant stopped.")
                handler.postDelayed({ stopSelf() }, 900)
            }
            politeCommand.contains("open phone") || politeCommand.contains("unlock phone") -> say("Please unlock your phone using your PIN, password, fingerprint or face. I cannot bypass your screen lock.")
            politeCommand == "settings" || politeCommand == "phone settings" || politeCommand == "open settings" -> launchIntent(Intent(android.provider.Settings.ACTION_SETTINGS), "Opening Settings.")
            openMatch != null -> launchApp(openMatch.groupValues[1].trim())
            politeCommand in listOf("go home", "home screen", "return home", "go to home screen", "press home") -> nav(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME, "home")
            politeCommand in listOf("go back", "press back", "back", "return to previous screen") -> nav(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK, "back")
            politeCommand.contains("recent apps") -> nav(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS, "recent apps")
            politeCommand.contains("lock phone") || politeCommand.contains("lock screen") -> nav(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "lock screen")
            politeCommand.contains("what time") || politeCommand == "time" -> say("The time is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()))
            politeCommand.startsWith("search ") || politeCommand.startsWith("search for ") -> {
                val q = politeCommand.removePrefix("search for ").removePrefix("search ").trim()
                if (q.isBlank()) say("What would you like me to search for?")
                else launchIntent(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", q), "Searching the web for $q.")
            }
            else -> askGroq(command)
        }
    }

    private fun launchApp(name: String) {
        val wanted = name.lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]"), "")
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(launcher, PackageManager.MATCH_ALL).firstOrNull {
            val label = it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]"), "")
            label == wanted || label.contains(wanted)
        }
        if (match == null) { say("$name is not available in the installed apps I can see."); return }
        val launch = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName)
        if (launch == null) { say("Android did not allow me to launch $name."); return }
        say("Opening $name.")
        android.os.Handler(mainLooper).postDelayed({
            try { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch) }
            catch (_: Exception) { say("Android could not open $name.") }
        }, 350)
    }

    private fun launchIntent(intent: Intent, spoken: String) {
        say(spoken)
        android.os.Handler(mainLooper).postDelayed({
            try { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent) }
            catch (_: Exception) { say("Android could not perform that action.") }
        }, 350)
    }

    private fun nav(action: Int, label: String) {
        val ok = JarvisAccessibilityService.perform(action)
        say(if (ok) "Done. $label." else "Enable JARVIS in Android Accessibility settings to use that phone control.")
    }

    private fun askGroq(question: String) {
        val key = prefs.getString("groq_key", "").orEmpty()
        if (key.isBlank()) { say("Please add your Groq key in JARVIS AI Settings."); return }
        thinking = true
        recognizer?.cancel()
        recognizerActive = false
        updateNotification("JARVIS is thinking…")
        thread {
            var answer = "I could not get an answer from Groq."
            try {
                val modelConnection = URL("https://api.groq.com/openai/v1/models").openConnection() as HttpURLConnection
                modelConnection.setRequestProperty("Authorization", "Bearer $key")
                modelConnection.connectTimeout = 12000
                modelConnection.readTimeout = 15000
                val modelCode = modelConnection.responseCode
                val modelRaw = (if (modelCode in 200..299) modelConnection.inputStream else modelConnection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                modelConnection.disconnect()
                if (modelCode !in 200..299) throw IllegalStateException("Groq model lookup failed: HTTP $modelCode")
                val data = JSONObject(modelRaw).optJSONArray("data") ?: JSONArray()
                val available = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }.toSet()
                val model = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen/qwen3-32b").firstOrNull { it in available }
                    ?: throw IllegalStateException("No supported chat model is available to this Groq key.")
                val history = try { JSONArray(prefs.getString("groq_conversation_history", "[]")) } catch (_: Exception) { JSONArray() }
                val systemPrompt = """You are JARVIS, a capable, calm, natural voice-first personal assistant.
Speak like a thoughtful human assistant, not a search result or a scripted bot. Keep spoken answers clear and concise; avoid markdown, long lists and repeated introductions.
Understand everyday conversational English, varied accents and imperfect phrasing. Interpret follow-up questions using the conversation history. Do not make the user repeat context already present. If a request is ambiguous in a way that could cause the wrong action, ask one short clarifying question.
Be useful beyond simple commands: explain concepts, reason through problems, plan tasks, draft messages, teach step by step, brainstorm, and help the user build things. Break complicated tasks into manageable steps and be honest about what you can and cannot do.
Never claim you opened an app, changed a setting, sent a message, saved something or completed an action unless the Android app has actually performed and confirmed it. You cannot bypass the lock screen, secretly monitor people, or override Android permissions. Protect private information and ask before destructive or consequential actions.
When a user corrects you, accept the correction and continue from the corrected meaning without repeating the previous answer. If you do not know something, say so plainly. You are JARVIS; be warm, confident, attentive and never robotic."""
                val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
                for (i in 0 until history.length()) messages.put(history.getJSONObject(i))
                messages.put(JSONObject().put("role", "user").put("content", question))
                val conn = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.connectTimeout = 15000; conn.readTimeout = 30000; conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $key")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(JSONObject().put("model", model).put("messages", messages).put("max_tokens", 400).toString().toByteArray()) }
                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                if (code !in 200..299) throw IllegalStateException("Groq returned HTTP $code.")
                val msg = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                answer = msg?.optString("content")?.trim().orEmpty().ifBlank { "I don't have a response yet." }
                history.put(JSONObject().put("role", "user").put("content", question))
                history.put(JSONObject().put("role", "assistant").put("content", answer))
                while (history.length() > 12) history.remove(0)
                prefs.edit().putString("groq_conversation_history", history.toString()).apply()
            } catch (e: Exception) { answer = e.message ?: "I could not reach Groq." }
            val spoken = answer.take(1200)
            handler.post {
                thinking = false
                say(spoken)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.UK)
            ready = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) { speaking = true }
                override fun onDone(utteranceId: String) {
                    handler.post {
                        speaking = false
                        if (listening && !thinking) listenAgain()
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    handler.post {
                        speaking = false
                        if (listening && !thinking) listenAgain()
                    }
                }
            })
        }
    }

    private fun say(text: String) {
        updateNotification(text.take(100))
        if (ready) {
            speaking = true
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${System.currentTimeMillis()}")
            // Keep recognition active during playback to support voice interruption.
            // Echo-cancellation quality depends on the Android speech-recognition provider.
            handler.postDelayed({ if (listening && !thinking && !recognizerActive) listenAgain() }, 250)
        } else {
            speaking = false
            listenAgain()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "JARVIS voice assistant", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shows when JARVIS is listening in the background"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, JarvisVoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("JARVIS is active").setContentText(message)
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    override fun onDestroy() {
        listening = false
        speaking = false
        thinking = false
        handler.removeCallbacks(conversationTimeout)
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.jarvis.assistant.START_VOICE"
        const val ACTION_STOP = "com.jarvis.assistant.STOP_VOICE"
        private const val CHANNEL_ID = "jarvis_voice"
        private const val NOTIFICATION_ID = 7412
        private const val TAG = "JarvisVoiceService"
    }
}
