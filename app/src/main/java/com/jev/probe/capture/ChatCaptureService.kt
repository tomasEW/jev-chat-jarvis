package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.jev.probe.capture.ocr.MlKitOcr
import com.jev.probe.capture.ocr.OcrLine
import com.jev.probe.capture.ocr.ScreenCapture
import com.jev.probe.core.BubbleRect
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ContextBuilder
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.JevClient
import com.jev.probe.overlay.OverlayController
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

/**
 * The live capture service (registered under a disguised class name so WeChat
 * exposes its node tree — see the disguised subclass). It reads whichever
 * adapted chat app is in the foreground, detects a new incoming message from the
 * other person, runs Jev analysis off the main thread, and drives the floating
 * overlay.
 *
 * Per-app node rules live in [ChatAppAdapter] implementations; everything here
 * is app-agnostic.
 *
 * It never sends a message. The only write action is ACTION_SET_TEXT (or a
 * clipboard PASTE fallback) to fill the chat input box when the user taps
 * "填入"; the user still presses send.
 */
open class ChatCaptureService : AccessibilityService() {

    private val serviceInstanceId = Integer.toHexString(System.identityHashCode(this))
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)

    /** Adapted chat apps, keyed by package name.
     *  WeChat is intentionally NOT wired in: reading it (node tree / screenshot /
     *  OCR) is what trips WeChat's anti-screenshot risk control, so it is fully
     *  disabled and handled by a short-circuit notice instead of an adapter (see
     *  [maybeCapture] / [onAccessibilityEvent]). [WeChatAdapter] is kept in the
     *  codebase for a possible future restore, just not used here. */
    private val adapters = listOf(QQAdapter(), XAdapter(), FeishuAdapter(), DouyinLiteAdapter()).associateBy { it.pkg }

    /** Submit to the worker, ignoring rejection after the service is torn down
     *  (a stale overlay callback must never crash the process). */
    private fun traceStep(message: String) {
        overlay?.trace(message)
    }

    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    private var lastSignature: String = ""
    private var activePkg: String? = null
    private var analyzing = false
    private val session = ConversationSession()
    private val analysisTasks = ArrayList<Future<*>>()
    private var destroyed = false
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "enabled" || key == "whitelist") {
            main.post {
                leaveConversation()
                overlay?.hide()
            }
        }
    }

    /** Invalidate callbacks before cancelling workers; interruption alone is not a guard. */
    private fun cancelAnalysis() {
        session.invalidate()
        ocrToken?.let { completeOcr(it) }
        main.removeCallbacks(debounce)
        pendingSnapshot = null
        analyzing = false
        analysisTasks.forEach { it.cancel(true) }
        analysisTasks.clear()
        overlay?.resetForNewConversation()
    }

    private fun observeTarget(target: ConversationSession.Target?) {
        if (session.observe(target)) {
            cancelAnalysis()
            currentSnapshot = null
            activePkg = null
            lastSignature = ""
            lastOcrSignature = ""
        }
    }

    private fun leaveConversation() {
        observeTarget(null)
        cancelAnalysis()
        currentSnapshot = null
    }

    /** Prefer the underlying chat window even while Jev's overlay is being tapped. */
    private fun rootForPackage(pkg: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { root ->
            if (root.packageName?.toString() == pkg) return root
        }
        for (window in windows) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            if (root.packageName?.toString() == pkg) return root
        }
        return null
    }

    /**
     * Reacquire roots after a manual cache refresh. Android 13+ can prefetch
     * descendants breadth-first and make the fetch uninterruptible; this avoids
     * immediately walking the same half-populated hierarchy that produced
     * Adapter failure / rows=0.
     */
    private fun freshForegroundRoot(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT < 33) {
            return foregroundRoot()?.also { runCatching { it.refresh() } }
        }
        val strategy = AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST or
            AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE
        val active = runCatching { getRootInActiveWindow(strategy) }.getOrNull()
        val roots = windows.mapNotNull { w ->
            val root = runCatching { w.getRoot(strategy) }.getOrNull() ?: return@mapNotNull null
            val pkg = root.packageName?.toString() ?: return@mapNotNull null
            ForegroundWindowSelector.Window(w.id, pkg,
                w.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                w.isActive, w.isFocused, w.layer) to root
        }
        val activeWindow = active?.let { root ->
            val w = root.window ?: return@let null
            ForegroundWindowSelector.Window(w.id, root.packageName?.toString().orEmpty(),
                w.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                w.isActive, w.isFocused, w.layer)
        }
        val selected = ForegroundWindowSelector.select(activeWindow, roots.map { it.first }, packageName)
            ?: return null
        return if (selected.id == active?.windowId) active
            else roots.firstOrNull { it.first.id == selected.id }?.second
    }

    /** Resolve the visible application, ignoring our overlay and the keyboard. */
    private fun foregroundRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        val roots = windows.mapNotNull { w ->
            val root = runCatching { w.root }.getOrNull() ?: return@mapNotNull null
            val pkg = root.packageName?.toString() ?: return@mapNotNull null
            ForegroundWindowSelector.Window(w.id, pkg,
                w.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                w.isActive, w.isFocused, w.layer) to root
        }
        val activeWindow = active?.let { root ->
            val w = root.window ?: return@let null
            ForegroundWindowSelector.Window(w.id, root.packageName?.toString().orEmpty(),
                w.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                w.isActive, w.isFocused, w.layer)
        }
        val selected = ForegroundWindowSelector.select(activeWindow, roots.map { it.first }, packageName)
            ?: return null
        return if (selected.id == active?.windowId) active
            else roots.firstOrNull { it.first.id == selected.id }?.second
    }

    /** Read the live target, never the previous chat's cached/stabilized title. */
    private fun targetFor(root: AccessibilityNodeInfo, extracted: ChatSnapshot? = null): ConversationSession.Target? {
        val pkg = root.packageName?.toString() ?: return null
        if (pkg == PKG_WECHAT || pkg == packageName || pkg == "com.android.systemui" ||
            pkg.contains("launcher", true) || pkg == "com.miui.home") return null
        val adapter = adapters[pkg]
        var messagesSignature: String? = null
        val title = if (adapter != null) {
            val snapshot = extracted ?: adapter.extract(root, resources) ?: return null
            messagesSignature = when {
                snapshot.messages.isNotEmpty() -> snapshot.signature()
                pkg == "com.ss.android.ugc.aweme.lite" && snapshot.bubbleRects.isNotEmpty() ->
                    ocrSignature(pkg, snapshot.title, snapshot.bubbleRects)
                else -> null
            }
            // Douyin Lite sometimes paints the visible DM title without exposing
            // accessibility text. For a manual analysis with no whitelist, keep a
            // window+row-geometry target instead of blocking before screenshot.
            // The rect signature still invalidates the request if the visible chat
            // layout changes while OCR/analysis is running.
            snapshot.title?.takeUnless { isTransientTitle(it) }
                ?: if (pkg == "com.ss.android.ugc.aweme.lite" && snapshot.bubbleRects.isNotEmpty())
                    "抖音私信#${root.windowId}"
                else return null
        } else {
            findTitleInActionBar(root, Int.MAX_VALUE, resources.displayMetrics.widthPixels,
                resources, 0.15, 0.85)
        }
        return ConversationSession.Target(pkg, root.windowId, title, messagesSignature)
    }

    /** Verification never clears the UI or invalidates a request as a side effect. */
    private fun checkCurrent(token: ConversationSession.Token): ConversationSession.Check {
        if (destroyed || !prefs.enabled) return ConversationSession.Check.STALE
        val root = foregroundRoot()
        val live = root?.let { targetFor(it) }
        val check = session.check(token, root?.packageName?.toString(), root?.windowId, live)
        return if (check == ConversationSession.Check.CURRENT && !prefs.isAllowed(live?.title))
            ConversationSession.Check.CHANGED else check
    }

    private fun isCurrent(token: ConversationSession.Token): Boolean =
        checkCurrent(token) == ConversationSession.Check.CURRENT

    private fun abortRequest(token: ConversationSession.Token, stage: String, check: ConversationSession.Check) {
        if (token.target.pkg == "com.ss.android.ugc.aweme.lite") traceStep("中止 $stage：$check")
        if (!session.accepts(token) || destroyed || !prefs.enabled) return
        val root = foregroundRoot()
        val sameApp = root?.packageName?.toString() == token.target.pkg
        cancelAnalysis()
        if (sameApp || root == null) {
            overlay?.showError(if (check == ConversationSession.Check.UNAVAILABLE)
                "$stage：聊天节点暂时无法读取，已重试，请重新分析"
                else "$stage：当前会话已变化，已停止分析，请重新分析")
        }
        // If the app really changed, keep the reset's neutral retry controls.
        // Never render the old chat's result into the new application.
    }

    /** Briefly retry incomplete accessibility trees, while still rejecting real
     * app/window/title changes and requests cancelled by a newer analysis. */
    private fun awaitCurrentTarget(
        token: ConversationSession.Token,
        stage: String,
        retries: Int = 4,
        onInvalid: (ConversationSession.Check) -> Unit = { abortRequest(token, stage, it) },
        onCurrent: () -> Unit
    ) {
        val check = checkCurrent(token)
        if (token.target.pkg == "com.ss.android.ugc.aweme.lite") traceStep("$stage：$check / retry=$retries")
        when {
            check == ConversationSession.Check.CURRENT -> onCurrent()
            check == ConversationSession.Check.UNAVAILABLE && retries > 0 ->
                main.postDelayed({ awaitCurrentTarget(token, stage, retries - 1, onInvalid, onCurrent) }, 200)
            else -> onInvalid(check)
        }
    }

    /** Window-only check before the local screenshot. Full conversation
     * verification runs before OCR, so an unreadable title need not abort the
     * screenshot during the 120 ms overlay visibility transition. */
    private fun canCaptureWindow(token: ConversationSession.Token): Boolean {
        if (destroyed || !prefs.enabled || !session.accepts(token)) return false
        val root = foregroundRoot() ?: return false
        return root.packageName?.toString() == token.target.pkg && root.windowId == token.target.windowId
    }

    /** Only called on the main thread, including the context-completion callback. */
    private fun submitAnalysis(task: () -> Unit) {
        try { analysisTasks.add(worker.submit(task)) } catch (_: RejectedExecutionException) { }
    }

    private val debounce = Runnable { runAnalysis() }
    private var pendingSnapshot: ChatSnapshot? = null
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    private var foregroundPkg: String? = null

    // ---- OCR path (B stage). Everything here runs on the main thread: the
    // screenshot callback and the ML Kit callback are both posted back to it.
    private val screenCapture by lazy {
        ScreenCapture(this,
            hideOverlay = { overlay?.setHiddenForShot(true) },
            restoreOverlay = { overlay?.setHiddenForShot(false) })
    }
    private val ocr = MlKitOcr()
    private var ocrBusy = false
    private var ocrToken: ConversationSession.Token? = null
    private var manualOcrRequested = false
    private var ocrTimeout: Runnable? = null

    private fun completeOcr(token: ConversationSession.Token): Boolean {
        if (ocrToken != token) return false
        ocrTimeout?.let { main.removeCallbacks(it) }
        ocrTimeout = null
        ocrToken = null
        ocrBusy = false
        return true
    }

    /** What the screen looked like the last time we fired an automatic shot.
     *  See [ocrSignature]: this is the brake on the OCR path. */
    private var lastOcrSignature: String = ""

    /** WeChat is fully disabled — we never read it, so instead of a signature we
     *  just track whether the "WeChat not supported" notice has been shown for
     *  the current WeChat visit. Reset to false whenever a non-WeChat foreground
     *  is seen, so it re-appears next visit but does not re-pop on every event. */
    private var wechatNoticeShown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        getSharedPreferences(Prefs.PREFS_MAIN, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(preferencesListener)
        // Enforce exactly one live overlay per process. Accessibility services can
        // reconnect without a process restart; an old WindowManager view would
        // otherwise remain tappable while this service updates a new controller.
        overlay?.hide()
        liveOverlay?.takeIf { it !== overlay }?.hide()

        val controller = OverlayController(this)
        overlay = controller
        liveOverlay = controller
        controller.trace("service connected S=$serviceInstanceId O=${controller.debugId()}")
        controller.onManualAnalyze = callback@{
            controller.trace("0c callback entered S=$serviceInstanceId O=${controller.debugId()} live=${liveOverlay === controller}")
            if (liveOverlay !== controller || overlay !== controller) {
                controller.trace("0d stale overlay callback blocked")
                controller.hide()
                return@callback
            }
            try { analyzeCurrentApp() } catch (e: Exception) {
                Log.w(TAG, "manual capture failed: ${e.javaClass.simpleName}")
                controller.showError("读取当前画面失败：${e.javaClass.simpleName}")
            }
        }
        // Bubble menu: file the open conversation as a knowledge-base contact.
        // Contacts are never created automatically — this is the one-tap way in.
        overlay?.onSaveContact = {
            val title = currentSnapshot?.title
            val pkg = activePkg ?: foregroundPkg ?: ""
            when {
                title.isNullOrBlank() -> overlay?.toast("当前会话没有标题，存不了")
                isTransientTitle(title) -> overlay?.toast("当前会话标题还没加载出来，稍后再试")
                else -> submit {
                    val msg = try {
                        KbStore.get(this).saveOrMergeContact(title, pkg)
                    } catch (e: Exception) { "保存失败：${e.javaClass.simpleName}" }
                    main.post { overlay?.toast(msg) }
                }
            }
        }
        // Bubble menu: one manual screenshot + OCR, for any app at all.
        overlay?.onOcrCapture = { ocrCaptureManual() }
        // Keep the process at foreground importance so MIUI does not freeze us.
        runCatching { KeepAliveService.start(this) }
        // Load the bundled OCR model now, off the main thread: the first
        // recognize() otherwise pays for it inside the screenshot callback.
        submit { MlKitOcr.warmUp() }
        // HyperOS may kill and restart us. On (re)connect, proactively re-show the
        // bubble for whatever chat is already open, so it comes back on its own
        // instead of waiting for the user to scroll.
        main.postDelayed({ if (prefs.enabled) runCatching { maybeCapture() } }, 900)
        Log.i(TAG, "capture service connected")
    }

    private var manualAnalyzeGeneration = 0

    private fun analyzeCurrentApp() {
        // The visible button/re-analyze control clears TRACE before dispatching,
        // so keep step 0 on screen instead of erasing the proof that touch arrived.
        traceStep("1 按下分析")
        if (!prefs.enabled) { overlay?.showError("Jev 已暂停，请先开启"); return }
        if (ocrBusy) {
            manualOcrRequested = true
            overlay?.showProgress("正在截屏辨识，完成后开始分析…"); return
        }
        if (analyzing) { overlay?.showProgress("分析中…"); return }

        val generation = ++manualAnalyzeGeneration
        overlay?.showProgress("正在读取当前 App…")
        val root = foregroundRoot() ?: run {
            overlay?.showError("找不到当前 App 窗口，请回到聊天页面再试"); return
        }
        val pkg = root.packageName?.toString().orEmpty()
        traceStep("2 前景 $pkg / window=${root.windowId}")
        if (pkg == PKG_WECHAT) { showWeChatDisabled(auto = false); return }

        // Douyin's accessibility hierarchy can get stuck in a partial cached
        // state: the same visible chat alternates between Adapter=null, rows=0,
        // and a populated message list until the Activity is re-entered. A manual
        // tap must recover in place instead of making the user leave the chat.
        if (pkg == PKG_DOUYIN) {
            overlay?.showProgress("正在重新读取抖音聊天…")
            // The callback is dispatched on ACTION_DOWN. Wait for the finger-up
            // before starting cache refresh/retries so the tap that launched this
            // analysis has completely finished.
            main.postDelayed({
                analyzeDouyinManualAttempt(generation, root.windowId, attempt = 0)
            }, 120)
            return
        }

        analyzeAdaptedRoot(root, pkg)
    }

    /** Normal one-shot manual path for adapted apps other than Douyin. */
    private fun analyzeAdaptedRoot(root: AccessibilityNodeInfo, pkg: String) {
        val adapter = adapters[pkg]
        if (adapter == null) {
            // Unadapted apps such as LINE keep their original explicit OCR path.
            ocrCaptureManual(root)
            return
        }
        val snap = adapter.extract(root, resources) ?: run {
            traceStep("3 Adapter 失败")
            overlay?.showError("已侦测到 $pkg，但找不到聊天列表或输入框"); return
        }
        traceStep("3 Adapter OK：rows=${snap.bubbleRects.size} msgs=${snap.messages.size} title=${!snap.title.isNullOrBlank()}")
        continueManualSnapshot(root, pkg, snap)
    }

    /**
     * Douyin-only recovery path for an incomplete/stale accessibility hierarchy.
     *
     * Android 13+ exposes an AccessibilityService cache clear API and root
     * prefetching. Clear the cache, reacquire a new root, and retry a few times
     * instead of reusing the same dead node tree forever. No screenshot/OCR is
     * attempted until at least one row has a reliable sender side.
     */
    private fun analyzeDouyinManualAttempt(
        generation: Int,
        expectedWindowId: Int,
        attempt: Int
    ) {
        if (generation != manualAnalyzeGeneration || destroyed || !prefs.enabled) return

        if (Build.VERSION.SDK_INT >= 33) {
            val cleared = runCatching { clearCache() }.getOrDefault(false)
            traceStep("3R 刷新节点 ${attempt + 1}/7 cache=${if (cleared) "OK" else "NO"}")
        } else {
            traceStep("3R 刷新节点 ${attempt + 1}/7")
        }

        // Cache invalidation is asynchronous from the app's point of view. Give
        // Douyin a short beat, then request a newly-prefetched hierarchy.
        main.postDelayed({
            if (generation != manualAnalyzeGeneration || destroyed || !prefs.enabled) return@postDelayed
            val root = freshForegroundRoot()
            if (root == null || root.packageName?.toString() != PKG_DOUYIN ||
                root.windowId != expectedWindowId
            ) {
                overlay?.showError("分析期间已离开原来的抖音会话，请重新分析")
                return@postDelayed
            }

            val snap = adapters[PKG_DOUYIN]?.extract(root, resources)
            val usable = snap != null && (snap.messages.isNotEmpty() || snap.bubbleRects.isNotEmpty())
            if (!usable) {
                val state = if (snap == null) "Adapter失败"
                    else "rows=0 title=${!snap.title.isNullOrBlank()}"
                traceStep("3R ${state} / attempt=${attempt + 1}")
                if (attempt < 6) {
                    overlay?.showProgress("抖音聊天节点暂时不完整，正在自动重试 ${attempt + 2}/7…")
                    main.postDelayed({
                        analyzeDouyinManualAttempt(generation, expectedWindowId, attempt + 1)
                    }, 180)
                } else {
                    overlay?.showError("抖音聊天节点持续不完整；已自动刷新并重试 7 次，仍无法取得可辨识的消息列")
                }
                return@postDelayed
            }

            traceStep("3 Adapter OK：rows=${snap!!.bubbleRects.size} msgs=${snap.messages.size} title=${!snap.title.isNullOrBlank()} retry=${attempt}")
            continueManualSnapshot(root, PKG_DOUYIN, snap)
        }, 90)
    }

    /** Continue a manual analysis after an adapter produced a usable snapshot. */
    private fun continueManualSnapshot(root: AccessibilityNodeInfo, pkg: String, snap: ChatSnapshot) {
        val target = targetFor(root, snap) ?: run {
            overlay?.showError("已侦测到 $pkg，但无法确认当前会话标题"); return
        }
        if (!prefs.isAllowed(snap.title)) {
            overlay?.showError("当前会话不在白名单内，请检查设置"); return
        }
        observeTarget(target)
        traceStep("4 会话确认 OK / window=${target.windowId}")
        if (snap.messages.isNotEmpty()) {
            currentSnapshot = snap
            pendingSnapshot = snap
            runAnalysis(manual = true)
        } else if (pkg == PKG_DOUYIN && snap.bubbleRects.isEmpty()) {
            // Defensive only: Douyin retries above should never pass an empty set.
            overlay?.showError("已侦测到抖音聊天，但没有找到可辨识的消息列")
        } else {
            traceStep("5 准备截屏 / rows=${snap.bubbleRects.size}")
            overlay?.showProgress("正在截屏辨识…")
            ocrCapture(snap.title, snap.bubbleRects, pkg, manual = true)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.enabled) { leaveConversation(); overlay?.hide(); return }

        val type = event.eventType
        // Decide "did we leave the chat app" from the REAL active window, not the
        // event's package. The event package can be an IME (e.g. com.tencent.wetype)
        // or the status bar while the chat app is still foreground — keying off it
        // made the bubble flicker (hide → re-show → hide…). rootInActiveWindow stays
        // on the chat app while the keyboard is up, so this is stable.
        //
        // An app with no adapter is NOT a reason to take the bubble away: the only
        // way into DingTalk / Telegram / anything else is the bubble menu's
        // "截屏识别一次", and a bubble that is gone cannot be tapped. So we park
        // the idle bubble there instead — still no automatic capture, no analysis.
        // The bubble does come off for places where it would only be in the way:
        // our own settings screens, the launcher, and the system UI.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = foregroundRoot()?.packageName?.toString()
            // WeChat is fully disabled: never read/screenshot/OCR/fill here, only
            // show the one-time "not supported" notice and stop. Checked before the
            // generic no-adapter branch because WeChat is no longer in `adapters`.
            if (fg == PKG_WECHAT) { foregroundPkg = fg; showWeChatDisabled(auto = true); return }
            if (fg != null && fg !in adapters) {
                val target = foregroundRoot()?.let { targetFor(it) }
                if (session.target != target) leaveConversation()
                else if (target != null && (ocrBusy || analyzing)) return
                foregroundPkg = fg
                wechatNoticeShown = false // left WeChat → allow the notice again next visit
                val drop = fg == packageName ||
                    fg.contains("launcher", ignoreCase = true) ||
                    fg == "com.miui.home" ||
                    fg == "com.android.systemui"
                if (drop) overlay?.hide() else overlay?.showIdle(null)
                return
            }
        }

        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> maybeCapture()
        }
    }

    private fun maybeCapture() {
        val root = foregroundRoot() ?: run {
            // Overlay transitions can briefly leave no application root.
            if (ocrBusy || analyzing) return
            leaveConversation(); overlay?.hide(); return
        }
        val pkg = root.packageName?.toString()
        // WeChat is fully disabled — no tree read, no screenshot, no OCR, no fill.
        // A content-changed / scrolled event in WeChat only re-shows the one-time
        // notice (deduped); it must never reach an adapter or the OCR path.
        if (pkg == PKG_WECHAT) { showWeChatDisabled(auto = true); return }
        wechatNoticeShown = false // any other foreground → allow the notice again next WeChat visit
        // Apps with no adapter are never handled automatically (v1.3 revision):
        // the only way in for them is the bubble menu's "截屏识别一次".
        val adapter = adapters[pkg] ?: run {
            if (session.target != null && session.target != targetFor(root)) leaveConversation()
            return
        }
        // Outside a chat window (the conversation list, a profile, settings…) the
        // adapter returns null. That is NOT a reason to show nothing: an adapted
        // app must behave at least as well as an unadapted one, which parks an idle
        // bubble so the menu stays reachable. Without this, opening QQ / Feishu on
        // their list screen produced no bubble at all.
        val rawSnapshot = adapter.extract(root, resources)
        val target = rawSnapshot?.let { targetFor(root, it) }
        if (rawSnapshot == null || target == null) {
            // Douyin can temporarily expose an incomplete tree while our panel
            // is hiding/restoring. Keep the live request and its progress intact.
            if ((ocrBusy || analyzing) && session.sameWindow(pkg, root.windowId)) return
            leaveConversation(); overlay?.showIdle(null); return
        }
        observeTarget(target)
        // Overlay/OCR events in this same chat must not replace the progress panel.
        if (ocrBusy || analyzing) return
        // Use only this window's title; never inherit another conversation's title.
        val snapshot = rawSnapshot
        if (!prefs.isAllowed(snapshot.title)) { leaveConversation(); overlay?.hide(); return }
        // In a chat window but the tree holds no text (Feishu draws its bodies,
        // WeChat hides them when the disguise fails) → screenshot + OCR, subject
        // to ScreenCapture's own >=1s throttle and failure backoff.
        if (snapshot.messages.isEmpty()) {
            // In a chat window, but the tree carries no text (Feishu draws its
            // message bodies). Park the bubble BEFORE attempting OCR, so the user
            // still has something to tap when OCR is off, deduped, or comes back
            // empty — previously all three cases left the screen with no bubble.
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            if (prefs.ocrFallback) {
                // Gate BEFORE the shot, not after the OCR. Feishu's tree is empty
                // on every content-changed event, and a successful shot resets the
                // failure backoff — so without this the caret blinking or an
                // "online" badge flipping keeps a screenshot going out every
                // second forever. The picture can only differ if the bubbles moved
                // or the conversation changed, and that is exactly what the
                // signature measures.
                val sig = ocrSignature(pkg ?: "", snapshot.title, snapshot.bubbleRects)
                if (sig == lastOcrSignature && overlay?.isShowing() == true) return
                if (ocrBusy) return
                lastOcrSignature = sig
                ocrCapture(snapshot.title, snapshot.bubbleRects, pkg ?: "", manual = false)
            }
            return
        }

        // Switching to another adapted app resets the dedupe signature, so two apps
        // whose last few messages happen to match cannot swallow each other.
        if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

        currentSnapshot = snapshot
        val sig = snapshot.signature()
        val showing = overlay?.isShowing() == true
        // Same content and the bubble is already up → nothing to do.
        if (sig == lastSignature && showing) return
        // Same content but the bubble is gone (killed by MIUI, or we left and came
        // back) → just put the bubble back, do NOT re-analyze (saves tokens/time).
        if (sig == lastSignature && !showing) { overlay?.showIdle(snapshot.title); return }
        // Anything else reaching here is a genuinely different conversation (new
        // app, or new content in this one) — a leftover judgment/candidates from
        // whatever was shown before must not leak into it.
        cancelAnalysis()
        lastSignature = sig
        Log.d(TAG, "snapshot[$pkg] title=${snapshot.title} n=${snapshot.messages.size} " +
            snapshot.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.text.length}" }) // sides + lengths only, never content

        // Trigger only when the newest message is from the other person, and only
        // if auto-analyze is on. Otherwise show the idle bubble (tap to analyze).
        if (snapshot.latestFrom != "other" || !prefs.autoAnalyze) {
            overlay?.showIdle(snapshot.title); return
        }

        pendingSnapshot = snapshot
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, 800) // debounce bursts of content-changed events
    }

    /**
     * Foreground is WeChat, which is fully disabled: no node-tree read, no
     * screenshot, no OCR, no fill. We only surface a one-time notice saying WeChat
     * itself blocks reading. [auto] events (accessibility callbacks)
     * show it once per WeChat visit — [wechatNoticeShown] dedupes them; a manual
     * bubble tap ([auto] = false) always shows it. Never gated by the user's
     * whitelist (it is an info notice, not a read) and only ever reached under
     * the WeChat package.
     */
    private fun showWeChatDisabled(auto: Boolean) {
        leaveConversation()
        if (auto && wechatNoticeShown) return
        wechatNoticeShown = true
        // No popup panel in WeChat — a full card is intrusive when others can see
        // the screen. Take the overlay off WeChat entirely and show the reason
        // once as a small transient toast.
        overlay?.hide()
        overlay?.toast(WECHAT_DISABLED_MSG)
    }

    /** A placeholder title an app shows only for a moment (e.g. X's "连接中…"
     *  right after opening a DM thread) — never a real conversation title.
     *  Blank/null counts too, so a caller can always fall back the same way. */
    private fun isTransientTitle(t: String?): Boolean {
        val trimmed = t?.trim()?.removeSuffix("…")?.removeSuffix("...")?.trim()
        if (trimmed.isNullOrEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT_TITLE_WORDS.any { lower.contains(it.lowercase()) }
    }

    private fun runAnalysis(manual: Boolean = false) {
        if (session.target?.pkg == "com.ss.android.ugc.aweme.lite") traceStep("14 runAnalysis")
        val snapshot = pendingSnapshot ?: run {
            if (manual) overlay?.showError("没有可分析的消息，请重新读取"); return
        }
        if (analyzing || destroyed || !prefs.enabled) {
            if (manual) overlay?.showError("分析服务忙碌或已暂停，请稍后重试"); return
        }
        val previous = session.token() ?: run {
            if (manual) overlay?.showError("无法确认当前会话，请重新读取"); return
        }
        if (!prefs.hasKey()) { overlay?.showError("未设置判断接口密钥，去设置里填"); return }
        analyzing = true
        overlay?.showLoading()
        awaitCurrentTarget(previous, "准备分析") {
            startAnalysis(snapshot)
        }
    }

    private fun startAnalysis(snapshot: ChatSnapshot) {
        if (session.target?.pkg == "com.ss.android.ugc.aweme.lite") traceStep("15 startAnalysis")
        val token = session.begin() ?: run { analyzing = false; return }
        overlay?.setNote(snapshot.note)
        val client = JevClient(prefs)
        val rel = prefs.relationship
        submitAnalysis {
            val ctx = try {
                ContextBuilder.build(this, snapshot, token.target.pkg, prefs)
            } catch (e: Exception) {
                Log.w(TAG, "context build failed: ${e.javaClass.simpleName}"); null
            }
            main.post {
                awaitCurrentTarget(token, "准备模型分析") {
                    overlay?.setContextInfo(ctx?.notes?.size ?: 0, ctx?.history?.size ?: 0)
                    var remaining = 2
                    fun completed() {
                        remaining--
                        if (remaining == 0) {
                            analyzing = false
                            analysisTasks.clear()
                        }
                    }
                    submitAnalysis {
                        val judgment = client.judge(snapshot, rel, ctx)
                        main.post {
                            if (token.target.pkg == "com.ss.android.ugc.aweme.lite") traceStep("16 模型判断回传 / error=${judgment.error != null}")
                            awaitCurrentTarget(token, "接收判断结果") {
                                if (judgment.error != null) overlay?.showError(judgment.error)
                                else overlay?.showJudgment(judgment)
                                completed()
                            }
                        }
                    }
                    submitAnalysis {
                        var replyError: String? = null
                        val ranked = try { client.draftAndRank(snapshot, rel, ctx) } catch (e: Exception) {
                            replyError = e.message ?: e.javaClass.simpleName
                            emptyList()
                        }
                        main.post {
                            if (token.target.pkg == "com.ss.android.ugc.aweme.lite") traceStep("17 候选回复回传 / n=${ranked.size} error=${replyError != null}")
                            awaitCurrentTarget(token, "接收候选回复") {
                                overlay?.showReplies(ranked, replyError) { text -> fillInput(token, text) }
                                completed()
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ OCR

    /**
     * Bubble menu → "截屏识别一次". Works on ANY app, adapted or not: one whole
     * screen shot, every line OCR'd, lines grouped into pseudo-bubbles by line
     * spacing. Nobody can tell who said what this way, so everything is filed as
     * the other person and the panel says so.
     */
    private fun ocrCaptureManual(root: AccessibilityNodeInfo? = foregroundRoot()) {
        val pkg = root?.packageName?.toString() ?: run {
            overlay?.showError("找不到当前 App 窗口，请回到聊天页面再试"); return
        }
        // WeChat is fully disabled: a manual "截屏识别一次" in WeChat must NOT take
        // a screenshot — just show the notice (a manual tap always shows it).
        if (pkg == PKG_WECHAT) { showWeChatDisabled(auto = false); return }
        // Top bar text, if this app has one we can read; else the first OCR line.
        val title = root?.let {
            findTitleInActionBar(it, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources, 0.15, 0.85)
        }
        val target = root?.let { targetFor(it) } ?: run {
            overlay?.showError("无法确认当前会话，请等待标题加载后重试")
            return
        }
        observeTarget(target)
        overlay?.showProgress("正在截屏辨识…")
        ocrCapture(title, emptyList(), pkg, manual = true)
    }

    /**
     * What the screen would look like to a camera, as far as the tree can tell.
     *
     * Feishu: the conversation title plus every bubble rectangle and its side —
     * the bubbles move whenever the list scrolls or a message arrives, and stay
     * put when only chrome (caret, presence dot, timestamp) redraws. Apps that
     * give us no rectangles fall back to package + title, which at least stops a
     * burst of events on one screen from becoming a burst of screenshots.
     */
    private fun ocrSignature(pkg: String, title: String?, rects: List<BubbleRect>): String {
        if (rects.isEmpty()) return pkg + "|" + (title ?: "")
        return (title ?: "") + "|" + rects.joinToString(";") { br ->
            val r = br.rect
            "${r.left},${r.top},${r.right},${r.bottom},${br.side}"
        }
    }

    /**
     * Screenshot, then either OCR each known bubble rect (Feishu: the tree knows
     * where the bubbles are and who sent them, just not what they say) or OCR
     * the whole screen (everything else).
     */
    private fun ocrCapture(treeTitle: String?, rects: List<BubbleRect>, pkg: String, manual: Boolean) {
        if (ocrBusy || destroyed || !prefs.enabled) {
            if (manual) overlay?.showError("截屏服务忙碌或已暂停，请稍后重试"); return
        }
        val token = session.token() ?: run {
            if (manual) overlay?.showError("无法确认截屏会话，请重试"); return
        }
        ocrBusy = true
        ocrToken = token
        manualOcrRequested = manual
        if (pkg == "com.ss.android.ugc.aweme.lite") traceStep("6 OCR 工作建立 / rows=${rects.size}")
        val timeout = Runnable {
            if (ocrToken == token) {
                val requested = manualOcrRequested
                completeOcr(token)
                if (session.accepts(token)) {
                    session.invalidate()
                    if (requested) overlay?.showError("文字辨识超时，请重新分析")
                    else overlay?.showIdle(treeTitle)
                }
            }
        }
        ocrTimeout = timeout
        main.postDelayed(timeout, 15000)
        awaitCurrentTarget(token, "截屏前") {
            if (pkg == "com.ss.android.ugc.aweme.lite") traceStep("7 呼叫系统截图")
            screenCapture.capture(targetWindowId = token.target.windowId,
                shouldCapture = { canCaptureWindow(token) }) { res ->
                if (!session.accepts(token)) {
                    if (res is ScreenCapture.Result.Ok) res.bitmap.recycle()
                    completeOcr(token)
                    return@capture
                }
                when (res) {
                    is ScreenCapture.Result.Failed -> {
                        if (pkg == "com.ss.android.ugc.aweme.lite") traceStep("8 截图失败 code=${res.code}")
                        if (!completeOcr(token)) return@capture
                        if (!manual) lastOcrSignature = ""
                        val transient = res.code == ScreenCapture.CODE_THROTTLED || res.code == 3
                        if (manual || manualOcrRequested || !transient)
                            overlay?.showError("截屏步骤：${res.humanMessage}")
                        else overlay?.showIdle(treeTitle)
                    }
                    is ScreenCapture.Result.Ok -> {
                        if (pkg == "com.ss.android.ugc.aweme.lite") traceStep("8 截图成功 ${res.bitmap.width}x${res.bitmap.height}")
                        awaitCurrentTarget(token, "截屏后确认会话", onInvalid = { check ->
                            res.bitmap.recycle()
                            completeOcr(token)
                            abortRequest(token, "截屏后确认会话", check)
                        }) {
                            if (manualOcrRequested) overlay?.showProgress("正在辨识消息文字…")
                            if (pkg == "com.ss.android.ugc.aweme.lite") traceStep("9 开始逐列 OCR / rows=${rects.size}")
                            ocr.scaleX = res.scaleX; ocr.scaleY = res.scaleY
                            ocr.originX = res.originX; ocr.originY = res.originY
                            if (rects.isNotEmpty()) {
                                val fresh = rootForPackage(pkg)?.let { currentRoot ->
                                    when (pkg) {
                                        "com.ss.android.lark" -> collectFeishuBubbleRects(currentRoot, resources)
                                        "com.ss.android.ugc.aweme.lite" -> collectDouyinLiteBubbleRects(currentRoot, resources)
                                        else -> emptyList()
                                    }
                                }
                                ocrByRects(res.bitmap, if (fresh.isNullOrEmpty()) rects else fresh,
                                    treeTitle, pkg, manual, token)
                            } else ocrWholeScreen(res.bitmap, treeTitle, pkg, manual, token)
                        }
                    }
                }
            }
        }
    }

    /** One OCR pass per bubble rectangle; each rect becomes exactly one message. */
    private fun ocrByRects(bmp: Bitmap, rects: List<BubbleRect>, title: String?, pkg: String, manual: Boolean, token: ConversationSession.Token) {
        val sx = ocr.scaleX; val sy = ocr.scaleY
        // Screen -> bitmap: drop the window origin first. A window shot does not
        // start at (0,0) in split screen or when it excludes the status bar.
        val ox = ocr.originX; val oy = ocr.originY
        val out = arrayOfNulls<Msg>(rects.size)
        var remaining = rects.size
        rects.forEachIndexed { i, br ->
            val region = Rect(
                ((br.rect.left - ox) * sx).toInt(), ((br.rect.top - oy) * sy).toInt(),
                ((br.rect.right - ox) * sx).toInt(), ((br.rect.bottom - oy) * sy).toInt())
            ocr.recognize(bmp, region) { lines ->
                val rawText = lines.joinToString(" ") { it.text }
                val text = if (pkg == "com.ss.android.ugc.aweme.lite") cleanDouyinBubbleText(rawText)
                    else cleanBubbleText(rawText)
                if (text.isNotEmpty()) out[i] = Msg(br.side, text)
                remaining--
                if (remaining == 0) {
                    val recognized = out.filterNotNull()
                    if (pkg == "com.ss.android.ugc.aweme.lite") traceStep("10 OCR 完成 / recognized=${recognized.size}/${rects.size}")
                    runCatching { bmp.recycle() }
                    finishOcrSnapshot(ChatSnapshot(title, recognized), pkg, manual = manual, token = token)
                }
            }
        }
    }

    /** Whole screen minus the top bar and the input area, grouped by line gaps. */
    private fun ocrWholeScreen(bmp: Bitmap, treeTitle: String?, pkg: String, manual: Boolean, token: ConversationSession.Token) {
        val region = Rect(0, (bmp.height * TOP_CROP).toInt(), bmp.width, (bmp.height * BOTTOM_CROP).toInt())
        ocr.recognize(bmp, region) { lines ->
            runCatching { bmp.recycle() }
            val msgs = groupOcrLines(lines)
            val title = treeTitle?.takeIf { it.isNotBlank() }
                ?: lines.firstOrNull()?.text?.trim()?.take(24)
            finishOcrSnapshot(ChatSnapshot(title, msgs, note = OCR_NOTE), pkg, manual, token)
        }
    }

    /**
     * OCR lines → "bubbles": a gap larger than 1.2x the previous line's height
     * starts a new one. Side is unknowable from a flat screen read, so every
     * group is filed as the other person (and [OCR_NOTE] says so on the panel).
     */
    private fun groupOcrLines(lines: List<OcrLine>): List<Msg> {
        val usable = lines
            .filter { it.text.isNotBlank() && !PURE_TIME.matches(it.text.trim()) }
            .sortedBy { it.bounds.top }
        val out = ArrayList<Msg>()
        val buf = StringBuilder()
        var prev: OcrLine? = null
        for (l in usable) {
            val p = prev
            if (p != null) {
                val gap = l.bounds.top - p.bounds.bottom
                val lineHeight = maxOf(p.bounds.height(), 1)
                if (gap > lineHeight * 1.2f) {
                    if (buf.isNotEmpty()) { out.add(Msg("other", buf.toString())); buf.setLength(0) }
                }
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(l.text.trim())
            prev = l
        }
        if (buf.isNotEmpty()) out.add(Msg("other", buf.toString()))
        return out
    }

    /** Strip the read receipt and the timestamp Feishu glues onto a bubble. */
    private fun cleanBubbleText(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (tail in arrayOf("已读", "未读")) {
                if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true }
            }
            TAIL_TIME.find(t)?.let { t = t.substring(0, it.range.first).trim(); changed = true }
        }
        return t
    }

    /** Douyin voice rows are ignored until the user manually expands the transcript. */
    private fun cleanDouyinBubbleText(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return ""
        t = t.replace(Regex("""(^|\s)[▶▷►]?\s*\d{1,3}\s*["″”'](?=\s|$)"""), " ").trim()
        t = t.replace(Regex("""(周[一二三四五六日天]|今天|昨天)\s*\d{1,2}[:：]\d{2}"""), " ").trim()
        t = t.replace(Regex("""\s+"""), " ").trim()
        if (t.isEmpty() || Regex("""^[▶▷►•·\s\d"″”']+$""").matches(t)) return ""
        return t
    }

    /** Shared tail of both OCR paths: dedupe, then analyze or park the bubble. */
    private fun finishOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean, token: ConversationSession.Token) {
        if (pkg == "com.ss.android.ugc.aweme.lite") traceStep("11 finishOcrSnapshot / msgs=${snapshot.messages.size}")
        if (ocrToken != token) {
            if (pkg == "com.ss.android.ugc.aweme.lite") traceStep("11b OCR token 已失效")
            return
        }
        awaitCurrentTarget(token, "文字辨识完成") {
            if (!completeOcr(token)) return@awaitCurrentTarget
            acceptOcrSnapshot(snapshot, pkg, manual)
        }
    }

    private fun acceptOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean) {
        if (pkg == "com.ss.android.ugc.aweme.lite") traceStep("12 接受 OCR snapshot / msgs=${snapshot.messages.size}")
        val requested = manual || manualOcrRequested
        // Counts only — OCR'd chat text never goes to logcat.
        Log.i(TAG, "ocr[$pkg] msgs=${snapshot.messages.size} manual=$manual")
        if (snapshot.messages.isEmpty()) {
            if (requested) overlay?.showError("这一屏没认出文字")
            return
        }
        if (!prefs.isAllowed(snapshot.title)) { leaveConversation(); overlay?.hide(); return }

        if (pkg.isNotEmpty() && pkg != activePkg) { activePkg = pkg; lastSignature = "" }
        currentSnapshot = snapshot
        val sig = snapshot.signature()
        // Manual taps always re-run; the automatic path dedupes like the tree path.
        if (!requested && sig == lastSignature) {
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            return
        }
        // Same rule as the tree path: past this point the conversation is either
        // new or being force-refreshed, so drop whatever was shown before.
        cancelAnalysis()
        lastSignature = sig

        val auto = prefs.ocrAutoAnalyze && prefs.autoAnalyze && snapshot.latestFrom == "other"
        if (requested || auto) {
            if (pkg == "com.ss.android.ugc.aweme.lite") traceStep("13 准备模型分析")
            pendingSnapshot = snapshot
            main.removeCallbacks(debounce)
            runAnalysis(manual = requested)
        } else {
            overlay?.setNote(snapshot.note)
            overlay?.showIdle(snapshot.title)
        }
    }

    /** Resolve only the originating chat's input, never an arbitrary foreground editor. */
    private fun inputFor(token: ConversationSession.Token): AccessibilityNodeInfo? {
        if (!isCurrent(token)) return null
        val root = rootForPackage(token.target.pkg) ?: return null
        if (targetFor(root) != token.target) return null
        val input = when (token.target.pkg) {
            "com.tencent.mobileqq" -> root.findAccessibilityNodeInfosByViewId("com.tencent.mobileqq:id/input").firstOrNull()
            "com.ss.android.lark" -> root.findAccessibilityNodeInfosByViewId("com.ss.android.lark:id/kb_rich_text_content").firstOrNull()
            "com.ss.android.ugc.aweme.lite" ->
                root.findAccessibilityNodeInfosByViewId(DouyinLiteAdapter.INPUT_ID).firstOrNull() ?: findEditable(root)
            "com.twitter.android" -> findEditable(root)
            else -> null // Unknown apps support explicit clipboard copy, not unverified writes.
        }
        // Re-read the node after SET_TEXT: the accessibility cache may still
        // contain the previous draft even though the write already succeeded.
        input ?: return null
        if (!input.refresh()) return null
        return input.takeIf { it.isVisibleToUser && it.isEnabled && !it.isPassword }
    }

    /** Fill without blocking the main thread; all retries re-resolve the original target. */
    private fun fillInput(token: ConversationSession.Token, text: String) {
        fun finish(ok: Boolean) {
            if (!isCurrent(token)) return
            if (ok) overlay?.toast("已填入，确认后自己发送")
            else { copyToClipboard(text); overlay?.toast("已复制，长按输入框粘贴") }
        }
        if (!isCurrent(token)) { overlay?.toast("会话已变化，请重新分析后填入"); return }
        if (inputFor(token) == null) { finish(false); return }
        GuardedInputWriter(
            resolve = {
                inputFor(token)?.let { node ->
                    object : GuardedInputWriter.Input {
                        override val text: String? get() = node.text?.toString()
                        override fun setText(text: String) = setTextRaw(node, text)
                        override fun focus() { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        override fun paste() { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
                    }
                }
            },
            later = { delay, action -> main.postDelayed({ action() }, delay) },
            copy = { copyToClipboard(it) },
            complete = { finish(it) }
        ).fill(text)
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        var found: AccessibilityNodeInfo? = null
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            if (node.isEditable && node.isVisibleToUser && node.isEnabled && !node.isPassword) {
                if (found != null) return null // Ambiguous editor: clipboard only.
                found = node
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return if (stack.isEmpty()) found else null
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
    }

    override fun onInterrupt() {
        leaveConversation()
        overlay?.hide()
    }

    override fun onDestroy() {
        destroyed = true
        getSharedPreferences(Prefs.PREFS_MAIN, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferencesListener)
        leaveConversation()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
        // Tear the overlay down and cut its callback so a stale button tap can
        // never call back into this dead instance.
        overlay?.onManualAnalyze = null
        overlay?.onSaveContact = null
        overlay?.onOcrCapture = null
        overlay?.hide()
        if (liveOverlay === overlay) liveOverlay = null
        overlay = null
        worker.shutdownNow()
    }

    companion object {
        @Volatile private var liveOverlay: OverlayController? = null
        private const val TAG = "JEVASSIST"

        /** WeChat's package. Reading it (node tree / screenshot / OCR) is what
         *  trips WeChat's anti-screenshot risk control, so it is fully disabled:
         *  no adapter, no capture, only a one-time "not supported" notice. */
        private const val PKG_WECHAT = "com.tencent.mm"
        private const val PKG_DOUYIN = "com.ss.android.ugc.aweme.lite"

        /** Shown once when the foreground is WeChat. Plain words, full-width
         *  punctuation; steers the user to a still-supported app. */
        private const val WECHAT_DISABLED_MSG =
            "微信已限制读取，请在别的软件上使用"

        /** Whole-screen OCR keeps the middle: no action bar, no input area. */
        private const val TOP_CROP = 0.12f
        private const val BOTTOM_CROP = 0.84f

        /** Said on the panel whenever a snapshot came from flat-screen OCR. */
        private const val OCR_NOTE = "OCR 未分边，把全部消息当作对方所说"

        private val PURE_TIME = Regex("""\d{1,2}[:：]\d{2}""")
        private val TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}$""")

        /** Transient placeholder titles apps show while a chat page is still
         *  connecting/loading — see [isTransientTitle]. Matched as a substring,
         *  case-insensitive, after trimming a trailing ellipsis. */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )
    }
}
