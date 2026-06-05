package com.fraudintel.a11yguard

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.min

/**
 * Weighted, explainable risk model evaluated at a sensitive decision point.
 *
 * Key FP-safety mechanism - "legitimacy damping": spoofable behavioral/per-touch signals are
 * heavily discounted unless a *capable, non-tool, untrusted* accessibility service is actually
 * enabled. This is what lets a whitelisted Assistive-Touch or TalkBack user pass: their injected
 * gestures and consumed touches still look "automated" per-event, but with no untrusted capable
 * service present those signals are damped below threshold. The non-damped backstops
 * (machine-paced flow, overlay, integrity) still catch abuse of an allow-listed service.
 */
internal object RiskEngine {

    fun evaluate(
        action: SensitiveAction,
        context: Context,
        config: A11yGuardConfig,
        integrity: IntegrityProvider
    ): RiskVerdict {
        val now = SystemClock.uptimeMillis()
        val flow = config.flowWindowMs
        val corr = config.humanInputCorrelationWindowMs

        val nodeActions = RuntimeState.nodeActionsWithin(now, flow)
        val genuineTouches = RuntimeState.genuineHumanTouchesWithin(now, flow)
        val allTouches = RuntimeState.touchesWithin(now, flow)
        val injected = RuntimeState.injectedTouchesWithin(now, flow)
        val lastHuman = RuntimeState.lastHumanInputAtMs()
        val lastObscured = RuntimeState.lastObscuredAtMs()
        val services = ServiceClassifier.assess(context, config)
        val integrityVerdict = integrity.deviceIntegrity()

        val anyActivity = nodeActions.isNotEmpty() || injected.isNotEmpty()
        // Damp spoofable signals when no untrusted capable service is present (the FP guard).
        val legDamp = if (services.capableUnknownPresent) 1.0 else 0.4

        fun humanInputBefore(tMs: Long): Boolean =
            genuineTouches.any { tMs - it.atMs in 0..corr } || (lastHuman in (tMs - corr)..tMs)

        val contributions = ArrayList<SignalContribution>(SignalId.entries.size)

        // 1) Automated node action (damped). SET_TEXT/PASTE are intrinsically suspicious - legitimate
        //    text entry goes through the IME (InputConnection), never an accessibility node action -
        //    so they count even if a genuine touch happens to precede them, closing the "inject right
        //    after a real touch to look correlated" evasion. CLICK/FOCUS keep the correlation exemption
        //    (a TalkBack double-tap is a real touch that drives a real CLICK). Legit tools that do use SET_TEXT
        //    (e.g. Voice Access) are covered by the legitimacy damp, not by correlation.
        run {
            fun isHard(a: Int) = a == AccessibilityNodeInfo.ACTION_SET_TEXT || a == AccessibilityNodeInfo.ACTION_PASTE
            val hard = nodeActions.count { isHard(it.action) }
            val soft = nodeActions.count { !isHard(it.action) && !humanInputBefore(it.atMs) }
            val base = min(1.0, 0.9 * hard + 0.35 * soft)
            add(contributions, SignalId.AUTOMATED_NODE_ACTION, base * legDamp, config,
                "node actions: hard(set-text/paste)=$hard, soft-uncorrelated=$soft")
        }

        // 2) Machine-paced flow (NOT damped - robust backstop, also catches abuse of trusted svc).
        run {
            val times = nodeActions.map { it.atMs }.sorted()
            val gaps = times.zipWithNext { a, b -> b - a }
            val conf = if (gaps.isEmpty()) 0.0
            else gaps.count { it in 1 until config.machinePacedGapMs }.toDouble() / gaps.size
            add(contributions, SignalId.MACHINE_PACED_FLOW, conf, config,
                "${gaps.size} inter-action gaps, min=${gaps.minOrNull() ?: -1}ms")
        }

        // 3) Absence of human input at the decision point (damped).
        run {
            val timeSince = now - lastHuman
            val conf = when {
                !anyActivity -> 0.0                       // idle reading is not suspicious
                lastHuman == 0L -> 1.0                     // activity but never any human input
                timeSince > corr -> 0.8
                else -> 0.0
            }
            add(contributions, SignalId.ABSENCE_OF_HUMAN_INPUT_AT_DECISION, conf * legDamp, config,
                "ms since human input=$timeSince, activity=$anyActivity")
        }

        // 4) Injected touch kinematics (damped).
        run {
            val conf = if (injected.any { it.injectedKinematics }) 0.8 else 0.0
            add(contributions, SignalId.INJECTED_TOUCH_KINEMATICS, conf * legDamp, config,
                "injected-kinematic touches=${injected.count { it.injectedKinematics }}")
        }

        // 5) Non-touchscreen device id (damped).
        run {
            val conf = if (allTouches.any { it.deviceIdSynthetic }) 0.6 else 0.0
            add(contributions, SignalId.DEVICE_ID_NOT_TOUCHSCREEN, conf * legDamp, config,
                "synthetic-deviceId touches=${allTouches.count { it.deviceIdSynthetic }}")
        }

        // 6) Unverified input (damped, low weight).
        run {
            val conf = if (allTouches.any { it.verified == false }) 0.5 else 0.0
            add(contributions, SignalId.INPUT_UNVERIFIED, conf * legDamp, config,
                "unverifiable boundary events=${allTouches.count { it.verified == false }}")
        }

        // 7) Hidden accessibility flag (damped; only meaningful if probe enabled).
        run {
            val conf = if (allTouches.any { it.hiddenA11yFlag }) 0.7 else 0.0
            add(contributions, SignalId.HIDDEN_ACCESSIBILITY_FLAG, conf * legDamp, config,
                "hidden-flag touches=${allTouches.count { it.hiddenA11yFlag }}")
        }

        // 8) Overlay obscured (NOT damped - a distinct tapjacking threat).
        run {
            val conf = if (lastObscured > 0L && now - lastObscured <= flow) 0.7 else 0.0
            add(contributions, SignalId.SCREEN_OBSCURED_OVERLAY, conf, config, "obscured within flow=$conf")
        }

        // 8b) Rapid guarded-node scraping (damped). Catches full-tree screen scraping.
        run {
            val reads = RuntimeState.nodeReadsWithin(now, config.nodeScrapeWindowMs)
            val conf = if (reads >= config.nodeScrapeBurstThreshold)
                min(1.0, reads.toDouble() / (config.nodeScrapeBurstThreshold * 2.0)) else 0.0
            add(contributions, SignalId.NODE_SCRAPE_RATE, conf * legDamp, config,
                "guarded node reads in ${config.nodeScrapeWindowMs}ms=$reads")
        }

        // 8c) Foreground concealment. Gated on an untrusted capable service so it is zero-FP for
        //     benign users (normal nav-bar HOME/back will not raise it without such a service).
        run {
            val lc = RuntimeState.lastConcealmentAtMs()
            val conf = if (lc > 0L && now - lc <= flow && services.capableUnknownPresent) 0.7 else 0.0
            add(contributions, SignalId.FOREGROUND_OVERLAY_CONCEALMENT, conf, config,
                "focus-loss-while-resumed within flow + untrusted capable service")
        }

        // 9) Capable unknown service present (NOT damped - it is the context, low weight).
        run {
            val conf = if (services.capableUnknownPresent) 1.0 else 0.0
            add(contributions, SignalId.CAPABLE_UNKNOWN_SERVICE_PRESENT, conf, config,
                "untrusted capable services=${services.capableUnknownPackages.size}")
        }

        // 10) Device integrity (NOT damped).
        run {
            val conf = if (integrityVerdict == IntegrityVerdict.FAIL) 1.0 else 0.0
            add(contributions, SignalId.DEVICE_INTEGRITY_FAILED, conf, config, "integrity=$integrityVerdict")
        }

        val score = min(1.0, contributions.sumOf { it.weightedScore })
        val (stepUp, block) = config.thresholdsFor(action)
        val decision = when {
            score >= block -> RiskDecision.BLOCK
            score >= stepUp -> RiskDecision.STEP_UP
            else -> RiskDecision.ALLOW
        }

        val verdict = RiskVerdict(
            action = action,
            decision = decision,
            score = score,
            stepUpThreshold = stepUp,
            blockThreshold = block,
            contributions = contributions.sortedByDescending { it.weightedScore },
            evaluatedAtMs = System.currentTimeMillis()
        )
        GuardLog.d { verdict.toTelemetryLine() }
        return verdict
    }

    private fun add(
        list: MutableList<SignalContribution>,
        id: SignalId,
        confidence: Double,
        config: A11yGuardConfig,
        detail: String
    ) {
        val c = confidence.coerceIn(0.0, 1.0)
        list += SignalContribution(id, c, config.weightFor(id), detail)
    }
}
