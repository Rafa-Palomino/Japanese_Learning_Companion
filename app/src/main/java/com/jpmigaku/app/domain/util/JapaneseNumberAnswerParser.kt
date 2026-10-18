package com.jpmigaku.app.domain.util

import com.jpmigaku.app.domain.model.NumberMagnitude

object JapaneseNumberAnswerParser {
    fun parseArabic(input: String): Int? {
        val normalized = input.trim()
        if (normalized.isEmpty() || normalized.any { it !in '0'..'9' }) return null
        return normalized.toIntOrNull()?.takeIf { it in 1..NumberMagnitude.OKU.maxValue }
    }

    fun matchesReading(input: String, expected: String): Boolean =
        input.trim().toHiragana() == expected
}
