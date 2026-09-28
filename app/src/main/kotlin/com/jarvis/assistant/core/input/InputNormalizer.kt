package com.jarvis.assistant.core.input

/** Normalizes human input without changing meaning. Keeps Roman Urdu/Urdu/English text intact. */
object InputNormalizer {
    fun normalize(input: String): String = input.trim().replace(Regex("\\s+"), " ")
        .replace('’', '\'').replace('“', '"').replace('”', '"')
}

enum class InputLanguage { ENGLISH, ROMAN_URDU, URDU, MIXED, UNKNOWN }

object LanguageDetector {
    private val romanUrdu = setOf("karo", "kar", "kholo", "band", "batao", "dikhao", "yaad", "rakho", "bhejo", "rok", "chalao", "phir", "abhi", "kal", "haan", "nahi")
    fun detect(text: String): InputLanguage {
        val hasUrdu = text.any { it in '\u0600'..'\u06FF' }
        val lower = text.lowercase()
        val words = lower.split(Regex("\\W+")).filter { it.isNotBlank() }
        val romanHits = words.count { it in romanUrdu }
        val latin = lower.count { it in 'a'..'z' }
        return when {
            hasUrdu && latin > 0 -> InputLanguage.MIXED
            hasUrdu -> InputLanguage.URDU
            romanHits > 0 && latin > 0 -> if (words.any { it in setOf("open", "show", "set", "send", "run", "stop", "search") }) InputLanguage.MIXED else InputLanguage.ROMAN_URDU
            latin > 0 -> InputLanguage.ENGLISH
            else -> InputLanguage.UNKNOWN
        }
    }
}
