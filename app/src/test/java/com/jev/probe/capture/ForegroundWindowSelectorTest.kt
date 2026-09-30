package com.jev.probe.capture

import org.junit.Assert.*
import org.junit.Test

class ForegroundWindowSelectorTest {
    private val own = "com.jev.probe.douyindiagnostic"
    private val douyin = ForegroundWindowSelector.Window(1, "com.ss.android.ugc.aweme.lite", true, false, false, 0)
    private val line = ForegroundWindowSelector.Window(2, "jp.naver.line.android", true, true, true, 1)
    private val overlay = ForegroundWindowSelector.Window(3, own, false, true, false, 5)

    @Test fun switchingFromDouyinToLineUsesLine() {
        assertEquals(line, ForegroundWindowSelector.select(line, listOf(douyin, line), own))
    }
    @Test fun overlayTapKeepsCurrentLineRatherThanOldDouyin() {
        assertEquals(line, ForegroundWindowSelector.select(overlay, listOf(douyin, line, overlay), own))
    }
    @Test fun keyboardDoesNotBecomeCaptureTarget() {
        val ime = overlay.copy(pkg = "com.example.keyboard")
        assertEquals(line, ForegroundWindowSelector.select(ime, listOf(line, ime), own))
    }
    @Test fun ownSettingsNeverSelectsBackgroundChat() {
        assertNull(ForegroundWindowSelector.select(overlay.copy(application = true), listOf(douyin), own))
    }
    @Test fun noVisibleApplicationDoesNotReusePreviousChat() {
        assertNull(ForegroundWindowSelector.select(overlay, listOf(overlay), own))
    }
    @Test fun foregroundDouyinStillUsesDouyin() {
        assertEquals(douyin, ForegroundWindowSelector.select(douyin, listOf(line, douyin), own))
    }
}
