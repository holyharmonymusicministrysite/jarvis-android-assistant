package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
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
import android.util.Base64
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
            text = SamAgentIdentity.ASSISTANT_NAME; textSize = 34f; gravity = Gravity.CENTER
            setTextColor(0xFF55D6FF.toInt())
        })
        root.addView(TextView(this).apply {
            text = "BUILT BY " + SamAgentIdentity.BUILDER_NAME
            textSize = 11f; gravity = Gravity.CENTER
            setTextColor(0xFF91A9BC.toInt())
        })
        status = TextView(this).apply {
            text = "Ready when you are."; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(0xFFEAF7FF.toInt()); setPadding(0, 28, 0, 24)
        }
        transcript = TextView(this).apply {
            text = "Press START, then say Hey SAM (or Hey JARVIS)."; textSize = 16f
            gravity = Gravity.CENTER; setTextColor(0xFFB9C9D8.toInt())
            setPadding(0, 16, 0, 32)
        }
        root.addView(status); root.addView(transcript)
        root.addView(Button(this).apply { text = "START LISTENING"; setOnClickListener { startListening() } })
        root.addView(Button(this).apply { text = "STOP LISTENING"; setOnClickListener { stopListening() } })
        root.addView(Button(this).apply { text = "AI SETTINGS"; setOnClickListener { showAiSettings() } })
        root.addView(Button(this).apply { text = "SAM DEVICE IDENTITY"; setOnClickListener { showSamDeviceIdentity() } })
        root.addView(Button(this).apply { text = "SAM OWNER ACCOUNT / PAIRING"; setOnClickListener { showSamOwnerConnection() } })
        root.addView(Button(this).apply {
            text = "PHONE CONTROL SETUP"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Enable SAM phone controls")
                    .setMessage("Android requires you to enable this service yourself. It can perform requested navigation actions and read visible screen text only when you explicitly ask JARVIS to read the screen. It does not continuously collect screen content. Continue to Accessibility settings?")
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

    private fun showSamOwnerConnection() {
        val savedUrl = preferences.getString("sam_gateway_url", "").orEmpty()
        val savedToken = SamSecureStore.read(this, "owner_access_token")
        if (savedUrl.isNotBlank() && !savedToken.isNullOrBlank()) {
            thread {
                try {
                    SamGatewayClient(savedUrl).me(savedToken)
                    runOnUiThread { showSamOwnerActions(savedUrl, savedToken) }
                    return@thread
                } catch (_: Exception) {
                    SamSecureStore.remove(this, "owner_access_token")
                }
            }
            return
        }

        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val gateway = EditText(this).apply {
            hint = "SAM gateway HTTPS URL"
            setText(savedUrl)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val email = EditText(this).apply {
            hint = "Owner email"
            setText(preferences.getString("sam_owner_email", "").orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val password = EditText(this).apply {
            hint = "Owner password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        panel.addView(gateway); panel.addView(email); panel.addView(password)
        AlertDialog.Builder(this)
            .setTitle("SAM owner sign-in")
            .setMessage("Sign in to your private SAM account. First-owner setup must be performed on the gateway using its server-only setup secret.")
            .setView(panel)
            .setPositiveButton("SIGN IN") { _, _ ->
                val url = gateway.text.toString().trim()
                val ownerEmail = email.text.toString().trim()
                val ownerPassword = password.text.toString()
                status.text = "Connecting to SAM gateway…"
                thread {
                    try {
                        val response = SamGatewayClient(url).login(ownerEmail, ownerPassword)
                        val token = response.getString("accessToken")
                        SamSecureStore.save(this, "owner_access_token", token)
                        preferences.edit().putString("sam_gateway_url", url)
                            .putString("sam_owner_email", ownerEmail).apply()
                        runOnUiThread {
                            status.text = "Signed in to SAM as Max."
                            showSamOwnerActions(url, token)
                        }
                    } catch (ex: Exception) {
                        runOnUiThread { Toast.makeText(this, ex.message ?: "SAM sign-in failed.", Toast.LENGTH_LONG).show() }
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showSamOwnerActions(gatewayUrl: String, token: String) {
        val actions = arrayOf("Pair this phone", "Create pairing code for Windows", "Review device requests", "View or revoke devices", "Create a task for a device", "View shared tasks", "Check this phone connection", "Sign out owner", "Sign out this device", "Company workspace")
        AlertDialog.Builder(this)
            .setTitle("SAM owner controls • Built by Max")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> pairThisPhone(gatewayUrl, token)
                    1 -> createSamPairingInvite(gatewayUrl, token)
                    2 -> loadSamPendingClaims(gatewayUrl, token)
                    3 -> loadSamDevices(gatewayUrl, token)
                    4 -> showSharedTaskTargetPicker(gatewayUrl, token)
                    5 -> loadSharedTasks(gatewayUrl, token)
                    6 -> checkSamPhoneSession(gatewayUrl)
                    7 -> thread {
                        try { SamGatewayClient(gatewayUrl).logout(token) } catch (_: Exception) {}
                        SamSecureStore.remove(this, "owner_access_token")
                        runOnUiThread { Toast.makeText(this, "Signed out of SAM owner account.", Toast.LENGTH_SHORT).show() }
                    }
                    8 -> signOutSamPhoneSession(gatewayUrl)
                    9 -> showCompanyWorkspace(gatewayUrl, token)
                }
            }
            .setNegativeButton("CLOSE", null).show()
    }


    private fun showCompanyWorkspace(gatewayUrl: String, token: String) {
        val actions = arrayOf("Dashboard overview", "Add customer", "View customers", "Create project", "Create KES quotation", "Issue a draft quotation")
        AlertDialog.Builder(this).setTitle("SAM • Company operations")
            .setItems(actions) { _, index ->
                when (index) {
                    0 -> showCompanyDashboard(gatewayUrl, token)
                    1 -> showCompanyCustomerForm(gatewayUrl, token)
                    2 -> loadCompanyCustomers(gatewayUrl, token)
                    3 -> chooseCompanyCustomer(gatewayUrl, token, "project")
                    4 -> chooseCompanyCustomer(gatewayUrl, token, "quote")
                    5 -> chooseDraftCompanyQuote(gatewayUrl, token)
                }
            }.setNegativeButton("CLOSE", null).show()
    }

    private fun showCompanyDashboard(gatewayUrl: String, token: String) {
        thread {
            try {
                val d = SamGatewayClient(gatewayUrl).companyDashboard(token)
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Company overview • KES")
                        .setMessage("Customers: ${d.optInt("customers")}\nLeads: ${d.optInt("leads")}\nActive projects: ${d.optInt("activeProjects")}\nDraft quotations: ${d.optInt("draftQuotes")}\nIssued quotations: ${d.optInt("sentQuotes")}\nIssued quotation value: KES ${d.optString("sentQuoteValueKes", "0")}")
                        .setPositiveButton("OK", null).show()
                }
            } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this, ex.message ?: "Could not load company dashboard.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showCompanyCustomerForm(gatewayUrl: String, token: String) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        fun field(hintText: String) = EditText(this).apply { hint = hintText }
        val name=field("Customer or contact name"); val company=field("Company name (optional)")
        val email=field("Email (optional)"); val phone=field("Phone (optional)"); val notes=field("Notes (optional)")
        panel.addView(name);panel.addView(company);panel.addView(email);panel.addView(phone);panel.addView(notes)
        AlertDialog.Builder(this).setTitle("Add company customer").setView(panel)
            .setPositiveButton("SAVE CUSTOMER") { _, _ ->
                thread { try {
                    val created=SamGatewayClient(gatewayUrl).createCompanyCustomer(token,name.text.toString(),company.text.toString(),email.text.toString(),phone.text.toString(),notes.text.toString())
                    runOnUiThread { Toast.makeText(this,"Customer saved: ${created.optString("name")}",Toast.LENGTH_LONG).show() }
                } catch(ex:Exception) { runOnUiThread { Toast.makeText(this,ex.message ?: "Customer could not be saved.",Toast.LENGTH_LONG).show() } } }
            }.setNegativeButton("CANCEL",null).show()
    }

    private fun loadCompanyCustomers(gatewayUrl: String, token: String) {
        thread { try {
            val rows=SamGatewayClient(gatewayUrl).companyCustomers(token)
            runOnUiThread {
                val labels=Array(rows.length()) { i -> val x=rows.getJSONObject(i); x.optString("name") + if(x.optString("companyName").isBlank()) "" else " • "+x.optString("companyName") }
                AlertDialog.Builder(this).setTitle("Company customers")
                    .setItems(labels) { _, i ->
                        val x=rows.getJSONObject(i)
                        AlertDialog.Builder(this).setTitle(x.optString("name"))
                            .setMessage(listOf(x.optString("companyName"),x.optString("email"),x.optString("phone"),x.optString("notes")).filter { it.isNotBlank() }.joinToString("\n"))
                            .setPositiveButton("OK",null).show()
                    }.setPositiveButton("CLOSE",null).show()
            }
        } catch(ex:Exception) { runOnUiThread { Toast.makeText(this,ex.message ?: "Could not load customers.",Toast.LENGTH_LONG).show() } } }
    }

    private fun chooseCompanyCustomer(gatewayUrl: String, token: String, mode: String) {
        thread { try {
            val rows=SamGatewayClient(gatewayUrl).companyCustomers(token)
            runOnUiThread {
                if(rows.length()==0) { Toast.makeText(this,"Add a customer first.",Toast.LENGTH_LONG).show();return@runOnUiThread }
                val labels=Array(rows.length()) { i -> rows.getJSONObject(i).optString("name") }
                AlertDialog.Builder(this).setTitle("Choose customer").setItems(labels) { _, i ->
                    val customer=rows.getJSONObject(i)
                    if(mode=="project") showCompanyProjectForm(gatewayUrl,token,customer)
                    else showCompanyQuoteForm(gatewayUrl,token,customer)
                }.setNegativeButton("CANCEL",null).show()
            }
        } catch(ex:Exception) { runOnUiThread { Toast.makeText(this,ex.message ?: "Could not load customers.",Toast.LENGTH_LONG).show() } } }
    }

    private fun showCompanyProjectForm(gatewayUrl: String, token: String, customer: JSONObject) {
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,8,24,8) }
        val name=EditText(this).apply { hint="Project name" }
        val brief=EditText(this).apply { hint="Brief and agreed outcome";minLines=3;gravity=Gravity.TOP }
        panel.addView(name);panel.addView(brief)
        AlertDialog.Builder(this).setTitle("New project • ${customer.optString("name")}").setView(panel)
            .setPositiveButton("CREATE PROJECT") { _, _ -> thread { try {
                val p=SamGatewayClient(gatewayUrl).createCompanyProject(token,customer.getString("id"),name.text.toString(),brief.text.toString())
                runOnUiThread { Toast.makeText(this,"Project created: ${p.optString("projectCode")} • LEAD",Toast.LENGTH_LONG).show() }
            } catch(ex:Exception) { runOnUiThread { Toast.makeText(this,ex.message ?: "Project could not be created.",Toast.LENGTH_LONG).show() } } } }
            .setNegativeButton("CANCEL",null).show()
    }

    private fun showCompanyQuoteForm(gatewayUrl: String, token: String, customer: JSONObject) {
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,8,24,8) }
        val scope=EditText(this).apply { hint="Agreed scope of work";minLines=2 }
        val exclusions=EditText(this).apply { hint="Exclusions (optional)" }
        val amount=EditText(this).apply { hint="Total amount in KES";inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        panel.addView(scope);panel.addView(exclusions);panel.addView(amount)
        AlertDialog.Builder(this).setTitle("Create KES quotation").setMessage("This creates a draft for review. It does not send the quote or collect payment.")
            .setView(panel).setPositiveButton("SAVE DRAFT") { _, _ ->
                val price=amount.text.toString().toBigDecimalOrNull()
                if(price==null||price< java.math.BigDecimal.ZERO) { Toast.makeText(this,"Enter a valid KES amount.",Toast.LENGTH_LONG).show();return@setPositiveButton }
                val lines=org.json.JSONArray().put(JSONObject().put("description","Agreed services").put("quantity",1).put("unitPriceKes",price))
                val validUntil=java.time.Instant.now().plusSeconds(14L*24*60*60).toString()
                thread { try {
                    val q=SamGatewayClient(gatewayUrl).createCompanyQuote(token,customer.getString("id"),scope.text.toString(),exclusions.text.toString(),validUntil,lines)
                    runOnUiThread { Toast.makeText(this,"Draft saved: ${q.optString("quoteNumber")} • KES ${q.optString("totalKes")}",Toast.LENGTH_LONG).show() }
                } catch(ex:Exception) { runOnUiThread { Toast.makeText(this,ex.message ?: "Quote could not be saved.",Toast.LENGTH_LONG).show() } } }
            }.setNegativeButton("CANCEL",null).show()
    }

    private fun chooseDraftCompanyQuote(gatewayUrl: String, token: String) {
        thread { try {
            val quotes=SamGatewayClient(gatewayUrl).companyQuotes(token)
            val drafts=(0 until quotes.length()).map { quotes.getJSONObject(it) }.filter { it.optString("status")=="DRAFT" }
            runOnUiThread {
                if(drafts.isEmpty()) { Toast.makeText(this,"There are no draft quotations to issue.",Toast.LENGTH_LONG).show();return@runOnUiThread }
                val labels=drafts.map { it.optString("quoteNumber")+" • KES "+it.optString("totalKes") }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Issue quotation (owner action)").setItems(labels) { _, i ->
                    AlertDialog.Builder(this).setTitle("Issue ${drafts[i].optString("quoteNumber")}?")
                        .setMessage("This changes the quote from DRAFT to SENT in SAM. It does not email the customer.")
                        .setPositiveButton("ISSUE") { _, _ -> thread { try {
                            SamGatewayClient(gatewayUrl).issueCompanyQuote(token,drafts[i].getString("id"))
                            runOnUiThread { Toast.makeText(this,"Quotation marked SENT.",Toast.LENGTH_LONG).show() }
                        } catch(ex:Exception) { runOnUiThread { Toast.makeText(this,ex.message ?: "Could not issue quote.",Toast.LENGTH_LONG).show() } } } }
                        .setNegativeButton("CANCEL",null).show()
                }.setNegativeButton("CANCEL",null).show()
            }
        } catch(ex:Exception) { runOnUiThread { Toast.makeText(this,ex.message ?: "Could not load quotations.",Toast.LENGTH_LONG).show() } } }
    }

    private fun showSharedTaskTargetPicker(gatewayUrl: String, token: String) {
        thread {
            try {
                val allDevices = SamGatewayClient(gatewayUrl).devices(token)
                val active = (0 until allDevices.length()).map { allDevices.getJSONObject(it) }
                    .filter { !it.optBoolean("revoked") }
                runOnUiThread {
                    if (active.isEmpty()) {
                        AlertDialog.Builder(this).setTitle("Create shared task")
                            .setMessage("Pair a device first. SAM cannot assign a task to an unpaired device.")
                            .setPositiveButton("OK", null).show()
                    } else {
                        val labels = active.map {
                            it.optString("deviceName") + " • " + it.optString("platform")
                        }.toTypedArray()
                        AlertDialog.Builder(this).setTitle("Choose the target device")
                            .setItems(labels) { _, index ->
                                showSharedTaskForm(gatewayUrl, token, active[index])
                            }
                            .setNegativeButton("CANCEL", null).show()
                    }
                }
            } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this, ex.message ?: "Could not load paired devices.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showSharedTaskForm(gatewayUrl: String, token: String, device: JSONObject) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        val title = EditText(this).apply { hint = "Task title"; maxLines = 2 }
        val instructions = EditText(this).apply {
            hint = "Describe what SAM should do"
            minLines = 4
            maxLines = 7
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP
        }
        panel.addView(title); panel.addView(instructions)
        AlertDialog.Builder(this)
            .setTitle("Assign task to " + device.optString("deviceName"))
            .setView(panel)
            .setPositiveButton("CREATE TASK") { _, _ ->
                val taskTitle = title.text.toString().trim()
                val taskInstructions = instructions.text.toString().trim()
                status.text = "Creating shared SAM task…"
                thread {
                    try {
                        val created = SamGatewayClient(gatewayUrl).createSharedTask(
                            token, device.getString("deviceId"), taskTitle, taskInstructions)
                        runOnUiThread {
                            status.text = "Task saved in SAM's shared inbox."
                            Toast.makeText(
                                this,
                                "Task queued: " + created.optString("title"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (ex: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, ex.message ?: "Task could not be created.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun loadSharedTasks(gatewayUrl: String, token: String) {
        thread {
            try {
                val tasks = SamGatewayClient(gatewayUrl).ownerTasks(token)
                runOnUiThread {
                    if (tasks.length() == 0) {
                        AlertDialog.Builder(this).setTitle("SAM shared tasks")
                            .setMessage("No shared tasks have been created yet.")
                            .setPositiveButton("OK", null).show()
                    } else {
                        val labels = Array(tasks.length()) { index ->
                            val task = tasks.getJSONObject(index)
                            task.optString("title") + " • " + task.optString("status") +
                                " • v" + task.optInt("version")
                        }
                        AlertDialog.Builder(this).setTitle("SAM shared task history")
                            .setItems(labels) { _, index -> showSharedTaskDetail(gatewayUrl, token, tasks.getJSONObject(index)) }
                            .setNegativeButton("CLOSE", null).show()
                    }
                }
            } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this, ex.message ?: "Could not load shared tasks.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showSharedTaskDetail(gatewayUrl: String, token: String, task: JSONObject) {
        thread {
            try {
                val events = SamGatewayClient(gatewayUrl).taskEvents(token, task.getString("id"))
                runOnUiThread {
                    val history = (0 until events.length()).joinToString("\n\n") { index ->
                        val event = events.getJSONObject(index)
                        "v" + event.optInt("version") + " • " + event.optString("status") +
                            "\n" + event.optString("message") +
                            "\n" + event.optString("createdAt")
                    }
                    val detail = "Target: " + task.optString("targetDeviceId") +
                        "\nStatus: " + task.optString("status") +
                        "\nVersion: " + task.optInt("version") +
                        "\n\n" + task.optString("instructions") +
                        "\n\nProgress history:\n" + history

                    val dialog = AlertDialog.Builder(this)
                        .setTitle(task.optString("title"))
                        .setMessage(detail)
                    if (task.optString("status") == "WAITING_FOR_APPROVAL") {
                        dialog.setPositiveButton("APPROVE TO CONTINUE") { _, _ ->
                            thread {
                                try {
                                    SamGatewayClient(gatewayUrl).approveTaskRun(
                                        token, task.getString("id"), task.optInt("version"))
                                    runOnUiThread { Toast.makeText(this, "Task approved to continue.", Toast.LENGTH_LONG).show() }
                                } catch (ex: Exception) {
                                    runOnUiThread { Toast.makeText(this, ex.message ?: "Task approval failed.", Toast.LENGTH_LONG).show() }
                                }
                            }
                        }
                    } else {
                        dialog.setPositiveButton("CLOSE", null)
                    }
                    if (task.optString("status") !in listOf("COMPLETED", "FAILED", "CANCELLED")) {
                        dialog.setNegativeButton("CANCEL TASK") { _, _ ->
                            thread {
                                try {
                                    SamGatewayClient(gatewayUrl).cancelTask(
                                        token, task.getString("id"), task.optInt("version"))
                                    runOnUiThread { Toast.makeText(this, "Task cancelled.", Toast.LENGTH_SHORT).show() }
                                } catch (ex: Exception) {
                                    runOnUiThread { Toast.makeText(this, ex.message ?: "Task cancellation failed.", Toast.LENGTH_LONG).show() }
                                }
                            }
                        }
                    }
                    dialog.setNeutralButton("REFRESH", null).show()
                }
            } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this, ex.message ?: "Could not load task progress.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun checkSamPhoneSession(gatewayUrl: String) {
        val deviceToken = SamSecureStore.read(this, "sam_device_access_token")
        if (deviceToken.isNullOrBlank()) {
            Toast.makeText(this, "This phone has not completed SAM device enrollment yet.", Toast.LENGTH_LONG).show()
            return
        }
        thread {
            try {
                val gateway = SamGatewayClient(gatewayUrl)
                val device = gateway.deviceMe(deviceToken)
                gateway.heartbeat(deviceToken)
                runOnUiThread {
                    status.text = "SAM phone connection: online."
                    Toast.makeText(this, "This phone is online as " + device.optString("deviceName") + ".", Toast.LENGTH_LONG).show()
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    status.text = "SAM phone connection could not be verified."
                    Toast.makeText(this, ex.message ?: "SAM device session check failed.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun signOutSamPhoneSession(gatewayUrl: String) {
        val token = SamSecureStore.read(this, "sam_device_access_token")
        if (token.isNullOrBlank()) {
            Toast.makeText(this, "This phone has no saved device session.", Toast.LENGTH_SHORT).show()
            return
        }
        thread {
            try {
                SamGatewayClient(gatewayUrl).logoutDeviceSession(token)
                SamSecureStore.remove(this, "sam_device_access_token")
                runOnUiThread {
                    status.text = "SAM device session signed out."
                    Toast.makeText(this, "This phone's SAM session was revoked.", Toast.LENGTH_LONG).show()
                }
            } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this, ex.message ?: "Device sign-out failed.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun pairThisPhone(gatewayUrl: String, ownerToken: String) {
        status.text = "Creating secure phone enrollment…"
        thread {
            try {
                val localIdentity = SamDeviceIdentity()
                val publicIdentity = localIdentity.getOrCreate()
                val gateway = SamGatewayClient(gatewayUrl)
                val invite = gateway.createPairingInvite(ownerToken)
                val pairingCode = invite.getString("pairingCode")
                val deviceName = (Build.MANUFACTURER + " " + Build.MODEL).trim().take(64).ifBlank { "Android phone" }
                val claim = gateway.startPairingClaim(
                    pairingCode,
                    deviceName,
                    "android",
                    publicIdentity.publicKeyBase64
                )

                val gatewayFingerprint = claim.getString("fingerprintSha256")
                if (!gatewayFingerprint.equals(publicIdentity.fingerprintSha256, ignoreCase = true))
                    throw IllegalStateException("The gateway returned a different device-key fingerprint. Enrollment stopped.")

                val claimId = claim.getString("claimId")
                val claimSecret = claim.getString("claimSecret")
                val proofPayload = Base64.decode(claim.getString("proofPayloadBase64"), Base64.DEFAULT)
                val signature = localIdentity.sign(proofPayload)
                val signatureBase64Url = Base64.encodeToString(
                    signature,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )
                gateway.provePairingClaim(claimId, signatureBase64Url)

                val stateBeforeApproval = gateway.pairingClaimStatus(claimId, claimSecret)
                if (stateBeforeApproval != "awaiting_owner_approval")
                    throw IllegalStateException("Phone proof did not reach the owner-approval step: " + stateBeforeApproval)

                // Max explicitly initiated "Pair this phone" from the authenticated owner screen.
                gateway.approveClaim(ownerToken, claimId)
                val finalState = gateway.pairingClaimStatus(claimId, claimSecret)
                if (finalState != "approved")
                    throw IllegalStateException("The phone pairing did not complete: " + finalState)

                val deviceSession = gateway.exchangeDeviceSession(claimId, claimSecret)
                val deviceAccessToken = deviceSession.getString("accessToken")
                val deviceMe = gateway.deviceMe(deviceAccessToken)
                if (deviceMe.optString("deviceId") != claim.getString("deviceId"))
                    throw IllegalStateException("Gateway device session resolved to a different device.")
                gateway.heartbeat(deviceAccessToken)
                SamSecureStore.save(this, "sam_device_access_token", deviceAccessToken)

                preferences.edit()
                    .putString("sam_android_device_id", claim.getString("deviceId"))
                    .putString("sam_android_device_fingerprint", publicIdentity.fingerprintSha256)
                    .putString("sam_android_device_session_expiry", deviceSession.optString("expiresAt"))
                    .apply()

                runOnUiThread {
                    status.text = "This phone is paired and online with SAM."
                    AlertDialog.Builder(this)
                        .setTitle("Nokia phone paired")
                        .setMessage(
                            "SAM has enrolled this phone using its Android Keystore private key.\n\n" +
                                "Device: " + deviceName + "\n\n" +
                                "SHA-256 fingerprint:\n" + publicIdentity.fingerprintSha256 +
                                "\n\nThe private key never left this phone."
                        )
                        .setPositiveButton("DONE", null)
                        .show()
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    status.text = "Phone pairing failed."
                    Toast.makeText(this, ex.message ?: "Could not pair this phone.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun createSamPairingInvite(gatewayUrl: String, token: String) {
        thread {
            try {
                val invite = SamGatewayClient(gatewayUrl).createPairingInvite(token)
                val code = invite.getString("pairingCode")
                val expiry = invite.optString("expiresAt")
                runOnUiThread {
                    val copy = Button(this).apply {
                        text = "COPY CODE"
                        setOnClickListener {
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("SAM pairing code", code))
                            Toast.makeText(this@MainActivity, "SAM pairing code copied.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    AlertDialog.Builder(this).setTitle("SAM device pairing code")
                        .setMessage("Enter this eight-digit code in the SAM Windows companion. It expires in 10 minutes. The new device will still need your approval here.\n\n" + code + "\n\nExpires: " + expiry)
                        .setView(copy).setPositiveButton("DONE", null).show()
                }
            } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this, ex.message ?: "Could not create pairing code.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun loadSamPendingClaims(gatewayUrl: String, token: String) {
        thread {
            try {
                val claims = SamGatewayClient(gatewayUrl).pendingClaims(token)
                runOnUiThread {
                    if (claims.length() == 0) {
                        AlertDialog.Builder(this).setTitle("SAM device requests")
                            .setMessage("There are no pending device requests.").setPositiveButton("OK", null).show()
                    } else {
                        val labels = Array(claims.length()) { index ->
                            val claim = claims.getJSONObject(index)
                            val proof = if (claim.optBoolean("proofVerified")) "proof verified" else "proof not verified"
                            claim.optString("deviceName") + " • " + claim.optString("platform") + " • " + proof
                        }
                        AlertDialog.Builder(this).setTitle("Choose a device request")
                            .setItems(labels) { _, index -> reviewSamClaim(gatewayUrl, token, claims.getJSONObject(index)) }
                            .setNegativeButton("CLOSE", null).show()
                    }
                }
            } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this, ex.message ?: "Could not load device requests.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun reviewSamClaim(gatewayUrl: String, token: String, claim: JSONObject) {
        val claimId = claim.optString("claimId")
        val proof = claim.optBoolean("proofVerified")
        val message = "Device: " + claim.optString("deviceName") +
            "\nPlatform: " + claim.optString("platform") +
            "\nSHA-256 fingerprint:\n" + claim.optString("fingerprintSha256") +
            "\n\n" + if (proof) "The device proved possession of its private key." else "The device has not completed private-key proof yet."
        val dialog = AlertDialog.Builder(this).setTitle("Review SAM device").setMessage(message)
        if (proof) dialog.setPositiveButton("APPROVE") { _, _ ->
            thread {
                try {
                    SamGatewayClient(gatewayUrl).approveClaim(token, claimId)
                    runOnUiThread { Toast.makeText(this, "Device enrolled in SAM.", Toast.LENGTH_LONG).show() }
                } catch (ex: Exception) {
                    runOnUiThread { Toast.makeText(this, ex.message ?: "Approval failed.", Toast.LENGTH_LONG).show() }
                }
            }
        }
        dialog.setNegativeButton(if (proof) "REJECT" else "REJECT REQUEST") { _, _ ->
            thread {
                try {
                    SamGatewayClient(gatewayUrl).rejectClaim(token, claimId)
                    runOnUiThread { Toast.makeText(this, "Device request rejected.", Toast.LENGTH_SHORT).show() }
                } catch (ex: Exception) {
                    runOnUiThread { Toast.makeText(this, ex.message ?: "Rejection failed.", Toast.LENGTH_LONG).show() }
                }
            }
        }
        dialog.setNeutralButton("CLOSE", null).show()
    }

    private fun loadSamDevices(gatewayUrl: String, token: String) {
        thread {
            try {
                val devices = SamGatewayClient(gatewayUrl).devices(token)
                runOnUiThread {
                    if (devices.length() == 0) {
                        AlertDialog.Builder(this).setTitle("SAM devices").setMessage("No devices are paired yet.")
                            .setPositiveButton("OK", null).show()
                    } else {
                        val labels = Array(devices.length()) { index ->
                            val device = devices.getJSONObject(index)
                            val state = if (device.optBoolean("revoked")) "revoked" else "active"
                            device.optString("deviceName") + " • " + device.optString("platform") + " • " + state
                        }
                        AlertDialog.Builder(this).setTitle("SAM paired devices")
                            .setItems(labels) { _, index ->
                                val device = devices.getJSONObject(index)
                                if (!device.optBoolean("revoked")) {
                                    AlertDialog.Builder(this).setTitle("Revoke SAM device?")
                                        .setMessage("Revoke " + device.optString("deviceName") + "? It will no longer be trusted by the SAM account.")
                                        .setPositiveButton("REVOKE") { _, _ ->
                                            thread {
                                                try {
                                                    SamGatewayClient(gatewayUrl).revokeDevice(token, device.getString("deviceId"))
                                                    runOnUiThread { Toast.makeText(this, "Device revoked.", Toast.LENGTH_SHORT).show() }
                                                } catch (ex: Exception) {
                                                    runOnUiThread { Toast.makeText(this, ex.message ?: "Device revocation failed.", Toast.LENGTH_LONG).show() }
                                                }
                                            }
                                        }.setNegativeButton("CANCEL", null).show()
                                }
                            }.setNegativeButton("CLOSE", null).show()
                    }
                }
            } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this, ex.message ?: "Could not load paired devices.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showSamDeviceIdentity() {
        try {
            val identity = SamDeviceIdentity().getOrCreate()
            val publicKeyField = EditText(this).apply {
                setText(identity.publicKeyBase64)
                minLines = 3
                maxLines = 5
                isSingleLine = false
                textSize = 12f
            }
            AlertDialog.Builder(this)
                .setTitle("SAM Android device identity")
                .setMessage(
                    "Local identity: ready. This phone is not paired to a SAM account yet.\n\n" +
                        "SHA-256 public-key fingerprint:\n" + identity.fingerprintSha256
                )
                .setView(publicKeyField)
                .setPositiveButton("COPY PUBLIC KEY") { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("SAM public device key", identity.publicKeyBase64))
                    Toast.makeText(this, "Public key copied. It is not a pairing code.", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("CLOSE", null)
                .show()
        } catch (ex: Exception) {
            AlertDialog.Builder(this)
                .setTitle("SAM device identity unavailable")
                .setMessage("Android could not create or verify the local device key: " + (ex.message ?: "Unknown error"))
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showAiSettings() {
        val field = EditText(this).apply {
            hint = "Paste your Groq API key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(preferences.getString("groq_key", "").orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle("Connect JARVIS to Groq")
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
            status.text = "JARVIS is running in the background. You can switch apps and say Hey JARVIS."
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
        val key = preferences.getString("groq_key", null)
        if (key.isNullOrBlank()) {
            speak("Connect JARVIS to Groq in AI Settings. Google AI Studio is no longer required.")
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
                tools.put(groqTool("company_dashboard", "Read the owner's company dashboard: customers, leads, active projects and issued quotation value."))
                tools.put(groqTool("company_list_customers", "List the owner's company customers and their IDs so projects and quotes can be linked correctly."))
                tools.put(groqTool("company_add_customer", "Add a customer only when the owner explicitly asks. This creates a CRM record.",
                    "name" to groqString("Customer or contact name"),
                    "company_name" to groqString("Company name or empty string"),
                    "email" to groqString("Email or empty string"),
                    "phone" to groqString("Phone or empty string"),
                    "notes" to groqString("Notes or empty string")))
                tools.put(groqTool("company_create_project", "Create a company project for an existing customer only when the owner asks. New projects start in LEAD status.",
                    "customer_id" to groqString("ID from company_list_customers"),
                    "name" to groqString("Project name"),
                    "brief" to groqString("Agreed project brief")))
                tools.put(groqTool("company_create_quote", "Create a DRAFT KES quotation only when explicitly requested. It does not send the quote or collect money.",
                    "customer_id" to groqString("ID from company_list_customers"),
                    "scope" to groqString("Agreed scope of work"),
                    "exclusions" to groqString("Exclusions or empty string"),
                    "amount_kes" to JSONObject().put("type", "number").put("minimum", 0).put("maximum", 100000000).put("description", "Total quotation amount in KES"),
                    "valid_days" to JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 90).put("description", "Quote validity in days")))
                val instructions = SamAgentIdentity.SYSTEM_CONTEXT + "\n\nYou are SAM: an expert reasoning assistant and company operations partner. First understand the actual outcome the owner wants. For complex work, silently plan a short sequence, use the smallest safe tools, inspect each result, and verify the final state. Ask one focused question when a required fact is missing; never invent customer details, prices, dates, approvals or results. You may act as operations manager, project manager, sales coordinator, finance analyst, research lead and technical lead, but you are not a licensed accountant or lawyer. For company work, follow scope-before-price: confirm the customer, deliverables, exclusions, timeline and currency before preparing a quotation. Use company tools only for an explicit owner request. A new quote is a DRAFT only; never claim it was sent, accepted, invoiced or paid. Do not initiate payments, change financial records, send external messages, issue quotes or perform destructive actions without explicit confirmation. Use KES for company quotations. Never expose credentials or private customer data unnecessarily. After tool use, report only verified outcomes and clearly state what remains manual. Available phone actions: open apps, Home, Back, Recents, Lock Screen, web search, time and notes. Available company actions: read dashboard, list customers, add a requested customer, create a project and prepare a draft KES quotation. No payroll, tax filing, payment collection, M-Pesa settlement, email sending or autonomous project execution is connected yet. You cannot bypass Android security or lock screens. Be thoughtful, accurate and concise."
                var round = 0
                while (round < 6) {
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
                        .put("max_tokens", 1400)
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
            val preferred = listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "qwen/qwen3-32b", "openai/gpt-oss-20b", "llama-3.1-8b-instant")
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

    private fun executeCompanyAiTool(name: String, args: JSONObject): String {
        val gateway = preferences.getString("sam_gateway_url", "").orEmpty()
        val token = SamSecureStore.read(this, "owner_access_token")
        if (gateway.isBlank() || token.isNullOrBlank()) return "Company tools are unavailable until the owner signs in to the SAM account and connects a gateway."
        val api = SamGatewayClient(gateway)
        return try {
            when (name) {
                "company_dashboard" -> {
                    val d = api.companyDashboard(token)
                    "Company dashboard: ${d.optInt("customers")} customers, ${d.optInt("leads")} leads, ${d.optInt("activeProjects")} active projects, ${d.optInt("draftQuotes")} draft quotes, ${d.optInt("sentQuotes")} issued quotes worth KES ${d.optString("sentQuoteValueKes", "0")}."
                }
                "company_list_customers" -> {
                    val rows = api.companyCustomers(token)
                    if (rows.length() == 0) "There are no company customers yet."
                    else (0 until rows.length()).joinToString("; ") { i ->
                        val x = rows.getJSONObject(i)
                        "${x.optString("name")} (ID: ${x.optString("id")})" +
                            if (x.optString("companyName").isBlank()) "" else " • ${x.optString("companyName")}"
                    }
                }
                "company_add_customer" -> {
                    val x = api.createCompanyCustomer(token, args.optString("name"), args.optString("company_name"),
                        args.optString("email"), args.optString("phone"), args.optString("notes"))
                    "Customer CRM record created: ${x.optString("name")} (ID: ${x.optString("id")})."
                }
                "company_create_project" -> {
                    val x = api.createCompanyProject(token, args.optString("customer_id"), args.optString("name"), args.optString("brief"))
                    "Project created in LEAD status: ${x.optString("name")}, reference ${x.optString("projectCode")}."
                }
                "company_create_quote" -> {
                    val days = args.optInt("valid_days", 14).coerceIn(1, 90)
                    val lines = org.json.JSONArray().put(JSONObject().put("description", "Agreed services")
                        .put("quantity", 1).put("unitPriceKes", args.optDouble("amount_kes", -1.0)))
                    val validUntil = java.time.Instant.now().plusSeconds(days.toLong() * 86400).toString()
                    val x = api.createCompanyQuote(token, args.optString("customer_id"), args.optString("scope"),
                        args.optString("exclusions"), validUntil, lines)
                    "Draft quotation prepared: ${x.optString("quoteNumber")}, KES ${x.optString("totalKes")}, valid until ${x.optString("validUntil")}. It has not been sent."
                }
                else -> "That company action is not available."
            }
        } catch (ex: Exception) {
            "Company action failed and was not confirmed: ${ex.message ?: "unknown gateway error"}"
        }
    }

    private fun executeAiToolOnUiThread(name: String, args: JSONObject): String {
        if (name.startsWith("company_")) return executeCompanyAiTool(name, args)
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
