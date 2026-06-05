package com.fraudintel.a11yguard

class A11yGuardConfig private constructor(
    /** Packages the client explicitly trusts (e.g. the whitelisted Assistive-Touch vendor and
     *  the device-intelligence SDK). Presence of these never raises [SignalId.CAPABLE_UNKNOWN_SERVICE_PRESENT]. */
    val allowlistedServicePackages: Set<String>,

    /** Known-good, widely-deployed tools treated as benign presence (TalkBack, Switch Access, etc.). */
    val knownGoodServicePackages: Set<String>,

    /** Per-signal weights. Missing keys fall back to [defaultWeights]. */
    val weights: Map<SignalId, Double>,

    /** Per-action (stepUp, block) thresholds. Missing keys fall back to [defaultThresholds]. */
    val thresholds: Map<SensitiveAction, Pair<Double, Double>>,

    /** A node-action sequence whose median inter-action gap is below this is "machine-paced". */
    val machinePacedGapMs: Long,

    /** How far back (ms) a genuine human touch/key still "covers" an a11y action or decision. */
    val humanInputCorrelationWindowMs: Long,

    /** Whole-flow lookback window (ms) used when scoring node actions at a decision point. */
    val flowWindowMs: Long,

    /** Window (ms) over which guarded-node reads are counted for scrape-rate detection. */
    val nodeScrapeWindowMs: Long,

    /** Guarded-node reads within [nodeScrapeWindowMs] above which scraping is suspected. */
    val nodeScrapeBurstThreshold: Int,

    /** Apply API 34+ accessibilityDataSensitive to guarded views automatically. */
    val applyAccessibilityDataSensitive: Boolean,

    /** Apply setFilterTouchesWhenObscured(true) to guarded views automatically. */
    val applyFilterTouchesWhenObscured: Boolean,

    /** Apply API 31+ Window.setHideOverlayWindows(true) to protected windows automatically. */
    val applyHideOverlayWindows: Boolean,

    /** Best-effort read of the hidden FLAG_IS_ACCESSIBILITY_EVENT bit. Off by default: it is a
     *  @hide/@TestApi bit, version/OEM dependent, and must never be a primary signal. */
    val enableHiddenFlagProbe: Boolean,

    /** Verbose logging (debug builds only - never log PII). */
    val debugLogging: Boolean
) {
    fun weightFor(id: SignalId): Double = weights[id] ?: defaultWeights.getValue(id)

    fun thresholdsFor(action: SensitiveAction): Pair<Double, Double> =
        thresholds[action] ?: defaultThresholds[action] ?: defaultThresholds.getValue(SensitiveAction.GENERIC)

    class Builder {
        private val allowlist = mutableSetOf<String>()
        private val knownGood = mutableSetOf(
            "com.google.android.marvin.talkback",   // TalkBack
            "com.google.android.accessibility.switchaccess",
            "com.google.android.apps.accessibility.voiceaccess",
            "com.android.switchaccess"
        )
        private val weights = mutableMapOf<SignalId, Double>()
        private val thresholds = mutableMapOf<SensitiveAction, Pair<Double, Double>>()
        private var machinePacedGapMs = 250L
        private var humanInputCorrelationWindowMs = 1500L
        private var flowWindowMs = 15_000L
        private var nodeScrapeWindowMs = 2_000L
        private var nodeScrapeBurstThreshold = 12
        private var applyAds = true
        private var applyFilter = true
        private var applyHideOverlays = true
        private var hiddenFlagProbe = false
        private var debug = false

        fun allowlistServicePackages(vararg pkgs: String) = apply { allowlist += pkgs }
        fun allowlistServicePackages(pkgs: Collection<String>) = apply { allowlist += pkgs }
        fun knownGoodServicePackages(vararg pkgs: String) = apply { knownGood += pkgs }
        fun weight(id: SignalId, w: Double) = apply { weights[id] = w }
        fun thresholds(action: SensitiveAction, stepUp: Double, block: Double) =
            apply { thresholds[action] = stepUp to block }
        fun machinePacedGapMs(v: Long) = apply { machinePacedGapMs = v }
        fun humanInputCorrelationWindowMs(v: Long) = apply { humanInputCorrelationWindowMs = v }
        fun flowWindowMs(v: Long) = apply { flowWindowMs = v }
        fun nodeScrapeWindowMs(v: Long) = apply { nodeScrapeWindowMs = v }
        fun nodeScrapeBurstThreshold(v: Int) = apply { nodeScrapeBurstThreshold = v }
        fun applyAccessibilityDataSensitive(v: Boolean) = apply { applyAds = v }
        fun applyFilterTouchesWhenObscured(v: Boolean) = apply { applyFilter = v }
        fun applyHideOverlayWindows(v: Boolean) = apply { applyHideOverlays = v }
        fun enableHiddenFlagProbe(v: Boolean) = apply { hiddenFlagProbe = v }
        fun debugLogging(v: Boolean) = apply { debug = v }

        fun build() = A11yGuardConfig(
            allowlistedServicePackages = allowlist.toSet(),
            knownGoodServicePackages = knownGood.toSet(),
            weights = weights.toMap(),
            thresholds = thresholds.toMap(),
            machinePacedGapMs = machinePacedGapMs,
            humanInputCorrelationWindowMs = humanInputCorrelationWindowMs,
            flowWindowMs = flowWindowMs,
            nodeScrapeWindowMs = nodeScrapeWindowMs,
            nodeScrapeBurstThreshold = nodeScrapeBurstThreshold,
            applyAccessibilityDataSensitive = applyAds,
            applyFilterTouchesWhenObscured = applyFilter,
            applyHideOverlayWindows = applyHideOverlays,
            enableHiddenFlagProbe = hiddenFlagProbe,
            debugLogging = debug
        )
    }

    companion object {
        /** FP-averse defaults. The two structural/behavioral signals dominate; presence-only
         *  and spoofable per-touch signals are intentionally light so they can corroborate but
         *  never single-handedly block. */
        val defaultWeights: Map<SignalId, Double> = mapOf(
            SignalId.AUTOMATED_NODE_ACTION to 0.55,
            SignalId.MACHINE_PACED_FLOW to 0.50,
            SignalId.ABSENCE_OF_HUMAN_INPUT_AT_DECISION to 0.45,
            SignalId.INJECTED_TOUCH_KINEMATICS to 0.35,
            SignalId.DEVICE_ID_NOT_TOUCHSCREEN to 0.30,
            SignalId.INPUT_UNVERIFIED to 0.15,
            SignalId.HIDDEN_ACCESSIBILITY_FLAG to 0.20,
            SignalId.SCREEN_OBSCURED_OVERLAY to 0.25,
            SignalId.NODE_SCRAPE_RATE to 0.30,
            SignalId.FOREGROUND_OVERLAY_CONCEALMENT to 0.25,
            SignalId.CAPABLE_UNKNOWN_SERVICE_PRESENT to 0.15,
            SignalId.DEVICE_INTEGRITY_FAILED to 0.30
        )

        /** (stepUp, block). BLOCK requires corroboration: no single default weight reaches it. */
        val defaultThresholds: Map<SensitiveAction, Pair<Double, Double>> = mapOf(
            SensitiveAction.LOGIN to (0.45 to 0.75),
            SensitiveAction.TRANSFER to (0.40 to 0.70),
            SensitiveAction.ADD_PAYEE to (0.40 to 0.70),
            SensitiveAction.CHANGE_CREDENTIALS to (0.40 to 0.70),
            SensitiveAction.HIGH_VALUE_TRANSACTION to (0.35 to 0.65),
            SensitiveAction.GENERIC to (0.50 to 0.80)
        )

        fun default() = Builder().build()
    }
}
