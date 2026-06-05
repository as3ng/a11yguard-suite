package com.fraudintel.a11yguard

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs

internal object TouchClassifier {

    /**
     * Best-effort mask for the hidden MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT bit
     * (native AMOTION_EVENT_FLAG_IS_ACCESSIBILITY_EVENT). It is @hide/@TestApi, may be stripped
     * before app delivery on many builds, and the value can differ across versions/OEMs.
     * Opportunistic only; gated behind [A11yGuardConfig.enableHiddenFlagProbe].
     */
    private const val FLAG_IS_ACCESSIBILITY_EVENT_BEST_EFFORT = 0x00000800

    @Volatile private var cachedTouchscreenIds: Set<Int> = emptySet()
    @Volatile private var cachedAtMs: Long = 0L
    private const val CACHE_TTL_MS = 5_000L

    fun classify(event: MotionEvent, context: Context, config: A11yGuardConfig, nowMs: Long): TouchSample {
        val obscured = (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0 ||
            (event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0

        val hiddenFlag = config.enableHiddenFlagProbe &&
            (event.flags and FLAG_IS_ACCESSIBILITY_EVENT_BEST_EFFORT) != 0

        val ids = touchscreenIds(nowMs)
        val devId = event.deviceId
        // If we cannot enumerate touchscreens, only non-positive ids are "synthetic" (FP-averse).
        val deviceIdSynthetic = if (ids.isEmpty()) devId <= 0 else (devId <= 0 || devId !in ids)

        val tool = event.getToolType(0)
        val toolOdd = tool != MotionEvent.TOOL_TYPE_FINGER &&
            tool != MotionEvent.TOOL_TYPE_STYLUS &&
            tool != MotionEvent.TOOL_TYPE_MOUSE

        val pinned = isPinnedPressureSize(event)
        val kin = hasSyntheticKinematics(event)
        val verified = verifyIfDecisive(event, context)

        val classification = when {
            deviceIdSynthetic && (kin || toolOdd || pinned || hiddenFlag) -> TouchClassification.SUSPECT_INJECTED
            !deviceIdSynthetic -> TouchClassification.GENUINE_HUMAN
            kin || hiddenFlag -> TouchClassification.SUSPECT_INJECTED
            else -> TouchClassification.INCONCLUSIVE
        }

        return TouchSample(
            atMs = nowMs,
            classification = classification,
            obscured = obscured,
            deviceIdSynthetic = deviceIdSynthetic,
            injectedKinematics = kin,
            verified = verified,
            hiddenA11yFlag = hiddenFlag
        )
    }

    private fun touchscreenIds(nowMs: Long): Set<Int> {
        if (nowMs - cachedAtMs < CACHE_TTL_MS && cachedTouchscreenIds.isNotEmpty()) return cachedTouchscreenIds
        val result = runCatching {
            InputDevice.getDeviceIds().asSequence()
                .mapNotNull { id -> InputDevice.getDevice(id)?.let { id to it.sources } }
                .filter { (_, sources) -> (sources and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN }
                .map { (id, _) -> id }
                .toSet()
        }.getOrDefault(emptySet())
        cachedTouchscreenIds = result
        cachedAtMs = nowMs
        return result
    }

    /** Injected MotionEvent.obtain() defaults tend to pin pressure to 1.0 and size to 0.0. */
    private fun isPinnedPressureSize(event: MotionEvent): Boolean =
        event.pressure == 1.0f && event.size == 0.0f

    /**
     * A dispatchGesture stroke is a linear interpolation sampled at uniform time steps with no
     * pressure jitter. Real fingers don't do that. Only meaningful for a MOVE with enough history.
     */
    private fun hasSyntheticKinematics(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val h = event.historySize
        if (h < 3) return false

        val xs = FloatArray(h + 1)
        val ys = FloatArray(h + 1)
        val ts = LongArray(h + 1)
        val ps = FloatArray(h + 1)
        for (i in 0 until h) {
            xs[i] = event.getHistoricalX(i); ys[i] = event.getHistoricalY(i)
            ts[i] = event.getHistoricalEventTime(i); ps[i] = event.getHistoricalPressure(i)
        }
        xs[h] = event.x; ys[h] = event.y; ts[h] = event.eventTime; ps[h] = event.pressure

        // 1) collinear: every consecutive triple has ~zero cross product
        var collinear = true
        for (i in 2..h) {
            val cross = (xs[i - 1] - xs[i - 2]) * (ys[i] - ys[i - 2]) -
                (ys[i - 1] - ys[i - 2]) * (xs[i] - xs[i - 2])
            if (abs(cross) > 2.0f) { collinear = false; break }
        }
        if (!collinear) return false

        // 2) uniform time step (coefficient of variation < 5%)
        val dts = LongArray(h) { ts[it + 1] - ts[it] }
        val mean = dts.average()
        if (mean <= 0.0) return false
        val variance = dts.sumOf { (it - mean) * (it - mean) } / dts.size
        val cov = Math.sqrt(variance) / mean
        if (cov > 0.05) return false

        // 3) zero pressure variance
        val pMean = ps.average()
        val pVar = ps.sumOf { (it - pMean) * (it - pMean) } / ps.size
        return pVar < 1e-6
    }

    /** Verify only the decisive boundary events (DOWN/UP) to bound cost. API 30+. */
    private fun verifyIfDecisive(event: MotionEvent, context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val a = event.actionMasked
        if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_UP) return null
        return runCatching {
            val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            im?.verifyInputEvent(event) != null
        }.getOrNull()
    }
}
