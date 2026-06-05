package com.fraudintel.a11yguard

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window

internal class InputAuthenticityMonitor(
    wrapped: Window.Callback,
    private val appContext: Context,
    private val config: A11yGuardConfig
) : WindowCallbackWrapper(wrapped) {

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        runCatching {
            val now = SystemClock.uptimeMillis()
            val sample = TouchClassifier.classify(event, appContext, config, now)
            RuntimeState.recordTouch(sample)
        }.onFailure { GuardLog.e(it) { "touch classify failed" } }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        runCatching {
            // A key from a real physical device (hardware keyboard, switch-access switch, d-pad)
            // is genuine human input. Virtual/injected keys carry deviceId == -1.
            if (event.deviceId > 0) RuntimeState.recordHumanInput(SystemClock.uptimeMillis())
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        runCatching {

            if (!hasFocus && RuntimeState.isAnyResumed()) {
                val now = SystemClock.uptimeMillis()
                if (now - RuntimeState.lastHumanInputAtMs() > 600) RuntimeState.recordConcealment(now)
            }
        }
        super.onWindowFocusChanged(hasFocus)
    }

    companion object {
        /** Wrap a window's callback exactly once. */
        fun install(window: Window, appContext: Context, config: A11yGuardConfig) {
            val current = window.callback
            if (current == null) { GuardLog.w { "window has no callback yet; skipping" }; return }
            if (current is GuardedCallback) return // already protected
            window.callback = InputAuthenticityMonitor(current, appContext, config)
            GuardLog.d { "input monitor installed on window" }
        }
    }
}
