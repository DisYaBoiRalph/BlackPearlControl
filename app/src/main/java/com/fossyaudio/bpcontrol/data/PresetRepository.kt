package com.fossyaudio.bpcontrol.data

import android.content.Context
import android.util.Log
import com.fossyaudio.bpcontrol.data.IPresetStorage
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.FilterType
import com.fossyaudio.bpcontrol.shared.model.Preset
import com.fossyaudio.bpcontrol.shared.preset.DEFAULT_BAND_FREQS
import com.fossyaudio.bpcontrol.shared.preset.ensureSystemPresets
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol
import org.json.JSONArray
import org.json.JSONObject

class PresetRepository(
    private val context: Context,
    private val prefsName: String = "BP_PRESETS",
    private val presetsKey: String = "presets_data"
) : IPresetStorage {

    override fun load(): List<Preset> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(presetsKey, null)
        val loaded = mutableListOf<Preset>()

        if (jsonStr != null) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val pObj = array.getJSONObject(i)
                    val name = pObj.getString("name")
                    val bArray = pObj.getJSONArray("filters")
                    val bList = mutableListOf<FilterBand>()
                    for (b in 0 until BlackPearlProtocol.Frame.BAND_COUNT) {
                        if (b < bArray.length()) {
                            val bObj = bArray.getJSONObject(b)
                            bList.add(
                                FilterBand(
                                    enabled = bObj.getBoolean("enabled"),
                                    type = runCatching {
                                        FilterType.valueOf(bObj.getString("type"))
                                    }.getOrDefault(FilterType.PK),
                                    freq = bObj.getInt("freq"),
                                    gain = bObj.getDouble("gain").toFloat(),
                                    q = bObj.getDouble("q").toFloat()
                                )
                            )
                        } else {
                            bList.add(FilterBand(freq = DEFAULT_BAND_FREQS[b]))
                        }
                    }
                    loaded.add(Preset(name, bList))
                }
            } catch (e: org.json.JSONException) {
                Log.e("Presets", "JSON Parse Error", e)
            }
        }

        return ensureSystemPresets(loaded)
    }

    override fun save(presets: List<Preset>) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (p in presets) {
            val pObj = JSONObject()
            pObj.put("name", p.name)
            val bArray = JSONArray()
            for (b in p.bands) {
                val bObj = JSONObject()
                bObj.put("enabled", b.enabled)
                bObj.put("type", b.type.name)
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
}
