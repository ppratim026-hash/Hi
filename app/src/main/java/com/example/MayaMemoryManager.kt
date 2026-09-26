package com.example

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class MayaPersonality {
    GF, PROFESSIONAL, VENOM
}

enum class WakeState {
    AWAKE, SLEEPING
}

data class MarketItem(
    val symbol: String,
    val name: String,
    val price: String,
    val change: String,
    val isPositive: Boolean
)

data class MacroRoutine(
    val trigger: String,
    val steps: List<String>
)

class MayaMemoryManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("maya_ai_memory", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PERSONALITY = "maya_personality"
        private const val KEY_WAKE_STATE = "maya_wake_state"
        private const val KEY_MEMORIES_JSON = "maya_memories_json"
        private const val KEY_MACROS_JSON = "maya_macros_json"
    }

    var personality: MayaPersonality
        get() {
            val name = prefs.getString(KEY_PERSONALITY, MayaPersonality.GF.name) ?: MayaPersonality.GF.name
            return try { MayaPersonality.valueOf(name) } catch (_: Exception) { MayaPersonality.GF }
        }
        set(value) {
            prefs.edit().putString(KEY_PERSONALITY, value.name).apply()
        }

    var wakeState: WakeState
        get() {
            val name = prefs.getString(KEY_WAKE_STATE, WakeState.AWAKE.name) ?: WakeState.AWAKE.name
            return try { WakeState.valueOf(name) } catch (_: Exception) { WakeState.AWAKE }
        }
        set(value) {
            prefs.edit().putString(KEY_WAKE_STATE, value.name).apply()
        }

    fun saveFact(factKey: String, factValue: String) {
        val memories = getAllMemories().toMutableMap()
        memories[factKey.trim().lowercase()] = factValue.trim()
        val json = JSONObject()
        for ((k, v) in memories) {
            json.put(k, v)
        }
        prefs.edit().putString(KEY_MEMORIES_JSON, json.toString()).apply()
    }

    fun findFact(query: String): String? {
        val memories = getAllMemories()
        val cleanQuery = query.lowercase().trim()
        for ((k, v) in memories) {
            if (cleanQuery.contains(k) || k.contains(cleanQuery)) {
                return v
            }
        }
        return null
    }

    fun getAllMemories(): Map<String, String> {
        val raw = prefs.getString(KEY_MEMORIES_JSON, null) ?: return mapOf(
            "favorite color" to "Sky Blue",
            "name" to "Boss",
            "city" to "Mumbai"
        )
        return try {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.getString(key)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveMacro(trigger: String, steps: List<String>) {
        val current = getAllMacros().toMutableList()
        current.removeAll { it.trigger.equals(trigger, ignoreCase = true) }
        current.add(MacroRoutine(trigger, steps))

        val arr = JSONArray()
        for (m in current) {
            val obj = JSONObject()
            obj.put("trigger", m.trigger)
            val sArr = JSONArray()
            m.steps.forEach { sArr.put(it) }
            obj.put("steps", sArr)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_MACROS_JSON, arr.toString()).apply()
    }

    fun getAllMacros(): List<MacroRoutine> {
        val raw = prefs.getString(KEY_MACROS_JSON, null)
        if (raw == null) {
            return listOf(
                MacroRoutine("morning routine", listOf("Check battery", "Open YouTube for news", "Check WhatsApp")),
                MacroRoutine("night routine", listOf("Turn off torch", "Mute volume", "Set alarm for 7 AM"))
            )
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<MacroRoutine>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val trigger = obj.getString("trigger")
                val sArr = obj.getJSONArray("steps")
                val steps = mutableListOf<String>()
                for (j in 0 until sArr.length()) {
                    steps.add(sArr.getString(j))
                }
                list.add(MacroRoutine(trigger, steps))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getLiveMarkets(): List<MarketItem> {
        return listOf(
            MarketItem("NIFTY 50", "NSE India", "25,372.40", "+0.78%", true),
            MarketItem("SENSEX", "BSE India", "82,888.15", "+0.65%", true),
            MarketItem("BTC/USD", "Bitcoin", "$64,280.00", "+2.45%", true),
            MarketItem("ETH/USD", "Ethereum", "$2,680.50", "-0.82%", false),
            MarketItem("GOLD 24K", "MCX 10g", "₹75,420", "+0.35%", true),
            MarketItem("AAPL", "Apple Inc.", "$228.40", "+1.12%", true)
        )
    }
}
