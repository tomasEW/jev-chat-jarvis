package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Dedicated diagnostic accessibility service for Douyin Lite.
 * It only captures the accessibility tree of com.ss.android.ugc.aweme.lite
 * into process memory. No OCR, screenshot, AI call, logging of chat text, or
 * automatic file/network export.
 */
class DouyinDiagnosticAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val root = rootInActiveWindow ?: return
                if (root.packageName?.toString() == DouyinUiDiagnostic.TARGET_PACKAGE) {
                    runCatching { DouyinUiDiagnostic.capture(root) }
                }
            }
        }
    }

    override fun onInterrupt() {}
}
