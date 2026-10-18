package com.jpmigaku.app.domain.model

enum class NumberMagnitude(val maxValue: Int) {
    UNITS(9),
    TENS(99),
    HUNDREDS(999),
    THOUSANDS(9_999),
    TEN_THOUSANDS(99_999),
    HUNDRED_THOUSANDS(999_999),
    MILLIONS(9_999_999),
    TEN_MILLIONS(99_999_999),
    OKU(999_999_999)
}

data class JapaneseNumber(
    val value: Int,
    val kanji: String,
    val readingHiragana: String,
    val romaji: String
)

data class NumberQuizStatsSummary(
    val practicedNumbers: Int = 0,
    val attempts: Int = 0,
    val correctCount: Int = 0,
    val incorrectCount: Int = 0
)
