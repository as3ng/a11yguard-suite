package com.fraudintel.a11yguard

import android.os.Build
import android.view.View
import android.view.Window
import androidx.annotation.RequiresApi

internal object StructuralHardening {

    fun hardenView(view: View, config: A11yGuardConfig) {
        // API 9+: also auto-marks the view accessibility-sensitive on Android 14+.
        if (config.applyFilterTouchesWhenObscured) {
            runCatching { view.setFilterTouchesWhenObscured(true) }
        }
        // API 34+: blocks non-isAccessibilityTool services from reading OR acting on this view.
        if (config.applyAccessibilityDataSensitive &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            applyAccessibilityDataSensitive(view)
        }
    }

    fun protectWindow(window: Window, config: A11yGuardConfig) {
        // API 31+: hide all non-system overlay windows over this window (anti-tapjacking).
        if (config.applyHideOverlayWindows && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hideOverlayWindows(window)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun applyAccessibilityDataSensitive(view: View) {
        runCatching { view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES) }
            .onFailure { GuardLog.e(it) { "setAccessibilityDataSensitive failed" } }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun hideOverlayWindows(window: Window) {
        runCatching { window.setHideOverlayWindows(true) }
            .onFailure { GuardLog.e(it) { "setHideOverlayWindows failed" } }
    }
}
