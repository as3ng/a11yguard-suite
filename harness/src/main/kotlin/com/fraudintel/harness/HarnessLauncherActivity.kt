package com.fraudintel.harness

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fraudintel.a11yguard.A11yGuard
import com.fraudintel.a11yguard.A11yGuardConfig
import com.fraudintel.a11yguard.RiskDecision
import com.fraudintel.a11yguard.RiskVerdict
import com.fraudintel.a11yguard.SensitiveAction

/**
 * Self-contained, guarded sample screen that doubles as the demo "victim" for the harness.
 *
 * Manual confirm tap        -> ALLOW   (genuine human touch in this window)
 * "Automate (machine 80ms)" -> BLOCK   (uncorrelated SET_TEXT + machine pacing + no human input)
 * "Automate (human 1500ms)" -> ALLOW   (human-plausible pacing, behavioral signals damped)
 *
 * NOTE: applyAccessibilityDataSensitive is intentionally false here so the *behavioral engine* is
 * exercised and visible on Android 14+. In production, leave it ON - on 14+ it also structurally
 * blocks the non-tool service from ever filling/clicking these views.
 */
class HarnessLauncherActivity : AppCompatActivity() {

    private lateinit var verdict: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cfg = A11yGuardConfig.Builder()
            .debugLogging(true)
            .applyAccessibilityDataSensitive(false) // see note above; demo wants the engine visible
            .build()
        A11yGuard.init(application, cfg)
        A11yGuard.protect(this)

        val pad = (resources.displayMetrics.density * 20).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val username = EditText(this).apply { hint = "Username"; contentDescription = "harness-username" }
        val password = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            contentDescription = "harness-password"
        }
        val confirm = Button(this).apply { text = "Confirm Transfer" }
        verdict = TextView(this).apply { text = "verdict: (tap or automate)"; setPadding(0, pad, 0, pad) }

        confirm.setOnClickListener {
            val v = A11yGuard.evaluate(SensitiveAction.TRANSFER)
            verdict.text = renderVerdict(v)
            val msg = when (v.decision) {
                RiskDecision.ALLOW -> "Transfer submitted"
                RiskDecision.STEP_UP -> "Step-up auth required"
                RiskDecision.BLOCK -> "Blocked: automation detected"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        val runAll = Button(this).apply {
            text = "Run full attack chain"
            setOnClickListener { drive { it.runFullChain("Confirm Transfer") } }
        }
        val startWatch = Button(this).apply {
            text = "Start continuous watch (2s)"
            setOnClickListener {
                A11yGuard.startContinuousWatch(SensitiveAction.TRANSFER, 2000L, notifyOnlyOnChange = false) { v ->
                    verdict.text = "WATCH ▸ " + renderVerdict(v)
                }
                Toast.makeText(this@HarnessLauncherActivity, "Continuous watch ON", Toast.LENGTH_SHORT).show()
            }
        }
        val stopWatch = Button(this).apply {
            text = "Stop continuous watch"
            setOnClickListener {
                A11yGuard.stopContinuousWatch()
                Toast.makeText(this@HarnessLauncherActivity, "Continuous watch OFF", Toast.LENGTH_SHORT).show()
            }
        }
        val runRecon = Button(this).apply {
            text = "Run recon (human-paced)"
            setOnClickListener { drive { it.runReconChain() } }
        }
        val runMachine = Button(this).apply {
            text = "Automate login+transfer (machine 80ms)"
            setOnClickListener { drive { it.runFillAndConfirm("victim", "Passw0rd!", "Confirm Transfer", 80) } }
        }
        val runHuman = Button(this).apply {
            text = "Automate (human-paced 1500ms)"
            setOnClickListener { drive { it.runFillAndConfirm("victim", "Passw0rd!", "Confirm Transfer", 1500) } }
        }
        val runGesture = Button(this).apply {
            text = "Automate confirm via dispatchGesture"
            setOnClickListener { drive { it.runGestureConfirm("Confirm Transfer") } }
        }
        val runPaste = Button(this).apply {
            text = "Fill via clipboard PASTE (no SET_TEXT)"
            setOnClickListener { drive { it.runPasteInjection("victim") } }
        }
        val runScrape = Button(this).apply {
            text = "Scrape node tree (page exfil)"
            setOnClickListener { drive { it.runTreeScrape(6) } }
        }
        val runConceal = Button(this).apply {
            text = "Concealment overlay (2.5s)"
            setOnClickListener { drive { it.runConcealmentOverlay(2500) } }
        }
        val runCurved = Button(this).apply {
            text = "Automate confirm (human-paced curve)"
            setOnClickListener { drive { it.runHumanPacedReplay("Confirm Transfer") } }
        }
        val openConsole = Button(this).apply {
            text = "Open targeted console (real app)"
            setOnClickListener { startActivity(Intent(this@HarnessLauncherActivity, TargetConsoleActivity::class.java)) }
        }
        val openSettings = Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        listOf<View>(
            username, password, confirm, verdict, runAll,
            startWatch, stopWatch, runRecon,
            runMachine, runHuman, runGesture, runPaste, runScrape, runConceal, runCurved, openConsole, openSettings
        ).forEach { root.addView(it) }
        setContentView(android.widget.ScrollView(this).apply { addView(root) })

        // Guard the sensitive controls AFTER they exist in the hierarchy.
        A11yGuard.markSensitive(username, password, confirm)
    }

    private fun renderVerdict(v: RiskVerdict): String {
        val lines = v.contributions.filter { it.confidence > 0.0 }
            .joinToString("\n") { " + ${"%.2f".format(it.weightedScore)}  ${it.signal.humanReadable}" }
        return "${v.decision}   score=${"%.2f".format(v.score)}  (block ≥ ${v.blockThreshold})\n$lines"
    }

    override fun onDestroy() {
        A11yGuard.stopContinuousWatch()
        super.onDestroy()
    }

    private inline fun drive(block: (HarnessAccessibilityService) -> Unit) {
        val svc = HarnessAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, "Enable 'A11y Test Harness Service' in Accessibility settings", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else block(svc)
    }
}
