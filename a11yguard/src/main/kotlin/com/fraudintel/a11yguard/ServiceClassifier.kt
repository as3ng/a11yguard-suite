package com.fraudintel.a11yguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo

/** Outcome of classifying the currently-enabled accessibility services. Presence-only = weak. */
internal data class ServiceAssessment(
    /** A capable, non-tool service from an untrusted source that is NOT allow-listed is enabled. */
    val capableUnknownPresent: Boolean,
    val capableUnknownPackages: List<String>
)

/**
 * Classifies enabled accessibility services. This is the *weak*, contextual axis - it must never
 * block on its own (that is exactly what produced the client's false positives). Allow-listed
 * vendors (the Assistive-Touch app, the device-intelligence SDK), known-good tools, and system
 * services are explicitly benign even when "capable".
 */
internal object ServiceClassifier {

    private val TRUSTED_INSTALLERS = setOf("com.android.vending", "com.google.android.feedback")

    // Short cache so continuous-watch polling doesn't re-enumerate services every tick.
    @Volatile private var cached: ServiceAssessment? = null
    @Volatile private var cachedAt = 0L
    private const val CACHE_TTL_MS = 8_000L

    fun assess(context: Context, config: A11yGuardConfig): ServiceAssessment {
        val nowMs = SystemClock.uptimeMillis()
        cached?.let { if (nowMs - cachedAt < CACHE_TTL_MS) return it }
        val result = computeAssess(context, config)
        cached = result
        cachedAt = nowMs
        return result
    }

    private fun computeAssess(context: Context, config: A11yGuardConfig): ServiceAssessment {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return ServiceAssessment(false, emptyList())
        val pm = context.packageManager

        val unknown = mutableListOf<String>()
        val list = runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        }.getOrDefault(emptyList())

        for (info in list) {
            val pkg = info.resolveInfo?.serviceInfo?.packageName ?: continue
            val caps = info.capabilities
            val canGesture = caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
            val canRead = caps and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0
            val capable = canGesture || canRead
            if (!capable) continue

            val isTool = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && info.isAccessibilityTool
            val allowListed = pkg in config.allowlistedServicePackages ||
                pkg in config.knownGoodServicePackages
            val isSystem = isSystemApp(pm, pkg)
            val trustedInstall = installerOf(pm, pkg) in TRUSTED_INSTALLERS

            if (!isTool && !allowListed && !isSystem && !trustedInstall) {
                unknown += pkg
            }
        }
        return ServiceAssessment(unknown.isNotEmpty(), unknown.distinct())
    }

    private fun isSystemApp(pm: PackageManager, pkg: String): Boolean = runCatching {
        val flags = pm.getApplicationInfo(pkg, 0).flags
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun installerOf(pm: PackageManager, pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            pm.getInstallerPackageName(pkg)
        }
    }.getOrNull()
}
