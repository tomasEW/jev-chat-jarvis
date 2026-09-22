package com.jev.probe

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.capture.DouyinUiDiagnostic

/** Separate launcher entry point. The accessibility service caches the last
 * Douyin Lite tree in process memory; exporting is always user initiated. */
class DiagnosticActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var pendingExport: String? = null

    private val saveDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val data = pendingExport
        pendingExport = null
        if (uri != null && data != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(data.toByteArray(Charsets.UTF_8))
                } ?: error("Cannot open output stream")
                status.text = "已儲存診斷 JSON。請檢查個人資料後再分享。"
            } catch (e: Exception) {
                status.text = "儲存失敗：${e.javaClass.simpleName}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (40 * dp).toInt(),
                (20 * dp).toInt(), (20 * dp).toInt())
        }
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            })
        }
        layout.addView(TextView(this).apply {
            text = "抖音極速版 UI 診斷"
            textSize = 23f
            gravity = Gravity.CENTER_HORIZONTAL
        })
        layout.addView(TextView(this).apply {
            text = "1. 到 Android 無障礙設定，開啟「抖音極速版 UI 診斷」服務。\n" +
                "2. 進入抖音極速版的測試用私訊對話，確認雙方都有訊息。\n" +
                "3. 回到此診斷工具，按下方按鈕將最近擷取的節點匯出成 JSON。\n" +
                "只擷取抖音極速版的無障礙節點，不截圖、不 OCR、不呼叫 AI。請使用測試對話。"
            textSize = 15f
        })
        status = TextView(this).apply { textSize = 15f }
        layout.addView(status)
        button("重新整理狀態") { refresh() }
        button("匯出最近的抖音畫面 JSON") {
            val json = DouyinUiDiagnostic.latestJson
            if (json == null) {
                status.text = "尚未擷取到畫面。請確認 Jev 無障礙服務已開啟並返回抖音私訊。"
            } else {
                pendingExport = json
                saveDocument.launch("douyin-lite-ui-diagnostic.json")
            }
        }
        button("清除暫存的診斷資料") {
            DouyinUiDiagnostic.clear()
            pendingExport = null
            refresh()
        }
        button("開啟 Android 無障礙設定") {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val json = DouyinUiDiagnostic.latestJson
        status.text = if (json == null) {
            "尚無抖音極速版診斷資料；請先前往測試私訊畫面。"
        } else {
            "已擷取：${DouyinUiDiagnostic.latestTime}；大小：${json.length} 字元。"
        }
    }
}
