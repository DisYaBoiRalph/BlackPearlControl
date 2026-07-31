package com.fossyaudio.bpcontrol.shared.preset

import com.fossyaudio.bpcontrol.shared.model.FilterBand
import com.fossyaudio.bpcontrol.shared.model.FilterType

data class ParsedEqImport(
    val bands: List<FilterBand>,
    val preamp: Float
)

/** Parses AutoEQ ParametricEQ.txt-style text. Platform code supplies the file's text content. */
object AutoEqParser {

    private val preampRegex = Regex("PREAMP\\s*[:=]?\\s*([-+.\\d]+)")
    private val fcRegex = Regex("FC\\s*[:=]?\\s*([\\d.]+)")
    private val gainRegex = Regex("GAIN\\s*[:=]?\\s*([-+.\\d]+)")
    private val qRegex = Regex("Q\\s*[:=]?\\s*([\\d.]+)")

    fun parse(text: String, maxLines: Int = 200): ParsedEqImport {
        val lines = text.lineSequence().toList()
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
                        ?.toFloatOrNull()?.coerceIn(-12f, 12f) ?: 0f
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
