package com.fraudintel.harness

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fraudintel.a11yguard.A11yGuard
import com.fraudintel.a11yguard.A11yGuardConfig
import com.fraudintel.a11yguard.RiskVerdict
import com.fraudintel.a11yguard.SensitiveAction

/**
 * Masih beta test :)
 * AUTHORIZED operator console for targeted testing against the client's REAL app.
 *
 * The operator types a package, enables the service, optionally toggles runtime a11y flags, names a
 * target resource (view-id or text) and an action, then presses START. It launches the target and
 * drives the chosen control at human pace so the client can confirm a11yguard fires in their own app.
 * Enumeration is read-only (lists resource-ids/text for target discovery); nothing is exfiltrated,
 * persisted, or sent anywhere. Test devices only.
 */
class TargetConsoleActivity : AppCompatActivity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 0) }

        root.addView(TextView(this).apply { text = "Targeted Test Console (authorized)"; textSize = 18f })

        val pkg = EditText(this).apply { hint = "target package e.g. com.client.app"; setSingleLine() }
        root.addView(label("Target package")); root.addView(pkg)

        root.addView(Button(this).apply {
            text = "Enable service (Accessibility settings)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        // Runtime accessibility flags
        root.addView(label("Accessibility flags (runtime, applied via setServiceInfo)"))
        val cbWindows = CheckBox(this).apply { text = "RETRIEVE_INTERACTIVE_WINDOWS"; isChecked = true }
        val cbViewIds = CheckBox(this).apply { text = "REPORT_VIEW_IDS"; isChecked = true }
        val cbKeys = CheckBox(this).apply { text = "REQUEST_FILTER_KEY_EVENTS" }
        val cbTouch = CheckBox(this).apply { text = "REQUEST_TOUCH_EXPLORATION_MODE" }
        val cbNotImp = CheckBox(this).apply { text = "INCLUDE_NOT_IMPORTANT_VIEWS"; isChecked = true }
        listOf(cbWindows, cbViewIds, cbKeys, cbTouch, cbNotImp).forEach { root.addView(it) }
        root.addView(Button(this).apply {
            text = "Apply a11y flags"
            setOnClickListener {
                var f = AccessibilityServiceInfo.DEFAULT
                if (cbWindows.isChecked) f = f or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                if (cbViewIds.isChecked) f = f or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                if (cbKeys.isChecked) f = f or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                if (cbTouch.isChecked) f = f or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
                if (cbNotImp.isChecked) f = f or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                withService { it.onLog = uiLogger(); it.applyServiceFlags(f) }
            }
        })

        // Target resource and action
        root.addView(label("Match target by"))
        val matchSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@TargetConsoleActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("View ID", "Text"))
        }
        root.addView(matchSpinner)

        val query = EditText(this).apply {
            hint = "resource-id e.g. com.client.app:id/btnConfirm  OR  visible text"; setSingleLine()
        }
        root.addView(label("Target resource / text  (leave blank + Enumerate to discover)")); root.addView(query)

        root.addView(label("Action"))
        val actionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@TargetConsoleActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Enumerate", "Tap (gesture)", "Click", "Set-Text", "Swipe"))
        }
        root.addView(actionSpinner)

        val textToSet = EditText(this).apply { hint = "text for Set-Text"; setSingleLine() }
        root.addView(textToSet)

        // Human pace
        root.addView(label("Human pace delay (ms): min / max"))
        val paceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val minDelay = EditText(this).apply { setText("700"); inputType = InputType.TYPE_CLASS_NUMBER }
        val maxDelay = EditText(this).apply { setText("2000"); inputType = InputType.TYPE_CLASS_NUMBER }
        paceRow.addView(minDelay, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        paceRow.addView(maxDelay, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(paceRow)

        // Start
        root.addView(Button(this).apply {
            text = "Start (launch and provision)"
            setOnClickListener {
                val action = when (actionSpinner.selectedItemPosition) {
                    1 -> "tap"; 2 -> "click"; 3 -> "settext"; 4 -> "swipe"; else -> "enum"
                }
                val cfg = ProvisionConfig(
                    pkg = pkg.text.toString().trim(),
                    matchById = matchSpinner.selectedItemPosition == 0,
                    query = query.text.toString().trim(),
                    action = action,
                    text = textToSet.text.toString(),
                    minDelayMs = minDelay.text.toString().toLongOrNull() ?: 700L,
                    maxDelayMs = maxDelay.text.toString().toLongOrNull() ?: 2000L
                )
                logView.text = ""
                withService { svc ->
                    svc.onLog = uiLogger()
                    if (cfg.pkg.isNotBlank()) {
                        val intent = packageManager.getLaunchIntentForPackage(cfg.pkg)
                        if (intent == null) { logView.append("ERROR: no launch intent / package not visible: ${cfg.pkg}\n"); return@withService }
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    }
                    svc.provision(cfg)
                }
            }
        })

        // a11yguard verdict (bundled sample only; same process as this console)
        root.addView(label("a11yguard verdict (bundled sample only; the real client app reports in its own logs)"))
        val verdictView = TextView(this).apply { text = "verdict: (start watch, then drive the sample)" }
        root.addView(verdictView)
        var watching = false
        root.addView(Button(this).apply {
            text = "Watch a11yguard verdict (sample)"
            setOnClickListener {
                if (!watching) {
                    startVerdictWatch(verdictView); watching = true; text = "Stop watching verdict"
                } else {
                    A11yGuard.stopContinuousWatch(); watching = false; text = "Watch a11yguard verdict (sample)"
                }
            }
        })

        // Record and replay macro
        root.addView(label("Macro (record current config as steps, then replay)"))
        val steps = mutableListOf<MacroStep>()
        fun currentStep() = MacroStep(
            matchById = matchSpinner.selectedItemPosition == 0,
            query = query.text.toString().trim(),
            action = when (actionSpinner.selectedItemPosition) { 1 -> "tap"; 2 -> "click"; 3 -> "settext"; 4 -> "swipe"; else -> "enum" },
            text = textToSet.text.toString(),
            minDelayMs = minDelay.text.toString().toLongOrNull() ?: 700L,
            maxDelayMs = maxDelay.text.toString().toLongOrNull() ?: 2000L
        )
        val scenarioName = EditText(this).apply { hint = "scenario name"; setSingleLine() }
        root.addView(scenarioName)
        root.addView(Button(this).apply {
            text = "Add current as step"
            setOnClickListener { val s = currentStep(); steps.add(s); logLine("step #${steps.size}: ${s.action} '${s.query}'") }
        })
        root.addView(Button(this).apply {
            text = "Replay steps"
            setOnClickListener {
                if (steps.isEmpty()) { toast("no steps recorded"); return@setOnClickListener }
                val p = pkg.text.toString().trim()
                logView.text = ""
                withService { svc ->
                    svc.onLog = uiLogger()
                    if (p.isNotBlank()) {
                        val intent = packageManager.getLaunchIntentForPackage(p)
                        if (intent == null) { logLine("ERROR: package not visible: $p"); return@withService }
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent)
                    }
                    svc.provisionSequence(p, steps.toList())
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Save scenario"
            setOnClickListener {
                val n = scenarioName.text.toString().trim()
                if (n.isEmpty()) { toast("enter a scenario name"); return@setOnClickListener }
                ScenarioStore.save(this@TargetConsoleActivity, n, pkg.text.toString().trim(), steps)
                logLine("saved '$n' (${steps.size} steps)")
            }
        })
        root.addView(Button(this).apply {
            text = "Load scenario"
            setOnClickListener {
                val loaded = ScenarioStore.load(this@TargetConsoleActivity, scenarioName.text.toString().trim())
                if (loaded == null) { toast("not found"); return@setOnClickListener }
                pkg.setText(loaded.first); steps.clear(); steps.addAll(loaded.second)
                logLine("loaded (${steps.size} steps) for pkg=${loaded.first}")
            }
        })
        root.addView(Button(this).apply {
            text = "List saved"
            setOnClickListener { logLine("saved: ${ScenarioStore.names(this@TargetConsoleActivity).joinToString().ifEmpty { "(none)" }}") }
        })
        root.addView(Button(this).apply {
            text = "Clear recorded steps"
            setOnClickListener { steps.clear(); logLine("steps cleared") }
        })

        root.addView(label("Log"))
        logView = TextView(this).apply {
            setTextIsSelectable(true); typeface = Typeface.MONOSPACE; textSize = 11f
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun uiLogger(): (String) -> Unit = { line -> logView.append("$line\n") }

    private fun logLine(m: String) { logView.append("$m\n") }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun startVerdictWatch(view: TextView) {
        val begin = {
            A11yGuard.startContinuousWatch(SensitiveAction.TRANSFER, 1500L, notifyOnlyOnChange = false) { v ->
                view.text = renderVerdict(v)
            }
        }
        try { begin() } catch (e: IllegalStateException) {
            A11yGuard.init(application, A11yGuardConfig.Builder().debugLogging(true).build())
            begin()
        }
    }

    private fun renderVerdict(v: RiskVerdict): String {
        val lines = v.contributions.filter { it.confidence > 0.0 }
            .joinToString("\n") { " + ${"%.2f".format(it.weightedScore)}  ${it.signal.humanReadable}" }
        return "a11yguard ▸ ${v.decision}  score=${"%.2f".format(v.score)}\n$lines"
    }

    private inline fun withService(block: (HarnessAccessibilityService) -> Unit) {
        val svc = HarnessAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, "Enable 'A11y Test Harness Service' first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else block(svc)
    }

    override fun onDestroy() {
        A11yGuard.stopContinuousWatch()
        HarnessAccessibilityService.instance?.onLog = null
        super.onDestroy()
    }
}
