package com.fraudintel.harness

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One step of a recorded console macro (an action against the foreground target). */
data class MacroStep(
    val matchById: Boolean,
    val query: String,
    val action: String,   // "enum" | "tap" | "click" | "settext" | "swipe"
    val text: String,
    val minDelayMs: Long,
    val maxDelayMs: Long
)

/** Persists named console scenarios (target package + steps) in SharedPreferences as JSON.
 *  Test-tool configuration only - no captured app data is stored. */
object ScenarioStore {
    private const val PREF = "harness_scenarios"
    private const val INDEX = "_names"

    fun save(ctx: Context, name: String, pkg: String, steps: List<MacroStep>) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = JSONArray()
        steps.forEach { s ->
            arr.put(JSONObject().apply {
                put("byId", s.matchById); put("q", s.query); put("a", s.action)
                put("t", s.text); put("min", s.minDelayMs); put("max", s.maxDelayMs)
            })
        }
        val obj = JSONObject().apply { put("pkg", pkg); put("steps", arr) }
        val names = (sp.getStringSet(INDEX, emptySet()) ?: emptySet()).toMutableSet().apply { add(name) }
        sp.edit().putString("s_$name", obj.toString()).putStringSet(INDEX, names).apply()
    }

    fun load(ctx: Context, name: String): Pair<String, List<MacroStep>>? {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString("s_$name", null) ?: return null
        val obj = JSONObject(raw)
        val arr = obj.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MacroStep(
                o.optBoolean("byId", true), o.optString("q", ""), o.optString("a", "enum"),
                o.optString("t", ""), o.optLong("min", 700L), o.optLong("max", 2000L)
            )
        }
        return obj.optString("pkg", "") to steps
    }

    fun names(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getStringSet(INDEX, emptySet()) ?: emptySet()
}
