package com.fossyaudio.bpcontrol.presentation

import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.FilterType
import java.io.InputStream

data class ParsedEqImport(
    val bands: List<FilterBand>,
    val preamp: Float
)

object AutoEqParser {

    private val preampRegex = Regex("PREAMP\\s*[:=]?\\s*([-+.\\d]+)")
    private val fcRegex = Regex("FC\\s*[:=]?\\s*([\\d.]+)")
    private val gainRegex = Regex("GAIN\\s*[:=]?\\s*([-+.\\d]+)")
    private val qRegex = Regex("Q\\s*[:=]?\\s*([\\d.]+)")

    fun parse(inputStream: InputStream, maxLines: Int = 200): ParsedEqImport {
        val lines = inputStream.bufferedReader().readLines()
        val tempBands = mutableListOf<FilterBand>()
        var parsedPreamp = 0f

        val limit = minOf(lines.size, maxLines)
        for (i in 0 until limit) {
            val line = lines[i].trim().uppercase()

            if (line.contains("PREAMP")) {
                preampRegex.find(line)?.let {
                    parsedPreamp = it.groupValues.getOrNull(1)?.toFloatOrNull() ?: 0f
                }
            }

            if (line.contains("FILTER") && tempBands.size < 10) {
                val fcMatch = fcRegex.find(line)
                val gainMatch = gainRegex.find(line)
                val qMatch = qRegex.find(line)

                if (fcMatch != null) {
                    val f = fcMatch.groupValues.getOrNull(1)
                        ?.toFloatOrNull()?.toInt()?.coerceIn(20, 20000) ?: 1000
                    val g = gainMatch?.groupValues?.getOrNull(1)
                        ?.toFloatOrNull()?.coerceIn(-10f, 10f) ?: 0f
                    val q = qMatch?.groupValues?.getOrNull(1)
                        ?.toFloatOrNull()?.coerceIn(0.1f, 10f) ?: 1f
                    val t = when {
                        line.contains("LS") -> FilterType.LS
                        line.contains("HS") -> FilterType.HS
                        else -> FilterType.PK
                    }
                    val en = !line.contains("OFF")
                    tempBands.add(FilterBand(enabled = en, type = t, freq = f, gain = g, q = q))
                }
            }
        }

        return ParsedEqImport(bands = tempBands, preamp = parsedPreamp)
    }
}
