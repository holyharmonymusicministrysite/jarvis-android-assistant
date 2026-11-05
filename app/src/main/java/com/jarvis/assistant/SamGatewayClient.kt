package com.jarvis.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** HTTPS-only client for SAM owner and device-registry endpoints. */
internal class SamGatewayClient(baseUrl: String) {
    private val base: String

    init {
        val normalized = baseUrl.trim().trimEnd('/')
        val uri = try { URI(normalized) } catch (ex: Exception) {
            throw IllegalArgumentException("Enter a valid SAM gateway HTTPS address.", ex)
        }
        require(uri.scheme?.equals("https", true) == true && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "SAM gateway address must use HTTPS and must not include credentials, a query or a fragment."
        }
        base = normalized
    }

    fun login(email: String, password: String): JSONObject =
        request("POST", "/api/v1/auth/login", null,
            JSONObject().put("email", email).put("password", password).toString()) as JSONObject
    fun me(token: String): JSONObject = request("GET", "/api/v1/auth/me", token, null) as JSONObject
    fun createPairingInvite(token: String): JSONObject = request("POST", "/api/v1/pairing/invites", token, "{}") as JSONObject
    fun startPairingClaim(pairingCode: String, deviceName: String, platform: String, publicKeySpkiBase64: String): JSONObject =
        request("POST", "/api/v1/pairing/claims/start", null,
            JSONObject().put("pairingCode", pairingCode).put("deviceName", deviceName)
                .put("platform", platform).put("publicKeySpkiBase64", publicKeySpkiBase64).toString()) as JSONObject
    fun provePairingClaim(claimId: String, signatureBase64Url: String) {
        request("POST", "/api/v1/pairing/claims/$claimId/prove", null,
            JSONObject().put("signatureBase64Url", signatureBase64Url).toString())
    }
    fun exchangeDeviceSession(claimId: String, claimSecret: String): JSONObject =
        request("POST", "/api/v1/pairing/claims/$claimId/session", null,
            JSONObject().put("claimSecret", claimSecret).toString()) as JSONObject
    fun deviceMe(token: String): JSONObject = request("GET", "/api/v1/devices/me", token, null) as JSONObject
    fun heartbeat(token: String): JSONObject = request("POST", "/api/v1/devices/me/heartbeat", token, "{}") as JSONObject
    fun logoutDeviceSession(token: String) { request("POST", "/api/v1/devices/me/logout", token, "{}") }
    fun pairingClaimStatus(claimId: String, claimSecret: String): String =
        (request("POST", "/api/v1/pairing/claims/$claimId/status", null,
            JSONObject().put("claimSecret", claimSecret).toString()) as JSONObject).optString("status")
    fun pendingClaims(token: String): JSONArray = request("GET", "/api/v1/pairing/claims", token, null) as JSONArray
    fun approveClaim(token: String, claimId: String) { request("POST", "/api/v1/pairing/claims/$claimId/approve", token, "{}") }
    fun rejectClaim(token: String, claimId: String) { request("POST", "/api/v1/pairing/claims/$claimId/reject", token, "{}") }
    fun devices(token: String): JSONArray = request("GET", "/api/v1/devices", token, null) as JSONArray
    fun revokeDevice(token: String, deviceId: String) { request("POST", "/api/v1/devices/$deviceId/revoke", token, "{}") }
    fun logout(token: String) { request("POST", "/api/v1/auth/logout", token, "{}") }

    private fun request(method: String, path: String, token: String?, body: String?): Any {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = try {
                    val error = JSONObject(text)
                    error.optString("detail", error.optString("message", "HTTP $status"))
                } catch (_: Exception) { "HTTP $status" }
                throw IOException("SAM gateway: $message")
            }
            if (text.isBlank()) return JSONObject()
            return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
        } finally { connection.disconnect() }
    }
}
