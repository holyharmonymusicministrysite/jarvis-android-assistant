package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal, user-enabled accessibility bridge for explicit global navigation commands.
 * It does not inspect screen contents or automate other apps.
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

    companion object {
        @Volatile private var activeService: JarvisAccessibilityService? = null

        fun perform(action: Int): Boolean {
            val service = activeService ?: return false
            return service.performGlobalAction(action)
        }
    }
}
