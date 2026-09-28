package com.jarvis.assistant.wingo.data

import com.jarvis.assistant.wingo.analysis.ModelOutput
import java.util.Locale

/** Compact JSON for the per-model outputs stored with every prediction (no android.* dependency). */
object ModelOutputsCodec {
    fun encode(outputs: List<ModelOutput>): String = buildString {
        append('[')
        outputs.forEachIndexed { i, o ->
            if (i > 0) append(',')
            append("{\"model\":\"").append(escape(o.modelName)).append("\",")
            append("\"probBig\":").append(String.format(Locale.US, "%.4f", o.probBig)).append(',')
            append("\"samples\":").append(o.sampleSize).append(',')
            append("\"abstained\":").append(o.abstained).append(',')
            append("\"lean\":\"").append(o.prediction?.name ?: "NONE").append("\",")
            append("\"evidence\":\"").append(escape(o.evidence.ifBlank { o.reason })).append("\"}")
        }
        append(']')
    }

    private fun escape(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(' ') else sb.append(c)
            }
        }
        return sb.toString()
    }
}
