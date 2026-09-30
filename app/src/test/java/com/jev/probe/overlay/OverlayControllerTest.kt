package com.jev.probe.overlay

import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class OverlayControllerTest {
    private fun panel(): Pair<OverlayController, LinearLayout> {
        val context = RuntimeEnvironment.getApplication()
        val controller = OverlayController(context)
        val content = LinearLayout(context)
        // A mounted overlay's content, without granting window-manager permissions.
        for ((field, value) in listOf("root" to FrameLayout(context), "contentBox" to content)) {
            OverlayController::class.java.getDeclaredField(field).apply { isAccessible = true }.set(controller, value)
        }
        return controller to content
    }

    @Test fun cancellationDuringOcrLeavesMessageAndWorkingRetryControl() {
        val (controller, content) = panel()
        var tapped = false
        controller.onManualAnalyze = { tapped = true }
        controller.showProgress("正在截屏辨识…")
        assertTrue(controller.isShowing())
        controller.resetForNewConversation()
        assertTrue(controller.isShowing())
        assertEquals(2, content.childCount)
        assertTrue((content.getChildAt(0) as TextView).text.contains("停止"))
        val retry = content.getChildAt(1) as TextView
        assertEquals("分析当前对话", retry.text.toString())
        retry.performClick()
        assertTrue(tapped)
    }

    @Test fun repeatedResetNeverLeavesMountedPanelBlank() {
        val (controller, content) = panel()
        controller.showError("文字辨识超时")
        repeat(3) { controller.resetForNewConversation() }
        assertTrue(content.childCount > 0)
        controller.showIdle(null)
        assertTrue(content.childCount > 0)
    }
}
