package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
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
            text = "SAM"; textSize = 34f; gravity = Gravity.CENTER
            setTextColor(0xFF55D6FF.toInt())
        })
        status = TextView(this).apply {
            text = "Ready when you are."; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(0xFFEAF7FF.toInt()); setPadding(0, 28, 0, 24)
        }
        transcript = TextView(this).apply {
            text = "Press START, then say Hey SAM."; textSize = 16f
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
                    .setTitle("Enable SAM phone controls")
                    .setMessage("Android requires you to enable this service yourself. It can perform requested navigation actions and read visible screen text only when you explicitly ask SAM to read the screen. It does not continuously collect screen content. Continue to Accessibility settings?")
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
            hint = "Paste your Groq API key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(preferences.getString("groq_key", "").orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle("Connect SAM to Groq")
            .setMessage("Google AI Studio is no longer used. Add a Groq API key to enable natural conversation and AI-assisted actions. Your key stays on this device.")
            .setView(field)
            .setPositiveButton("SAVE KEY") { _, _ ->
                val key = field.text.toString().trim()
                if (key.isBlank()) {
                    preferences.edit().remove("groq_key").apply()
                    Toast.makeText(this, "Groq key removed.", Toast.LENGTH_SHORT).show()
                } else {
                    preferences.edit().putString("groq_key", key).remove("gemini_key").apply()
                    Toast.makeText(this, "Groq key saved on this device.", Toast.LENGTH_SHORT).show()
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
                            status.text = if (activated) "Listening for your next request…" else "Listening… say Hey SAM."
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
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Speech recognition is unavailable. Enable a speech service on your phone."
            return
        }
        val serviceIntent = Intent(this, JarvisVoiceService::class.java).setAction(JarvisVoiceService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            listening = true
            status.text = "SAM is running in the background. You can switch apps and say Hey JARVIS."
            transcript.text = "Background voice assistant active. Use the notification to stop it."
        } catch (e: Exception) {
            status.text = "Android could not start background listening: " + (e.message ?: "permission required")
        }
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
            c == "stop listening" || c == "go to sleep" || c == "goodbye sam" -> {
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
        val key = preferences.getString("groq_key", null)
        if (key.isNullOrBlank()) {
            speak("Connect SAM to Groq in AI Settings. Google AI Studio is no longer required.")
            return
        }
        status.text = "JARVIS is thinking…"
        thread {
            var answer = ""
            var failure = ""
            var connection: HttpURLConnection? = null
            try {
                val endpoint = "https://api.groq.com/openai/v1/chat/completions"
                val model = fetchAvailableGroqModel(key)
                if (model.isBlank()) {
                    failure = "Groq did not list an AI chat model available to this API key. Check the Groq project and model access."
                    throw IllegalStateException(failure)
                }
                val history = try { org.json.JSONArray(preferences.getString("groq_conversation_history", "[]")) }
                    catch (_: Exception) { org.json.JSONArray() }
                history.put(JSONObject().put("role", "user").put("content", question))
                while (history.length() > 16) history.remove(0)

                val tools = org.json.JSONArray()
                tools.put(groqTool("open_app", "Open an installed Android app by name.",
                    "app_name" to groqString("Installed app name")))
                tools.put(groqTool("phone_navigation", "Perform a clearly requested phone navigation action.",
                    "action" to JSONObject().put("type", "string").put("enum", org.json.JSONArray(listOf("home", "back", "recents", "lock_screen")))))
                tools.put(groqTool("web_search", "Open web search for a query.",
                    "query" to groqString("Search query")))
                tools.put(groqTool("get_time", "Get the current local device time."))
                tools.put(groqTool("save_note", "Save a note locally on this phone.",
                    "title" to groqString("Note title"),
                    "content" to groqString("Note content")))
                tools.put(groqTool("list_notes", "List locally saved note titles."))
                val instructions = "You are JARVIS, a capable and honest personal assistant. Understand natural language. Use available functions to perform actions rather than merely explaining how. After each action, report its actual result. Never claim success unless Android confirms it. Ask before ambiguous or consequential actions. Available actions: open installed apps, Home, Back, Recents, Lock Screen, web search, read time, save and list notes. You cannot power off Android, control every third-party app, send messages or make payments. You cannot build and install software on this phone without a connected build environment. Explain limitations honestly. Be concise and conversational."
                var round = 0
                while (round < 4) {
                    val messages = org.json.JSONArray()
                        .put(JSONObject().put("role", "system").put("content", instructions))
                    for (i in 0 until history.length()) messages.put(history.getJSONObject(i))
                    connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 20000
                        readTimeout = 45000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Authorization", "Bearer $key")
                    }
                    val payload = JSONObject()
                        .put("model", model)
                        .put("messages", messages)
                        .put("tools", tools)
                        .put("tool_choice", "auto")
                        .put("temperature", 0.35)
                        .put("max_tokens", 800)
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    connection.disconnect()
                    connection = null
                    if (code !in 200..299) {
                        val providerMessage = try { JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty() }
                            catch (_: Exception) { "" }
                        failure = when (code) {
                            401, 403 -> "Groq rejected the API key ($code). Check the key in AI Settings. " + providerMessage.take(180)
                            404 -> "Groq could not find the selected model or endpoint (404). " + providerMessage.take(180)
                            429 -> "Groq rate limit or free-tier quota reached (429). Wait and try again. " + providerMessage.take(180)
                            500, 502, 503, 504 -> "Groq AI is temporarily unavailable ($code). Try again shortly."
                            else -> "Groq request failed with HTTP $code. " + providerMessage.take(180)
                        }.trim()
                        break
                    }
                    val message = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    if (message == null) {
                        failure = "Groq returned no usable response."
                        break
                    }
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
                        val result = executeAiToolOnUiThread(function.optString("name"), args)
                        history.put(JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", call.optString("id"))
                            .put("content", result))
                    }
                    round++
                }
                while (history.length() > 16) history.remove(0)
                preferences.edit().putString("groq_conversation_history", history.toString()).apply()
                if (answer.isBlank() && failure.isBlank()) failure = "I could not finish the action sequence. Please try again."
            } catch (e: Exception) {
                failure = "I could not connect to Groq. Check your internet connection and API key. " + (e.message ?: "").take(100)
            } finally {
                connection?.disconnect()
            }
            runOnUiThread { speak(if (answer.isNotBlank()) answer else failure) }
        }
    }

    private fun fetchAvailableGroqModel(key: String): String {
        val connection = (URL("https://api.groq.com/openai/v1/models").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Groq model-list request failed with HTTP $code")
            val data = JSONObject(raw).optJSONArray("data") ?: return ""
            val available = (0 until data.length()).mapNotNull { i ->
                data.optJSONObject(i)?.takeIf { it.optBoolean("active", true) }?.optString("id")?.takeIf { it.isNotBlank() }
            }.toSet()
            val preferred = listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b", "llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen/qwen3-32b")
            return preferred.firstOrNull { it in available }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }
    private fun groqString(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun groqTool(name: String, description: String, vararg fields: Pair<String, JSONObject>): JSONObject {
        val properties = JSONObject()
        fields.forEach { properties.put(it.first, it.second) }
        val parameters = JSONObject().put("type", "object").put("properties", properties)
        if (fields.isNotEmpty()) parameters.put("required", org.json.JSONArray(fields.map { it.first }))
        parameters.put("additionalProperties", false)
        return JSONObject().put("type", "function").put("function",
            JSONObject().put("name", name).put("description", description).put("parameters", parameters))
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
                else -> -1
            }
            if (id < 0) "Unsupported phone action."
            else if (JarvisAccessibilityService.perform(id)) "Android accepted the " + action + " action."
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
        stopService(Intent(this, JarvisVoiceService::class.java))
        recognizer?.cancel()
        status.text = "Background listening stopped."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            status.text = "Microphone allowed. Press START LISTENING."
        } else if (requestCode == 10) {
            status.text = "Microphone permission is required for voice control."
        }
    }


    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
