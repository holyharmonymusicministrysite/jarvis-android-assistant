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
        root.addView(Button(this).apply {
            text = "PHONE CONTROL SETUP"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Enable JARVIS phone controls")
                    .setMessage("Android requires you to enable this service yourself. It can perform only the navigation actions you request: Home, Back, Recent Apps and Lock Screen. It does not read screen content. Continue to Accessibility settings?")
                    .setPositiveButton("OPEN SETTINGS") { _, _ ->
                        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            }
        })
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
        val requestedApp = Regex("^(?:please\\s+)?(?:open|launch|start|take me to)\\s+(.+)$")
            .find(c)?.groupValues?.get(1)?.trim()
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
            requestedApp != null && requestedApp !in listOf("settings", "phone settings") -> {
                openInstalledApp(requestedApp)
            }
            c.startsWith("open ") && c.removePrefix("open ").trim().isNotBlank() -> {
                val appName = c.removePrefix("open ").trim()
                if (appName in listOf("settings", "phone settings")) {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                } else {
                    openInstalledApp(appName)
                }
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
            speak("Connect your Google AI Studio key in AI Settings to use natural conversation and intelligent actions.")
            return
        }
        status.text = "JARVIS is thinking…"
        thread {
            var answer = ""
            var failure = ""
            var connection: HttpURLConnection? = null
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"
                val history = try { org.json.JSONArray(preferences.getString("conversation_history", "[]")) }
                    catch (_: Exception) { org.json.JSONArray() }
                history.put(JSONObject().put("role", "user").put("parts",
                    org.json.JSONArray().put(JSONObject().put("text", question))))
                while (history.length() > 12) history.remove(0)

                val declarations = org.json.JSONArray()
                declarations.put(toolDeclaration("open_app", "Open an installed Android app by name.",
                    "app_name" to toolString("Installed app name")))
                val navigationActionSchema = JSONObject()
                    .put("type", "STRING")
                    .put("enum", org.json.JSONArray(listOf("home", "back", "recents", "lock_screen")))
                declarations.put(toolDeclaration(
                    "phone_navigation",
                    "Perform a clearly requested phone navigation action.",
                    "action" to navigationActionSchema
                ))
                declarations.put(toolDeclaration("web_search", "Open web search for a query.",
                    "query" to toolString("Search query")))
                declarations.put(toolDeclaration("get_time", "Get the current local device time."))
                declarations.put(toolDeclaration("save_note", "Save a note locally on this phone.",
                    "title" to toolString("Note title"),
                    "content" to toolString("Note content")))
                declarations.put(toolDeclaration("list_notes", "List locally saved note titles."))
                val tools = org.json.JSONArray().put(JSONObject().put("functionDeclarations", declarations))
                val instructions = "You are JARVIS, a capable and honest personal assistant. Understand natural language. Use the available functions to perform actions rather than merely explaining how. After each action, report its actual result. Never claim success unless Android confirms it. Ask before ambiguous or consequential actions. Available actions: open installed apps, Home, Back, Recents, Lock Screen, web search, read time, save and list notes. You cannot power off Android, control every third-party app, send messages or make payments. You cannot build and install software on this phone without a connected build environment. Explain limitations honestly. Be concise and conversational."
                var round = 0
                while (round < 4) {
                    connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = 30000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("x-goog-api-key", key)
                    }
                    val payload = JSONObject()
                        .put("systemInstruction", JSONObject().put("parts",
                            org.json.JSONArray().put(JSONObject().put("text", instructions))))
                        .put("tools", tools)
                        .put("contents", history)
                        .put("generationConfig", JSONObject().put("temperature", 0.35).put("maxOutputTokens", 800))
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    connection.disconnect()
                    connection = null
                    if (code !in 200..299) {
                        failure = when (code) {
                            400, 403 -> "The AI key was rejected. Check AI Settings."
                            429 -> "The AI free usage limit has been reached. Try again later."
                            else -> "The AI service is temporarily unavailable."
                        }
                        break
                    }
                    val modelContent = JSONObject(raw).optJSONArray("candidates")
                        ?.optJSONObject(0)?.optJSONObject("content")
                    val parts = modelContent?.optJSONArray("parts")
                    if (modelContent == null || parts == null) {
                        failure = "The AI returned no usable response."
                        break
                    }
                    history.put(modelContent)
                    val responses = org.json.JSONArray()
                    var textAnswer = ""
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        if (part.has("text")) textAnswer += part.optString("text")
                        val call = part.optJSONObject("functionCall") ?: continue
                        val result = executeAiToolOnUiThread(call.optString("name"), call.optJSONObject("args") ?: JSONObject())
                        responses.put(JSONObject().put("functionResponse", JSONObject()
                            .put("name", call.optString("name"))
                            .put("response", JSONObject().put("result", result))))
                    }
                    if (responses.length() == 0) {
                        answer = textAnswer.trim()
                        break
                    }
                    history.put(JSONObject().put("role", "user").put("parts", responses))
                    round++
                }
                while (history.length() > 12) history.remove(0)
                preferences.edit().putString("conversation_history", history.toString()).apply()
                if (answer.isBlank() && failure.isBlank()) failure = "I could not finish the action sequence. Please try again."
            } catch (_: Exception) {
                failure = "I could not reach the AI service. Check your internet connection."
            } finally {
                connection?.disconnect()
            }
            runOnUiThread { speak(if (answer.isNotBlank()) answer else failure) }
        }
    }

    private fun toolString(description: String): JSONObject =
        JSONObject().put("type", "STRING").put("description", description)

    private fun toolDeclaration(name: String, description: String, vararg fields: Pair<String, JSONObject>): JSONObject {
        val properties = JSONObject()
        fields.forEach { properties.put(it.first, it.second) }
        val parameters = JSONObject().put("type", "OBJECT").put("properties", properties)
        if (fields.isNotEmpty()) parameters.put("required", org.json.JSONArray(fields.map { it.first }))
        return JSONObject().put("name", name).put("description", description).put("parameters", parameters)
    }

    private fun executeAiToolOnUiThread(name: String, args: JSONObject): String {
        val result = java.util.concurrent.atomic.AtomicReference("Action did not complete.")
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            try { result.set(executeAiTool(name, args)) }
            catch (_: Exception) { result.set("Android reported that the action failed.") }
            finally { latch.countDown() }
        }
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        return result.get()
    }

    private fun executeAiTool(name: String, args: JSONObject): String = when (name) {
        "open_app" -> openInstalledAppForAi(args.optString("app_name"))
        "phone_navigation" -> {
            val action = args.optString("action")
            val id = when (action) {
                "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                "lock_screen" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                else -> return "Unsupported phone action."
            }
            if (JarvisAccessibilityService.perform(id)) "Android accepted the " + action + " action."
            else "Enable JARVIS in Android Accessibility settings first."
        }
        "web_search" -> {
            val query = args.optString("query").trim()
            if (query.isBlank()) "No search query was provided."
            else try {
                startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", query))
                "Android opened web search for " + query
            } catch (_: Exception) { "Android could not open web search." }
        }
        "get_time" -> "The current time is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        "save_note" -> {
            val title = args.optString("title").trim().take(80)
            val content = args.optString("content").trim().take(12000)
            if (title.isBlank() || content.isBlank()) "A note needs a title and content."
            else {
                val notes = try { org.json.JSONArray(preferences.getString("jarvis_notes", "[]")) }
                    catch (_: Exception) { org.json.JSONArray() }
                notes.put(JSONObject().put("title", title).put("content", content).put("saved_at", System.currentTimeMillis()))
                while (notes.length() > 50) notes.remove(0)
                preferences.edit().putString("jarvis_notes", notes.toString()).apply()
                "Saved note: " + title
            }
        }
        "list_notes" -> {
            val notes = try { org.json.JSONArray(preferences.getString("jarvis_notes", "[]")) }
                catch (_: Exception) { org.json.JSONArray() }
            if (notes.length() == 0) "There are no saved notes."
            else (0 until notes.length()).joinToString("; ") { i -> notes.optJSONObject(i)?.optString("title").orEmpty() }
        }
        else -> "That action is not available."
    }

    private fun openInstalledAppForAi(requestedName: String): String {
        val wanted = requestedName.lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]"), "")
        if (wanted.isBlank()) return "No app name was supplied."
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(intent, 0).firstOrNull { info ->
            val label = info.loadLabel(packageManager).toString().lowercase(Locale.getDefault())
                .replace(Regex("[^a-z0-9]"), "")
            label == wanted || label.contains(wanted) || wanted.contains(label)
        } ?: return "I could not find an installed app named " + requestedName + "."
        val launch = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "Android did not provide a launch action for " + requestedName + "."
        return try {
            startActivity(launch)
            "Android launched " + match.loadLabel(packageManager).toString() + "."
        } catch (_: Exception) { "Android could not launch " + requestedName + "." }
    }

    private fun openInstalledApp(requestedName: String) {
        val wanted = requestedName.lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]"), "")
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = packageManager.queryIntentActivities(launcherIntent, 0)
        val match = matches.firstOrNull { info ->
            val label = info.loadLabel(packageManager).toString()
                .lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]"), "")
            label == wanted || label.contains(wanted) || wanted.contains(label)
        }
        if (match == null) {
            speak("I couldn't find an installed app called " + requestedName + ".")
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName)
        if (launch == null) {
            speak("I found the app but Android did not provide a launch action.")
            return
        }
        speak("Opening " + match.loadLabel(packageManager) + ".")
        status.postDelayed({
            try { startActivity(launch) }
            catch (_: Exception) { speak("Android could not open that app.") }
        }, 450)
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
