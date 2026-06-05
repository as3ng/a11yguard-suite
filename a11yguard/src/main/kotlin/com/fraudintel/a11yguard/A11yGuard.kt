package com.fraudintel.a11yguard

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View

object A11yGuard {

    @Volatile private var appContext: Context? = null
    @Volatile private var config: A11yGuardConfig = A11yGuardConfig.default()
    @Volatile private var integrity: IntegrityProvider = DefaultIntegrityProvider
    @Volatile private var watch: ContinuousWatch? = null

    /** Initialize once, early (Application.onCreate). */
    @JvmStatic
    @JvmOverloads
    fun init(app: Application, config: A11yGuardConfig = A11yGuardConfig.default()) {
        this.appContext = app.applicationContext
        this.config = config
        GuardLog.enabled = config.debugLogging
        GuardLog.d { "initialized" }
    }

    /** Auto-protect every Activity window for the lifetime of the process. */
    @JvmStatic
    fun enableAutoProtection(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = protect(activity)
            override fun onActivityStarted(activity: Activity) = protect(activity)
            override fun onActivityResumed(activity: Activity) = RuntimeState.incrementResumed()
            override fun onActivityPaused(activity: Activity) = RuntimeState.decrementResumed()
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Manually protect a single Activity's window (idempotent). */
    @JvmStatic
    fun protect(activity: Activity) {
        val ctx = requireInit()
        InputAuthenticityMonitor.install(activity.window, ctx, config)
        StructuralHardening.protectWindow(activity.window, config)
    }

    /** Mark sensitive controls (credential fields, confirm buttons). Call after setContentView(). */
    @JvmStatic
    fun markSensitive(vararg views: View) {
        requireInit()
        views.forEach { BehavioralMonitor.markSensitive(it, config) }
    }

    /** Convenience: protect the window and mark sensitive views in one call. */
    @JvmStatic
    fun protectScreen(activity: Activity, vararg sensitiveViews: View) {
        protect(activity)
        markSensitive(*sensitiveViews)
    }

    /** Supply a server-verified integrity verdict (e.g. Play Integrity). Optional. */
    @JvmStatic
    fun setIntegrityProvider(provider: IntegrityProvider) {
        integrity = provider
    }

    /** Evaluate risk for a sensitive action at the decision point. Never throws on bad state in
     *  release; returns a fail-open ALLOW verdict if not initialized (so the SDK can never brick
     *  a transaction), while logging loudly in debug. */
    @JvmStatic
    fun evaluate(action: SensitiveAction): RiskVerdict {
        val ctx = appContext
        if (ctx == null) {
            GuardLog.w { "evaluate() before init(); failing open to ALLOW" }
            val (s, b) = config.thresholdsFor(action)
            return RiskVerdict(action, RiskDecision.ALLOW, 0.0, s, b, emptyList(), System.currentTimeMillis())
        }
        return RiskEngine.evaluate(action, ctx, config, integrity)
    }

    /**
     * Continuously re-evaluate risk in the background (default every 2 s) and deliver verdicts to
     * [listener] on the main thread. Catches sustained in-app automation that never trips an
     * [evaluate] call. Remember to call [stopContinuousWatch] (e.g. in onDestroy) to avoid leaks.
     */
    @JvmStatic
    fun startContinuousWatch(
        action: SensitiveAction = SensitiveAction.GENERIC,
        intervalMs: Long = 2000L,
        notifyOnlyOnChange: Boolean = true,
        listener: VerdictListener
    ) {
        val ctx = requireInit()
        stopContinuousWatch()
        ContinuousWatch(ctx, config, integrity).also {
            watch = it
            it.start(action, intervalMs.coerceAtLeast(500L), notifyOnlyOnChange, listener)
        }
    }

    @JvmStatic
    fun stopContinuousWatch() {
        watch?.stop()
        watch = null
    }

    /** Visible for testing / the authorized harness. */
    @JvmStatic
    fun resetEvidenceForTesting() = RuntimeState.reset()

    private fun requireInit(): Context =
        appContext ?: error("A11yGuard.init(app) must be called before use")
}
