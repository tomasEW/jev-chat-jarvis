package com.jev.probe.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DouyinUiDiagnostic {
    const val TARGET_PACKAGE = "com.ss.android.ugc.aweme.lite"
    private const val MAX_HISTORY = 8

    data class Capture(
        val json: String,
        val time: String,
        val nodeCount: Int,
        val textNodeCount: Int,
        val descNodeCount: Int,
        val nonEmptyNodeCount: Int
    )

    private val history = ArrayDeque<Capture>()

    @Volatile var latest: Capture? = null
        private set

    @Volatile var frozen: Capture? = null
        private set

    @Synchronized
    fun capture(root: AccessibilityNodeInfo) {
        if (root.packageName?.toString() != TARGET_PACKAGE) return

        val nodes = JSONArray()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to -1)

        var count = 0
        var textCount = 0
        var descCount = 0
        var nonEmptyCount = 0

        while (queue.isNotEmpty() && count < 3500) {
            val (node, parent) = queue.removeFirst()
            val index = count++
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val text = node.text?.toString()?.take(1000) ?: ""
            val desc = node.contentDescription?.toString()?.take(1000) ?: ""
            if (text.isNotBlank()) textCount++
            if (desc.isNotBlank()) descCount++
            if (text.isNotBlank() || desc.isNotBlank()) nonEmptyCount++

            val entry = JSONObject().apply {
                put("index", index)
                put("parent", parent)
                put("class", node.className?.toString() ?: "")
                put("resourceId", node.viewIdResourceName ?: "")
                put("text", text)
                put("contentDescription", desc)
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

        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val obj = JSONObject().apply {
            put("diagnosticVersion", 2)
            put("targetPackage", TARGET_PACKAGE)
            put("capturedAtLocal", time)
            put("nodeCount", count)
            put("textNodeCount", textCount)
            put("contentDescriptionNodeCount", descCount)
            put("nonEmptyTextOrDescriptionNodeCount", nonEmptyCount)
            put("truncated", queue.isNotEmpty())
            put("nodes", nodes)
        }

        val result = Capture(
            json = obj.toString(2),
            time = time,
            nodeCount = count,
            textNodeCount = textCount,
            descNodeCount = descCount,
            nonEmptyNodeCount = nonEmptyCount
        )

        latest = result
        history.addLast(result)
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    @Synchronized
    fun freezeLatest(): Capture? {
        frozen = latest
        return frozen
    }

    @Synchronized
    fun exportJson(): String? {
        val chosen = frozen ?: latest ?: return null
        val captures = JSONArray()
        history.forEachIndexed { index, c ->
            captures.put(JSONObject().apply {
                put("index", index)
                put("capturedAtLocal", c.time)
                put("nodeCount", c.nodeCount)
                put("textNodeCount", c.textNodeCount)
                put("contentDescriptionNodeCount", c.descNodeCount)
                put("nonEmptyTextOrDescriptionNodeCount", c.nonEmptyNodeCount)
                put("isFrozen", frozen?.time == c.time)
            })
        }
        return JSONObject().apply {
            put("diagnosticVersion", 2)
            put("targetPackage", TARGET_PACKAGE)
            put("exportedAtLocal", SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
            put("selectedCapture", JSONObject(chosen.json))
            put("recentCaptureSummaries", captures)
        }.toString(2)
    }

    @Synchronized
    fun summary(): String {
        val current = latest ?: return "尚無擷取資料"
        val frozenNow = frozen
        val frozenText = if (frozenNow == null) "未鎖定" else "已鎖定 " + frozenNow.time
        return "最近：" + current.time +
            "\n節點 " + current.nodeCount +
            "；文字 " + current.textNodeCount +
            "；描述 " + current.descNodeCount +
            "；有效 " + current.nonEmptyNodeCount +
            "\n" + frozenText
    }

    @Synchronized
    fun clear() {
        latest = null
        frozen = null
        history.clear()
    }
}
