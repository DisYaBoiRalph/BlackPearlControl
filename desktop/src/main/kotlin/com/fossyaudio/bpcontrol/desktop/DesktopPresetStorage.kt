package com.fossyaudio.bpcontrol.desktop

import com.fossyaudio.bpcontrol.data.IPresetStorage
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.FilterType
import com.fossyaudio.bpcontrol.shared.model.Preset
import com.fossyaudio.bpcontrol.shared.preset.DEFAULT_BAND_FREQS
import com.fossyaudio.bpcontrol.shared.preset.ensureSystemPresets
import com.fossyaudio.bpcontrol.shared.preset.migrateLegacyName
import com.fossyaudio.bpcontrol.shared.preset.parsePresetSource
import com.fossyaudio.bpcontrol.transport.protocol.BlackPearlProtocol
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

    override fun load(): List<Preset> {
        val loaded = mutableListOf<Preset>()
        val filePath = presetFilePath()
        if (Files.exists(filePath)) {
            runCatching {
                val raw = Files.readString(filePath, StandardCharsets.UTF_8)
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val pObj = array.getJSONObject(i)
                    val name = migrateLegacyName(pObj.getString("name"))
                    val bArray = pObj.optJSONArray("filters") ?: JSONArray()
                    val bList = mutableListOf<FilterBand>()
                    for (b in 0 until BlackPearlProtocol.Frame.BAND_COUNT) {
                        if (b < bArray.length()) {
                            val bObj = bArray.getJSONObject(b)
                            bList.add(
                                FilterBand(
                                    enabled = bObj.optBoolean("enabled", true),
                                    type = runCatching { FilterType.valueOf(bObj.optString("type", FilterType.PK.name)) }
                                        .getOrDefault(FilterType.PK),
                                    freq = bObj.optInt("freq", DEFAULT_BAND_FREQS[b]),
                                    gain = bObj.optDouble("gain", 0.0).toFloat(),
                                    q = bObj.optDouble("q", 1.0).toFloat()
                                )
                            )
                        } else {
                            bList.add(FilterBand(freq = DEFAULT_BAND_FREQS[b]))
                        }
                    }
                    val source = parsePresetSource(name, pObj.takeIf { it.has("source") }?.getString("source"))
                    val savedAt = pObj.optLong("savedAt", 0L)
                    loaded.add(Preset(name = name, bands = bList, source = source, savedAt = savedAt))
                }
            }
        }

        return ensureSystemPresets(loaded)
    }

    override fun save(presets: List<Preset>) {
        val array = JSONArray()
        for (preset in presets) {
            val pObj = JSONObject()
            pObj.put("name", preset.name)
            pObj.put("source", preset.source.name)
            pObj.put("savedAt", preset.savedAt)
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
