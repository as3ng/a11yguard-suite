package com.fraudintel.a11yguard

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The false-positive-safe core. By chaining a [View.AccessibilityDelegate] onto guarded controls,
 * we observe accessibility node actions (SET_TEXT / CLICK / PASTE / ...) *exactly* when an
 * accessibility service drives them - and never for:
 *   - soft-keyboard typing (arrives via InputConnection, not performAccessibilityAction), or
 *   - the app's own setText()/performClick() (never routed as an accessibility action).
 *
 * This sidesteps the classic "field changed without a touch" trap, which misfires on every legit
 * password entry because IME input lands in the keyboard's window, not the app's.
 *
 * We only *observe* here and let the risk engine decide, so a single human-paced action from a
 * legitimate tool (TalkBack double-tap, Switch Access) does not block anything. The engine
 * separates that from a machine-paced Automatic-Transfer-System burst.
 */
internal object BehavioralMonitor {

    fun markSensitive(view: View, config: A11yGuardConfig) {
        StructuralHardening.hardenView(view, config)
        val prior = priorDelegate(view)
        view.accessibilityDelegate = ObservingDelegate(prior)
        GuardLog.d { "guarded view ${view.id}" }
    }

    /** On API 29+ we can read and chain an existing delegate; below that we set ours (document
     *  the limitation: call markSensitive before the app installs its own delegate). */
    private fun priorDelegate(view: View): View.AccessibilityDelegate? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) view.accessibilityDelegate else null

    private class ObservingDelegate(
        private val prior: View.AccessibilityDelegate?
    ) : View.AccessibilityDelegate() {

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            if (isInteresting(action)) {
                RuntimeState.recordNodeAction(
                    NodeActionEvent(viewId = host.id, action = action, atMs = SystemClock.uptimeMillis())
                )
                GuardLog.d { "observed a11y action 0x${action.toString(16)} on view ${host.id}" }
            }
            // Always pass through so behavior is unchanged (we score, we don't block here).
            return prior?.performAccessibilityAction(host, action, args)
                ?: super.performAccessibilityAction(host, action, args)
        }

        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            // Invoked when an accessibility service reads this guarded view's node info - a proxy
            // for tree scraping. A rapid burst across guarded views indicates page exfiltration.
            RuntimeState.recordNodeRead(SystemClock.uptimeMillis())
            if (prior != null) prior.onInitializeAccessibilityNodeInfo(host, info)
            else super.onInitializeAccessibilityNodeInfo(host, info)
        }
    }

    private fun isInteresting(action: Int): Boolean =
        action == AccessibilityNodeInfo.ACTION_CLICK ||
            action == AccessibilityNodeInfo.ACTION_LONG_CLICK ||
            action == AccessibilityNodeInfo.ACTION_SET_TEXT ||
            action == AccessibilityNodeInfo.ACTION_PASTE ||
            action == AccessibilityNodeInfo.ACTION_FOCUS
}
