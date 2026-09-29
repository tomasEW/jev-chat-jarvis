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
                status.text = "已儲存診斷 JSON。建議直接存到 Downloads／下載資料夾。"
            } catch (e: Exception) {
                status.text = "儲存失敗：" + e.javaClass.simpleName
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
            text = "抖音極速版 UI 診斷 v2"
            textSize = 23f
            gravity = Gravity.CENTER_HORIZONTAL
        })

        layout.addView(TextView(this).apply {
            text = "1. 到 Android 無障礙設定，開啟「抖音極速版 UI 診斷」。\n" +
                "2. 進入抖音極速版私訊，等畫面完全載入。\n" +
                "3. 回到這裡先按「鎖定最近一次擷取」。\n" +
                "4. 再匯出 JSON。\n\n" +
                "新版會保留最近 8 次擷取摘要，避免後來的半載入畫面把好資料直接蓋掉。"
            textSize = 15f
        })

        status = TextView(this).apply { textSize = 15f }
        layout.addView(status)

        button("重新整理狀態") { refresh() }

        button("鎖定最近一次擷取") {
            val c = DouyinUiDiagnostic.freezeLatest()
            status.text = if (c == null) {
                "尚未抓到抖音畫面。請先進入抖音極速版私訊。"
            } else {
                "已鎖定 " + c.time +
                    "\n節點 " + c.nodeCount +
                    "；文字 " + c.textNodeCount +
                    "；描述 " + c.descNodeCount +
                    "；有效 " + c.nonEmptyNodeCount
            }
        }

        button("匯出鎖定／最近畫面 JSON") {
            val json = DouyinUiDiagnostic.exportJson()
            if (json == null) {
                status.text = "尚未擷取到畫面。請確認診斷無障礙服務已開啟並返回抖音私訊。"
            } else {
                pendingExport = json
                saveDocument.launch("douyin-lite-ui-diagnostic-v2.json")
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
        status.text = DouyinUiDiagnostic.summary()
    }
}
