package com.jev.probe.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Explicitly scoped, in-memory UI-tree diagnostic for Douyin Lite.
 * No screenshots, OCR, network calls, disk writes, or log messages.
 * Users export the captured tree from DiagnosticActivity themselves.
 */
object DouyinUiDiagnostic {
    const val TARGET_PACKAGE = "com.ss.android.ugc.aweme.lite"

    @Volatile var latestJson: String? = null
        private set
    @Volatile var latestTime: String? = null
        private set

    fun capture(root: AccessibilityNodeInfo) {
        if (root.packageName?.toString() != TARGET_PACKAGE) return
        val nodes = JSONArray()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to -1)
        var count = 0
        while (queue.isNotEmpty() && count < 3500) {
            val (node, parent) = queue.removeFirst()
            val index = count++
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val entry = JSONObject().apply {
                put("index", index)
                put("parent", parent)
                put("class", node.className?.toString() ?: "")
                put("resourceId", node.viewIdResourceName ?: "")
                put("text", node.text?.toString()?.take(1000) ?: "")
                put("contentDescription", node.contentDescription?.toString()?.take(1000) ?: "")
                put("bounds", JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
                put("childCount", node.childCount)
                put("editable", node.isEditable)
                put("clickable", node.isClickable)
                put("scrollable", node.isScrollable)
                put("selected", node.isSelected)
                put("visible", node.isVisibleToUser)
            }
            nodes.put(entry)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it to index) }
            }
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        latestJson = JSONObject().apply {
            put("diagnosticVersion", 1)
            put("targetPackage", TARGET_PACKAGE)
            put("capturedAtLocal", time)
            put("nodeCount", count)
            put("truncated", queue.isNotEmpty())
            put("nodes", nodes)
        }.toString(2)
        latestTime = time
    }

    fun clear() {
        latestJson = null
        latestTime = null
    }
}
