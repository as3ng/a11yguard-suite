package com.fraudintel.harness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Broadcast entry point for triggering scenarios from adb. Action com.fraudintel.harness.RUN with a
 * string extra "scenario" ("fill" or "gesture"), a long extra "gapMs", and optional string extras
 * "user", "pass", and "confirm".
 */
class HarnessCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = HarnessAccessibilityService.instance
        if (svc == null) {
            Log.w(HarnessAccessibilityService.TAG, "service not enabled")
            Toast.makeText(context, "Enable the A11y Test Harness service first", Toast.LENGTH_LONG).show()
            return
        }
        val scenario = intent.getStringExtra("scenario") ?: "fill"
        val gap = intent.getLongExtra("gapMs", 80L)
        val user = intent.getStringExtra("user") ?: "user"
        val pass = intent.getStringExtra("pass") ?: "secret"
        val confirm = intent.getStringExtra("confirm") ?: "Confirm Transfer"
        when (scenario) {
            "gesture" -> svc.runGestureConfirm(confirm)
            else -> svc.runFillAndConfirm(user, pass, confirm, gap)
        }
    }
}
