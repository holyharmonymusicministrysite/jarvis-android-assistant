package com.jarvis.assistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional notification access. JARVIS stores a short local list and reads it
 * only after the user explicitly asks. Notification content is not uploaded.
 */
class JarvisNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        if (item.packageName == packageName) return
        val extras = item.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        items.put(JSONObject()
            .put("package", item.packageName)
            .put("title", title.take(180))
            .put("text", text.take(700))
            .put("time", item.postTime))
        while (items.length() > 30) items.remove(0)
        prefs.edit().putString(KEY, items.toString()).apply()
    }

    companion object {
        private const val PREFS = "jarvis_private"
        private const val KEY = "recent_notifications"

        fun readRecent(context: Context, limit: Int = 10): String {
            val items = try {
                JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
            } catch (_: Exception) { JSONArray() }
            if (items.length() == 0) return "No recent notifications are available. Enable JARVIS notification access and wait for notifications to arrive."
            val start = (items.length() - limit.coerceIn(1, 20)).coerceAtLeast(0)
            val lines = ArrayList<String>()
            for (i in items.length() - 1 downTo start) {
                val item = items.optJSONObject(i) ?: continue
                val title = item.optString("title").ifBlank { item.optString("package") }
                val body = item.optString("text")
                lines.add(if (body.isBlank()) title else "$title: $body")
            }
            return lines.joinToString("\n").take(7000)
        }
    }
}
