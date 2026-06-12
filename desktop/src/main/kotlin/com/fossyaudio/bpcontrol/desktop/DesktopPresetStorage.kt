package com.fossyaudio.bpcontrol.desktop

import com.fossyaudio.bpcontrol.data.IPresetStorage
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.FilterType
import com.fossyaudio.bpcontrol.shared.model.Preset
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class DesktopPresetStorage(
    private val appDirName: String = "BlackPearlControl",
    private val fileName: String = "presets.json"
) : IPresetStorage {

    private val defaultFreqs = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    override fun load(): MutableList<Preset> {
        val loaded = mutableListOf<Preset>()
        val filePath = presetFilePath()
        if (Files.exists(filePath)) {
            runCatching {
                val raw = Files.readString(filePath, StandardCharsets.UTF_8)
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val pObj = array.getJSONObject(i)
                    val name = pObj.getString("name")
                    val preamp = pObj.optDouble("preamp", 0.0).toFloat()
                    val bArray = pObj.optJSONArray("filters") ?: JSONArray()
                    val bList = mutableListOf<FilterBand>()
                    for (b in 0 until 10) {
                        if (b < bArray.length()) {
                            val bObj = bArray.getJSONObject(b)
                            bList.add(
                                FilterBand(
                                    enabled = bObj.optBoolean("enabled", true),
                                    type = runCatching { FilterType.valueOf(bObj.optString("type", FilterType.PK.name)) }
                                        .getOrDefault(FilterType.PK),
                                    freq = bObj.optInt("freq", defaultFreqs[b]),
                                    gain = bObj.optDouble("gain", 0.0).toFloat(),
                                    q = bObj.optDouble("q", 1.0).toFloat()
                                )
                            )
                        } else {
                            bList.add(FilterBand(freq = defaultFreqs[b]))
                        }
                    }
                    loaded.add(Preset(name = name, preamp = preamp, bands = bList))
                }
            }
        }

        ensureSystemPresets(loaded)
        return loaded
    }

    override fun save(presets: List<Preset>) {
        val array = JSONArray()
        for (preset in presets) {
            val pObj = JSONObject()
            pObj.put("name", preset.name)
            pObj.put("preamp", preset.preamp.toDouble())
            val bands = JSONArray()
            for (band in preset.bands) {
                val bObj = JSONObject()
                bObj.put("enabled", band.enabled)
                bObj.put("type", band.type.name)
                bObj.put("freq", band.freq)
                bObj.put("gain", band.gain.toDouble())
                bObj.put("q", band.q.toDouble())
                bands.put(bObj)
            }
            pObj.put("filters", bands)
            array.put(pObj)
        }

        val filePath = presetFilePath()
        Files.createDirectories(filePath.parent)
        Files.writeString(filePath, array.toString(2), StandardCharsets.UTF_8)
    }

    private fun ensureSystemPresets(presets: MutableList<Preset>) {
        if (presets.none { it.name == "Flat" }) {
            presets.add(
                0,
                Preset(
                    name = "Flat",
                    preamp = 0f,
                    bands = MutableList(10) { i -> FilterBand(freq = defaultFreqs[i], gain = 0f, enabled = true) }
                )
            )
        }

        if (presets.none { it.name == "None" }) {
            presets.add(
                Preset(
                    name = "None",
                    preamp = 0f,
                    bands = MutableList(10) { i -> FilterBand(freq = defaultFreqs[i]) }
                )
            )
        }
    }

    private fun presetFilePath(): Path {
        return appDataDir().resolve(fileName)
    }

    private fun appDataDir(): Path {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("win") -> {
                val base = System.getenv("APPDATA")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Paths.get(it) }
                    ?: Paths.get(System.getProperty("user.home"), "AppData", "Roaming")
                base.resolve(appDirName)
            }
            osName.contains("mac") -> Paths.get(System.getProperty("user.home"), "Library", "Application Support", appDirName)
            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Paths.get(it) }
                (xdg ?: Paths.get(System.getProperty("user.home"), ".config")).resolve(appDirName)
            }
        }
    }
}
