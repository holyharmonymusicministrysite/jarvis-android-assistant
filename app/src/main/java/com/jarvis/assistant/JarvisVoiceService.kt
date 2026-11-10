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
    private var currentSpeechText = ""
    private var interruptionDetected = false
    private val handler by lazy { android.os.Handler(mainLooper) }
    private val conversationTimeout = Runnable {
        activated = false
        updateNotification("Listening for “Hey SAM”")
    }
    private val prefs by lazy { getSharedPreferences("jarvis_private", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this, this)
        startForeground(NOTIFICATION_ID, notification("Starting SAM voice assistant…"))
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
            updateNotification(if (activated) "Listening for your next request" else "Listening for “Hey SAM”")
        }
        override fun onBeginningOfSpeech() {
            recognizerActive = true
            // Do not stop JARVIS just because the recognizer heard its own speaker output.
            // A genuinely different partial phrase below is treated as a user interruption.
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
            if (words.isBlank()) { listenAgain(); return }
            if (speaking && !interruptionDetected && isOwnSpeechEcho(words)) {
                // Ignore speech-to-text that merely repeats JARVIS's current spoken answer.
                handler.postDelayed({ listenAgain() }, 250)
                return
            }
            if (speaking) {
                tts?.stop()
                speaking = false
            }
            processSpeech(words)
        }
        override fun onPartialResults(partialResults: android.os.Bundle?) {
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (speaking && partial.isNotBlank() && !isOwnSpeechEcho(partial)) {
                interruptionDetected = true
                tts?.stop()
                speaking = false
                updateNotification("JARVIS heard your interruption…")
            }
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
        val wakeMatch = Regex("^(?:hey\\s+)?sam(?:[,.!?]\\s*|\\s+|$)", RegexOption.IGNORE_CASE).find(normalized)
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
        if (key.isBlank()) {
            thinking = false
            say("Please add your Groq API key in JARVIS AI Settings before I can use the AI brain.")
            return
        }
        thinking = true
        recognizer?.cancel()
        recognizerActive = false
        updateNotification("JARVIS is thinking…")
        thread {
            var answer = ""
            var failure = ""
            var connection: HttpURLConnection? = null
            try {
                val modelConnection = (URL("https://api.groq.com/openai/v1/models").openConnection() as HttpURLConnection)
                modelConnection.requestMethod = "GET"
                modelConnection.connectTimeout = 15000
                modelConnection.readTimeout = 20000
                modelConnection.setRequestProperty("Authorization", "Bearer $key")
                modelConnection.setRequestProperty("Accept", "application/json")
                val modelCode = modelConnection.responseCode
                val modelRaw = (if (modelCode in 200..299) modelConnection.inputStream else modelConnection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                modelConnection.disconnect()
                if (modelCode !in 200..299) throw IllegalStateException("Groq model lookup failed: HTTP $modelCode")
                val availableData = JSONObject(modelRaw).optJSONArray("data") ?: JSONArray()
                val available = (0 until availableData.length()).mapNotNull {
                    availableData.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() }
                }.toSet()
                val model = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.3-70b-versatile",
                    "llama-3.1-8b-instant", "qwen/qwen3-32b").firstOrNull { it in available }
                    ?: throw IllegalStateException("No supported chat model is available to this Groq key.")

                val history = try { JSONArray(prefs.getString("groq_conversation_history", "[]")) }
                    catch (_: Exception) { JSONArray() }
                history.put(JSONObject().put("role", "user").put("content", question))
                while (history.length() > 20) history.remove(0)

                val tools = JSONArray()
                tools.put(voiceTool("open_app", "Open an installed Android application by its visible name.",
                    "app_name" to voiceString("Name of the installed app")))
                tools.put(voiceTool("phone_navigation", "Perform a phone navigation action explicitly requested by the user.",
                    "action" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("home", "back", "recents", "lock_screen")))))
                tools.put(voiceTool("web_search", "Open Android web search for a query. This opens a search page; it does not claim to read search results.",
                    "query" to voiceString("What to search for")))
                tools.put(voiceTool("get_time", "Read the current local phone time."))
                tools.put(voiceTool("save_note", "Save a note locally on this phone.",
                    "title" to voiceString("Short note title"), "content" to voiceString("Note body")))
                tools.put(voiceTool("list_notes", "List titles of notes saved by JARVIS."))
                tools.put(voiceTool("read_screen", "Read visible screen text only because the user explicitly asked what is on the screen or asked you to read it."))
                tools.put(voiceTool("read_notifications", "Read recent notifications only because the user explicitly asked to hear or check notifications.",
                    "limit" to JSONObject().put("type", "integer").put("description", "Number of recent notifications, 1 to 10")))
                tools.put(voiceTool("open_notification_settings", "Open Android notification access settings so the user can optionally grant JARVIS access."))

                val systemPrompt = """You are JARVIS, a capable, thoughtful, natural voice-first personal AI assistant.
You are the reasoning and planning brain, not a scripted command bot. Understand ordinary conversational English, imperfect grammar, follow-up questions, context and corrections. Think through complex requests, explain clearly, teach, plan, draft, troubleshoot and help the user build software.
Use available tools when the user asks you to operate the phone or retrieve phone information. Do not merely tell the user how if an available tool can perform the action. You may chain multiple safe tools, then explain what actually happened.
Available tools: open installed apps; Home, Back, Recents and Lock Screen; open a web search; read local time; save/list notes; read visible screen text on explicit request; read recent notifications on explicit request; open notification-access settings.
Screen and notification content is private. Never read it unless the user's current request clearly asks for it. Do not transmit screen or notification text to any external service except the current AI request that the user initiated to answer that specific question. Do not infer passwords or expose one-time codes unnecessarily.
Ask before destructive, financial, message-sending or other consequential actions. Do not claim to send messages, make payments, unlock the phone, shut down Android, change protected settings, or control every third-party app; those actions are not available.
The web_search tool opens a search page; it does not provide you with the search results. Be honest about that distinction.
Never claim an action succeeded unless the tool result confirms it. If a permission is missing, tell the user exactly which Android setting they need to enable.
Be conversational and concise when speaking aloud. Do not repeat greetings, do not narrate internal thinking, and accept corrections immediately. If you cannot do something, say so and offer the closest safe next step."""

                var round = 0
                while (round < 5) {
                    val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
                    for (i in 0 until history.length()) messages.put(history.getJSONObject(i))
                    connection = (URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 20000
                        readTimeout = 45000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Authorization", "Bearer $key")
                    }
                    val payload = JSONObject().put("model", model).put("messages", messages)
                        .put("tools", tools).put("tool_choice", "auto")
                        .put("temperature", 0.25).put("max_tokens", 900)
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    connection.disconnect()
                    connection = null
                    if (code !in 200..299) {
                        val providerMessage = try { JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty() }
                            catch (_: Exception) { "" }
                        failure = when (code) {
                            401, 403 -> "Groq rejected the API key ($code). Check the key in JARVIS AI Settings."
                            404 -> "Groq could not find the selected model or endpoint. " + providerMessage.take(140)
                            429 -> "The free Groq quota or rate limit has been reached. Wait and try again."
                            500, 502, 503, 504 -> "Groq AI is temporarily unavailable ($code). Try again shortly."
                            else -> "Groq request failed with HTTP $code. " + providerMessage.take(140)
                        }
                        break
                    }
                    val message = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    if (message == null) { failure = "Groq returned no usable response."; break }
                    history.put(message)
                    val calls = message.optJSONArray("tool_calls")
                    if (calls == null || calls.length() == 0) {
                        answer = message.optString("content").trim()
                        break
                    }
                    for (i in 0 until calls.length()) {
                        val call = calls.optJSONObject(i) ?: continue
                        val function = call.optJSONObject("function") ?: continue
                        val args = try { JSONObject(function.optString("arguments", "{}")) } catch (_: Exception) { JSONObject() }
                        val result = executeVoiceToolOnMain(function.optString("name"), args)
                        history.put(JSONObject().put("role", "tool")
                            .put("tool_call_id", call.optString("id")).put("content", result))
                    }
                    round++
                }
                while (history.length() > 20) history.remove(0)
                prefs.edit().putString("groq_conversation_history", history.toString()).apply()
                if (answer.isBlank() && failure.isBlank()) failure = "I could not finish that sequence. Please try again."
            } catch (e: Exception) {
                failure = when {
                    e.message?.contains("401") == true -> "Groq rejected the API key. Check JARVIS AI Settings."
                    e.message?.contains("429") == true -> "The free Groq limit has been reached. Please try again later."
                    else -> "I could not connect to Groq. Check your internet connection and API key. " + (e.message ?: "").take(100)
                }
            } finally {
                connection?.disconnect()
            }
            handler.post {
                thinking = false
                say(if (answer.isNotBlank()) answer else failure)
            }
        }
    }

    private fun voiceString(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun voiceTool(name: String, description: String, vararg fields: Pair<String, JSONObject>): JSONObject {
        val properties = JSONObject()
        fields.forEach { properties.put(it.first, it.second) }
        val parameters = JSONObject().put("type", "object").put("properties", properties)
            .put("additionalProperties", false)
        if (fields.isNotEmpty()) parameters.put("required", JSONArray(fields.map { it.first }))
        return JSONObject().put("type", "function").put("function",
            JSONObject().put("name", name).put("description", description).put("parameters", parameters))
    }

    private fun executeVoiceToolOnMain(name: String, args: JSONObject): String {
        val result = java.util.concurrent.atomic.AtomicReference("Android did not confirm the action.")
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try { result.set(executeVoiceTool(name, args)) }
            catch (e: Exception) { result.set("Android reported an error: " + (e.message ?: "unknown error").take(120)) }
            finally { latch.countDown() }
        }
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        return result.get()
    }

    private fun executeVoiceTool(name: String, args: JSONObject): String = when (name) {
        "open_app" -> {
            val requested = args.optString("app_name").trim()
            val wanted = requested.lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]"), "")
            if (wanted.isBlank()) "No app name was provided."
            else {
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val match = packageManager.queryIntentActivities(launcher, PackageManager.MATCH_ALL).firstOrNull {
                    val label = it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]"), "")
                    label == wanted || label.contains(wanted) || wanted.contains(label)
                }
                val launch = match?.let { packageManager.getLaunchIntentForPackage(it.activityInfo.packageName) }
                if (launch == null) "I could not find or launch $requested."
                else try {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                    "Android launched " + match.loadLabel(packageManager).toString() + "."
                } catch (_: Exception) { "Android could not launch $requested." }
            }
        }
        "phone_navigation" -> {
            val action = args.optString("action")
            val id = when (action) {
                "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                "lock_screen" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                else -> -1
            }
            if (id < 0) "That phone action is not supported."
            else if (JarvisAccessibilityService.perform(id)) "Android accepted the $action action."
            else "Enable JARVIS in Android Accessibility settings first."
        }
        "web_search" -> {
            val query = args.optString("query").trim()
            if (query.isBlank()) "No search query was provided."
            else try {
                val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                "Android opened a web search for $query."
            } catch (_: Exception) { "Android could not open web search." }
        }
        "get_time" -> "The current phone time is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        "save_note" -> {
            val title = args.optString("title").trim().take(80)
            val body = args.optString("content").trim().take(12000)
            if (title.isBlank() || body.isBlank()) "A note needs a title and content."
            else {
                val notes = try { JSONArray(prefs.getString("jarvis_notes", "[]")) } catch (_: Exception) { JSONArray() }
                notes.put(JSONObject().put("title", title).put("content", body).put("saved_at", System.currentTimeMillis()))
                while (notes.length() > 50) notes.remove(0)
                prefs.edit().putString("jarvis_notes", notes.toString()).apply()
                "Saved the note '$title' on this phone."
            }
        }
        "list_notes" -> {
            val notes = try { JSONArray(prefs.getString("jarvis_notes", "[]")) } catch (_: Exception) { JSONArray() }
            if (notes.length() == 0) "There are no saved notes."
            else (0 until notes.length()).joinToString("; ") { i -> notes.optJSONObject(i)?.optString("title").orEmpty() }
        }
        "read_screen" -> JarvisAccessibilityService.readScreen()
        "read_notifications" -> {
            val limit = args.optInt("limit", 5).coerceIn(1, 10)
            JarvisNotificationListener.readRecent(this, limit)
        }
        "open_notification_settings" -> try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened Android notification access settings. Enable JARVIS there only if you want it to read notifications."
        } catch (_: Exception) { "Android could not open notification access settings." }
        else -> "That tool is not available."
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

    private fun isOwnSpeechEcho(candidate: String): Boolean {
        val heard = candidate.lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+")).filter { it.length > 1 }
        val spoken = currentSpeechText.lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        if (heard.isEmpty() || spoken.isEmpty()) return false
        val overlap = heard.count { it in spoken }.toFloat() / heard.size.toFloat()
        return overlap >= 0.60f
    }

    private fun say(text: String) {
        currentSpeechText = text
        interruptionDetected = false
        updateNotification(text.take(100))
        if (ready) {
            speaking = true
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${System.currentTimeMillis()}")
            // Keep recognition active during playback. Matching recognition of JARVIS's
            // own output is ignored; a distinct partial phrase stops speech for barge-in.
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
