package com.fraudintel.a11yguard

import android.util.Log

internal object GuardLog {
    private const val TAG = "A11yGuard"
    @Volatile var enabled: Boolean = false

    fun d(msg: () -> String) { if (enabled) Log.d(TAG, msg()) }
    fun w(msg: () -> String) { if (enabled) Log.w(TAG, msg()) }
    fun e(t: Throwable?, msg: () -> String) { if (enabled) Log.e(TAG, msg(), t) }
}
