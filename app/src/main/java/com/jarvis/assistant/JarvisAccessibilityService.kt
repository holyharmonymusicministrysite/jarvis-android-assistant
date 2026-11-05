package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * User-enabled assistive bridge. Screen text is collected only on an explicit
 * JARVIS request; accessibility events are not stored or transmitted.
 */
class JarvisAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    private fun collectText(node: AccessibilityNodeInfo?, output: StringBuilder, depth: Int) {
        if (node == null || depth > 20 || output.length >= 12000) return
        val value = node.text?.toString()?.trim().orEmpty()
        if (value.isNotBlank()) {
            if (output.isNotEmpty()) output.append("\n")
            output.append(value)
        }
        for (i in 0 until node.childCount) {
            if (output.length >= 12000) break
            collectText(node.getChild(i), output, depth + 1)
        }
    }

    private fun readVisibleScreen(): String {
        val root = rootInActiveWindow ?: return "Android did not expose readable text from the current screen. The app may not provide accessibility text, or JARVIS may need to be enabled again."
        val output = StringBuilder()
        try { collectText(root, output, 0) } finally { root.recycle() }
        return output.toString().takeIf { it.isNotBlank() }
            ?: "The current screen contains no readable accessibility text."
    }

    companion object {
        @Volatile private var activeService: JarvisAccessibilityService? = null

        fun perform(action: Int): Boolean {
            val service = activeService ?: return false
            return service.performGlobalAction(action)
        }

        fun readScreen(): String {
            val service = activeService
                ?: return "Enable JARVIS in Android Accessibility settings first. Screen reading is only performed when you explicitly ask."
            return service.readVisibleScreen()
        }
    }
}
