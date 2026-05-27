package com.fossyaudio.bpcontrol.data

import android.content.Context
import android.util.Log
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.Preset
import org.json.JSONArray
import org.json.JSONObject

class PresetRepository(
    private val context: Context,
    private val prefsName: String = "BP_PRESETS",
    private val presetsKey: String = "presets_data"
) {
    private val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    fun load(): MutableList<Preset> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(presetsKey, null)
        val loaded = mutableListOf<Preset>()

        if (jsonStr != null) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val pObj = array.getJSONObject(i)
                    val name = pObj.getString("name")
                    val preamp = pObj.optDouble("preamp", 0.0).toFloat()
                    val bArray = pObj.getJSONArray("filters")
                    val bList = mutableListOf<FilterBand>()
                    for (b in 0 until 10) {
                        if (b < bArray.length()) {
                            val bObj = bArray.getJSONObject(b)
                            bList.add(
                                FilterBand(
                                    enabled = bObj.getBoolean("enabled"),
                                    type = bObj.getString("type"),
                                    freq = bObj.getInt("freq"),
                                    gain = bObj.getDouble("gain").toFloat(),
                                    q = bObj.getDouble("q").toFloat()
                                )
                            )
                        } else {
                            bList.add(FilterBand(freq = defaultFreqs[b]))
                        }
                    }
                    loaded.add(Preset(name, preamp, bList))
                }
            } catch (e: Exception) {
                Log.e("Presets", "JSON Parse Error", e)
            }
        }

        ensureSystemPresets(loaded)
        return loaded
    }

    fun save(presets: List<Preset>) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (p in presets) {
            val pObj = JSONObject()
            pObj.put("name", p.name)
            pObj.put("preamp", p.preamp.toDouble())
            val bArray = JSONArray()
            for (b in p.bands) {
                val bObj = JSONObject()
                bObj.put("enabled", b.enabled)
                bObj.put("type", b.type)
                bObj.put("freq", b.freq)
                bObj.put("gain", b.gain.toDouble())
                bObj.put("q", b.q.toDouble())
                bArray.put(bObj)
            }
            pObj.put("filters", bArray)
            array.put(pObj)
        }
        prefs.edit().putString(presetsKey, array.toString()).apply()
    }

    private fun ensureSystemPresets(presets: MutableList<Preset>) {
        if (presets.none { it.name == "Flat" }) {
            val flatBands = MutableList(10) { i -> FilterBand(freq = defaultFreqs[i], gain = 0f, enabled = true) }
            presets.add(0, Preset("Flat", 0f, flatBands))
        }

        if (presets.none { it.name == "None" }) {
            val noneBands = MutableList(10) { i -> FilterBand(freq = defaultFreqs[i]) }
            presets.add(Preset("None", 0f, noneBands))
        }
    }
}
