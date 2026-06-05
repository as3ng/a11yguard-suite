package com.fraudintel.harness

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Test instrumentation that drives accessibility automation against the foreground app under test,
 * used to validate detection. It acts only on demand, performs no networking, persistence, or data
 * collection, and is intentionally not declared as an accessibility tool.
 */
class HarnessAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

    /** Set by the console to stream step logs to its UI. */
    @Volatile var onLog: ((String) -> Unit)? = null

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* drives input; does not observe */ }
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    /** Fill the first two editable fields and click the confirm control, [gapMs] apart. */
    fun runFillAndConfirm(user: String, pass: String, confirmText: String, gapMs: Long) {
        Thread {
            val edits = collect { it.isEditable }
            if (edits.size >= 2) {
                setText(edits[0], user); sleep(gapMs)
                setText(edits[1], pass); sleep(gapMs)
            } else {
                Log.w(TAG, "expected >=2 editable fields, found ${edits.size}")
            }
            sleep(gapMs)
            clickByText(confirmText)
            Log.i(TAG, "fill+confirm done (gap=${gapMs}ms)")
        }.start()
    }

    /** Click the confirm control with an injected gesture tap at its centre. */
    fun runGestureConfirm(confirmText: String) {
        val node = collect { it.isClickable && it.text?.toString()?.contains(confirmText, true) == true }
            .firstOrNull() ?: run { Log.w(TAG, "confirm node not found"); return }
        val b = Rect().also { node.getBoundsInScreen(it) }
        if (b.isEmpty) { Log.w(TAG, "confirm bounds empty: $b"); return }
        tap(b.exactCenterX(), b.exactCenterY())
    }

    /** Fill the first editable field via the clipboard and ACTION_PASTE rather than ACTION_SET_TEXT. */
    fun runPasteInjection(text: String) {
        val edit = collect { it.isEditable }.firstOrNull() ?: run { Log.w(TAG, "no editable field"); return }
        pasteInto(edit, text)
        Log.i(TAG, "clipboard-paste injection triggered")
    }

    /** Set the clipboard and perform ACTION_PASTE on the given node. */
    private fun pasteInto(node: AccessibilityNodeInfo, text: String) {
        main.post {
            runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("x", text))
            }
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            main.postDelayed({ node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }, 80L)
        }
    }

    /** Walk the active window node tree repeatedly to exercise node-read detection. */
    fun runTreeScrape(rounds: Int) {
        Thread {
            repeat(rounds) {
                val nodes = collect { true }
                nodes.forEach { runCatching { it.text; it.className; it.viewIdResourceName } }
                sleep(60L)
            }
            Log.i(TAG, "tree scrape done rounds=$rounds")
        }.start()
    }

    /**
     * Show an opaque, focusable, full-screen accessibility overlay for [durationMs]. It is focusable
     * so the covered window observes focus loss; non-focusable overlays are not observable by the
     * covered app and are mitigated by Window.setHideOverlayWindows on API 31+. No input is captured.
     */
    @Suppress("DEPRECATION")
    fun runConcealmentOverlay(durationMs: Long) {
        main.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = View(this).apply { setBackgroundColor(Color.BLACK) }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.OPAQUE
            )
            runCatching {
                wm.addView(v, lp)
                main.postDelayed({ runCatching { wm.removeView(v) } }, durationMs)
                Log.i(TAG, "concealment overlay shown ${durationMs}ms")
            }.onFailure { Log.w(TAG, "overlay add failed: ${it.message}") }
        }
    }

    /**
     * Tap the confirm control with a curved gesture and randomized duration, to exercise detection
     * that does not rely on gesture kinematics.
     */
    fun runHumanPacedReplay(confirmText: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { Log.w(TAG, "dispatchGesture needs API 24"); return }
        val node = collect { it.isClickable && it.text?.toString()?.contains(confirmText, true) == true }
            .firstOrNull() ?: run { Log.w(TAG, "confirm node not found"); return }
        val b = Rect().also { node.getBoundsInScreen(it) }
        if (b.isEmpty || b.width() <= 0 || b.height() <= 0) { Log.w(TAG, "confirm bounds invalid: $b"); return }
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - 1).coerceAtLeast(1).toFloat()
        val maxY = (dm.heightPixels - 1).coerceAtLeast(1).toFloat()
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        main.post {
            val rnd = java.util.Random()
            // Clamp every point on-screen; StrokeDescription rejects negative or off-display bounds.
            fun clampX(x: Float) = x.coerceIn(0f, maxX)
            fun clampY(y: Float) = y.coerceIn(0f, maxY)
            val path = Path().apply {
                moveTo(clampX(cx - 80f), clampY(cy + 150f))
                quadTo(
                    clampX(cx - 10f + (rnd.nextInt(20) - 10)), clampY(cy + 50f),
                    clampX(cx + (rnd.nextInt(10) - 5)), clampY(cy)
                )
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 220L + rnd.nextInt(140))
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            Log.i(TAG, "human-paced curved replay dispatched")
        }
    }

    /**
     * Run paste fill, a concealment overlay, a node-read burst, and an injected confirm in sequence.
     * The leading delay lets the triggering touch fall outside the human-input correlation window.
     */
    fun runFullChain(confirmText: String) {
        Thread {
            sleep(1800L)
            val edits = collect { it.isEditable }
            if (edits.size >= 2) {
                pasteInto(edits[0], "user"); sleep(140L)
                pasteInto(edits[1], "secret"); sleep(140L)
            }
            runConcealmentOverlay(1500L)
            sleep(1700L)
            repeat(10) {
                collect { true }.forEach { runCatching { it.text; it.className } }
                sleep(40L)
            }
            sleep(120L)
            runGestureConfirm(confirmText)
            Log.i(TAG, "full chain done")
        }.start()
    }

    /**
     * Exercise sustained, human-paced automation against the foreground app: jittered swipes,
     * node-tree reads, and a clipboard step. It does not open or read other applications and does not
     * download or install anything; the clipboard holds a placeholder value.
     */
    fun runReconChain() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { Log.w(TAG, "recon needs API 24"); return }
        Thread {
            val rnd = java.util.Random()
            fun pace() = sleep(700L + rnd.nextInt(1300)) // 0.7-2.0s, jittered
            val dm = resources.displayMetrics
            val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
            Log.i(TAG, "recon: begin (human-paced, in-app scope)")
            pace(); swipe(w * 0.5f, h * 0.72f, w * 0.5f, h * 0.28f, 300L); Log.i(TAG, "recon: swipe up")
            pace(); scrapeOnce(); Log.i(TAG, "recon: read screen")
            pace(); swipe(w * 0.2f, h * 0.5f, w * 0.8f, h * 0.5f, 300L); Log.i(TAG, "recon: swipe right")
            pace(); swipe(w * 0.8f, h * 0.5f, w * 0.2f, h * 0.5f, 300L); Log.i(TAG, "recon: swipe left")
            pace(); scrapeOnce()
            pace(); swipe(w * 0.5f, h * 0.28f, w * 0.5f, h * 0.72f, 300L); Log.i(TAG, "recon: swipe down")
            pace()
            runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("x", "https://example.invalid/placeholder"))
            }
            Log.i(TAG, "recon: clipboard set to placeholder (no download or install)")
            pace(); collect { it.isEditable }.firstOrNull()?.let { pasteInto(it, "https://example.invalid/placeholder") }
            Log.i(TAG, "recon: done (navigation only; no other-app access, no install)")
        }.start()
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        main.post {
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
    }

    private fun scrapeOnce() {
        collect { true }.forEach { runCatching { it.text; it.className } }
    }

    /** Apply runtime AccessibilityServiceInfo flags chosen by the operator. */
    fun applyServiceFlags(flags: Int) {
        runCatching {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = flags
            serviceInfo = info
            log("applied flags = 0x${flags.toString(16)}")
        }.onFailure { log("applyServiceFlags failed: ${it.message}") }
    }

    /**
     * Wait for the named package to come to the foreground, then enumerate (read-only) or act on the
     * chosen resource at human pace. The package is launched by the caller.
     */
    fun provision(cfg: ProvisionConfig) {
        Thread {
            log("provision: action=${cfg.action} match=${if (cfg.matchById) "id" else "text"} query='${cfg.query}'")
            if (cfg.pkg.isNotBlank() && !waitForForeground(cfg.pkg, 8000L)) {
                log("warning: ${cfg.pkg} not foreground within 8s; acting on current window")
            }
            paceDelay(cfg)
            if (cfg.action == "enum") enumerateAndLog(cfg) else actOnTarget(cfg)
            log("done")
        }.start()
    }

    /** Replay a recorded macro: wait for the target foreground once, then run each step at its pace. */
    fun provisionSequence(pkg: String, steps: List<MacroStep>) {
        Thread {
            log("replay: ${steps.size} step(s)")
            if (pkg.isNotBlank() && !waitForForeground(pkg, 8000L)) {
                log("warning: $pkg not foreground within 8s; using current window")
            }
            steps.forEachIndexed { i, s ->
                val cfg = ProvisionConfig("", s.matchById, s.query, s.action, s.text, s.minDelayMs, s.maxDelayMs)
                log("step ${i + 1}/${steps.size}: ${cfg.action} match=${if (cfg.matchById) "id" else "text"} '${cfg.query}'")
                paceDelay(cfg)
                if (cfg.action == "enum") enumerateAndLog(cfg) else actOnTarget(cfg)
            }
            log("replay done")
        }.start()
    }

    private fun waitForForeground(pkg: String, timeoutMs: Long): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (rootInActiveWindow?.packageName?.toString() == pkg) return true
            sleep(150L)
        }
        return false
    }

    private fun findNodes(byId: Boolean, query: String): List<AccessibilityNodeInfo> {
        if (query.isBlank()) return emptyList()
        val root = rootInActiveWindow ?: return emptyList()
        return runCatching {
            if (byId) root.findAccessibilityNodeInfosByViewId(query) ?: emptyList()
            else root.findAccessibilityNodeInfosByText(query) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun enumerateAndLog(cfg: ProvisionConfig) {
        val nodes = if (cfg.query.isBlank())
            collect { it.isClickable || it.isEditable || !it.text.isNullOrEmpty() }
        else findNodes(cfg.matchById, cfg.query)
        log("enumerate: ${nodes.size} node(s)" + if (cfg.query.isBlank()) " (interactive/text on screen)" else "")
        nodes.take(60).forEachIndexed { i, n ->
            val rid = runCatching { n.viewIdResourceName }.getOrNull()
            val txt = runCatching { n.text?.toString() }.getOrNull()
            val r = Rect().also { n.getBoundsInScreen(it) }
            log("  [$i] id=$rid text=${txt?.take(40)} cls=${n.className} click=${n.isClickable} edit=${n.isEditable} b=$r")
        }
    }

    private fun actOnTarget(cfg: ProvisionConfig) {
        val node = findNodes(cfg.matchById, cfg.query).firstOrNull()
            ?: run { log("target not found: '${cfg.query}'"); return }
        val b = Rect().also { node.getBoundsInScreen(it) }
        when (cfg.action) {
            "tap" -> if (b.isEmpty) log("bounds empty") else {
                tap(b.exactCenterX(), b.exactCenterY()); log("tap at ${b.exactCenterX().toInt()},${b.exactCenterY().toInt()}")
            }
            "click" -> log("ACTION_CLICK -> ${node.performAction(AccessibilityNodeInfo.ACTION_CLICK)}")
            "settext" -> log("ACTION_SET_TEXT -> ${setText(node, cfg.text)}")
            "swipe" -> if (b.isEmpty) log("bounds empty") else {
                swipe(b.left + 10f, b.exactCenterY(), b.right - 10f, b.exactCenterY(), 320L); log("swipe across node")
            }
            else -> log("unknown action '${cfg.action}'")
        }
    }

    private fun paceDelay(cfg: ProvisionConfig) {
        val lo = cfg.minDelayMs.coerceAtLeast(0L)
        val hi = cfg.maxDelayMs.coerceAtLeast(lo + 1L)
        sleep(lo + java.util.Random().nextInt((hi - lo).toInt().coerceAtLeast(1)).toLong())
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
        onLog?.let { l -> main.post { l(msg) } }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args).also {
            Log.i(TAG, "ACTION_SET_TEXT -> $it")
        }
    }

    private fun clickByText(text: String): Boolean {
        val node = collect {
            it.isClickable && it.text?.toString()?.contains(text, true) == true
        }.firstOrNull()
            ?: collect { it.text?.toString()?.contains(text, true) == true }
                .firstOrNull()?.let { climbToClickable(it) }
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ?.also { Log.i(TAG, "ACTION_CLICK '$text' -> $it") } ?: false
    }

    private fun tap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "dispatchGesture requires API 24"); return
        }
        main.post {
            val path = Path().apply { moveTo(x, y); lineTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(gesture, null, null)
            Log.i(TAG, "dispatchGesture tap($x,$y) dispatched=$ok")
        }
    }

    private fun climbToClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isClickable) return cur
            cur = cur.parent; depth++
        }
        return null
    }

    /** Breadth-first collection of nodes matching [predicate] from the active window root. */
    private fun collect(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (predicate(n)) out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }

    companion object {
        const val TAG = "A11yHarness"
        @Volatile var instance: HarnessAccessibilityService? = null
    }
}

/** Operator-supplied configuration for a single targeted run. */
data class ProvisionConfig(
    val pkg: String,
    val matchById: Boolean,
    val query: String,
    val action: String,
    val text: String,
    val minDelayMs: Long,
    val maxDelayMs: Long
)
