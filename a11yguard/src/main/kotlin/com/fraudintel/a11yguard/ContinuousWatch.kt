package com.fraudintel.a11yguard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class ContinuousWatch(
    private val appContext: Context,
    private val config: A11yGuardConfig,
    private val integrity: IntegrityProvider
) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var exec: ScheduledExecutorService? = null
    @Volatile private var future: ScheduledFuture<*>? = null
    @Volatile private var listener: VerdictListener? = null
    @Volatile private var action: SensitiveAction = SensitiveAction.GENERIC
    @Volatile private var notifyOnlyOnChange: Boolean = true
    @Volatile private var lastDecision: RiskDecision? = null

    fun start(action: SensitiveAction, intervalMs: Long, notifyOnlyOnChange: Boolean, listener: VerdictListener) {
        stop()
        this.action = action
        this.listener = listener
        this.notifyOnlyOnChange = notifyOnlyOnChange
        this.lastDecision = null
        val e = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "a11yguard-watch").apply { isDaemon = true }
        }
        exec = e
        future = e.scheduleWithFixedDelay({ tick() }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
        GuardLog.d { "continuous watch started interval=${intervalMs}ms action=$action" }
    }

    private fun tick() {
        runCatching {
            val verdict = RiskEngine.evaluate(action, appContext, config, integrity)
            val changed = verdict.decision != lastDecision
            lastDecision = verdict.decision
            if (!notifyOnlyOnChange || changed) {
                val l = listener ?: return@runCatching
                main.post { l.onVerdict(verdict) }
            }
        }.onFailure { GuardLog.e(it) { "watch tick failed" } }
    }

    fun stop() {
        future?.cancel(false); future = null
        exec?.shutdownNow(); exec = null
        listener = null
        lastDecision = null
    }
}
