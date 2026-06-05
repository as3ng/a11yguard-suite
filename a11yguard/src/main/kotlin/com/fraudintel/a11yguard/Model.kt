package com.fraudintel.a11yguard


/** Final decision returned to the integrating app at a sensitive decision point. */
enum class RiskDecision {
    /** No meaningful automation evidence. Proceed normally. */
    ALLOW,

    /** Ambiguous evidence. Do not hard-fail; require out-of-band/step-up confirmation. */
    STEP_UP,

    /** Strong, corroborated automation evidence. Block this action. */
    BLOCK
}

/** The sensitive operation being guarded. Lets callers tune thresholds per action class. */
enum class SensitiveAction {
    LOGIN,
    TRANSFER,
    ADD_PAYEE,
    CHANGE_CREDENTIALS,
    HIGH_VALUE_TRANSACTION,
    GENERIC
}

/** Device/app integrity verdict, normally fed in from a server-verified Play Integrity check. */
enum class IntegrityVerdict {
    PASS,
    FAIL,
    UNKNOWN
}

/**
 * Individual signals the engine can observe. Each maps to a configurable weight.
 * Ordered roughly strongest to weakest discriminator.
 */
enum class SignalId(val humanReadable: String) {
    /** A11y node action (SET_TEXT / CLICK / PASTE) hit a guarded control. Observed directly,
     *  so it never fires for IME typing or the app's own setText(). */
    AUTOMATED_NODE_ACTION("Accessibility action on a guarded control"),

    /** Guarded controls were driven faster than a human plausibly could (ATS fingerprint). */
    MACHINE_PACED_FLOW("Machine-paced control sequence"),

    /** The decision point was reached with no genuine human input in the app's own window. */
    ABSENCE_OF_HUMAN_INPUT_AT_DECISION("No human input at decision point"),

    /** A touch in our window had injection kinematics (pinned pressure/size, zero jitter,
     *  perfectly linear stroke, uniform timing). */
    INJECTED_TOUCH_KINEMATICS("Synthetic touch kinematics"),

    /** A touch's deviceId does not map to any registered physical touchscreen InputDevice. */
    DEVICE_ID_NOT_TOUCHSCREEN("Touch from a non-touchscreen device id"),

    /** InputManager.verifyInputEvent() could not verify the driving event (API 30+). */
    INPUT_UNVERIFIED("System could not verify the input event"),

    /** Opportunistic, best-effort read of the hidden FLAG_IS_ACCESSIBILITY_EVENT bit. */
    HIDDEN_ACCESSIBILITY_FLAG("Hidden accessibility-event flag set on touch"),

    /** A non-system overlay obscured our window during the action (tapjacking surface). */
    SCREEN_OBSCURED_OVERLAY("Window obscured by an overlay"),

    /** Guarded views' accessibility node info was read in a rapid burst, indicating full-tree
     *  scraping of the screen. */
    NODE_SCRAPE_RATE("Rapid accessibility node-tree scraping"),

    /** Our window lost focus while still RESUMED with no human input: a focusable concealment
     *  overlay, or the observable effect of a HOME/RECENTS global action. Partial coverage only;
     *  NOT_FOCUSABLE/NOT_TOUCHABLE overlays are invisible to the covered app and must be prevented
     *  with setHideOverlayWindows (API 31+). */
    FOREGROUND_OVERLAY_CONCEALMENT("Foreground focus loss / concealment overlay"),

    /** A capable accessibility service from an unknown installer / not declared a tool is enabled. */
    CAPABLE_UNKNOWN_SERVICE_PRESENT("Capable non-tool accessibility service enabled"),

    /** Server-verified device/app integrity failed. */
    DEVICE_INTEGRITY_FAILED("Device/app integrity check failed")
}

/** One scored signal contribution, kept for auditability / SIEM export. */
data class SignalContribution(
    val signal: SignalId,
    /** Raw 0.0..1.0 confidence this signal fired. */
    val confidence: Double,
    /** Weight applied by the engine for this signal. */
    val weight: Double,
    /** Human-readable evidence string (no PII). */
    val detail: String
) {
    val weightedScore: Double get() = confidence * weight
}

/**
 * The verdict. [contributions] is the full, ordered evidence list so the integrating
 * app (and your SIEM) can log *why* a decision was made - enterprises need explainability.
 */
data class RiskVerdict(
    val action: SensitiveAction,
    val decision: RiskDecision,
    val score: Double,
    val stepUpThreshold: Double,
    val blockThreshold: Double,
    val contributions: List<SignalContribution>,
    val evaluatedAtMs: Long
) {
    val isBlocked: Boolean get() = decision == RiskDecision.BLOCK
    val requiresStepUp: Boolean get() = decision == RiskDecision.STEP_UP
    val isAllowed: Boolean get() = decision == RiskDecision.ALLOW

    /** Compact, PII-free one-liner suitable for telemetry. */
    fun toTelemetryLine(): String {
        val firing = contributions.filter { it.confidence > 0.0 }
            .sortedByDescending { it.weightedScore }
            .joinToString(",") { "${it.signal.name}:${"%.2f".format(it.weightedScore)}" }
        return "a11yguard action=$action decision=$decision score=${"%.2f".format(score)} signals=[$firing]"
    }
}

/** Callback for continuous-watch verdicts. Delivered on the main thread. */
fun interface VerdictListener {
    fun onVerdict(verdict: RiskVerdict)
}

// Internal evidence records (not part of the public API)

/** A11y action observed on a guarded view via the chained AccessibilityDelegate. */
internal data class NodeActionEvent(
    val viewId: Int,
    val action: Int,
    val atMs: Long
)

/** A classified touch sample retained briefly for kinematic/flow analysis. */
internal data class TouchSample(
    val atMs: Long,
    val classification: TouchClassification,
    val obscured: Boolean,
    val deviceIdSynthetic: Boolean = false,
    val injectedKinematics: Boolean = false,
    /** null = verification not attempted/unsupported; true/false = verifyInputEvent result. */
    val verified: Boolean? = null,
    val hiddenA11yFlag: Boolean = false
)

internal enum class TouchClassification { GENUINE_HUMAN, SUSPECT_INJECTED, INCONCLUSIVE }
