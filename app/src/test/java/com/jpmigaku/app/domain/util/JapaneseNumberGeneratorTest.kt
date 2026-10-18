package com.jpmigaku.app.domain.util

import com.jpmigaku.app.domain.model.NumberMagnitude
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseNumberGeneratorTest {
    @Test
    fun generatesKanjiAndReadingsForDigitsAndPositions() {
        assertNumber(1, "一", "いち")
        assertNumber(10, "十", "じゅう")
        assertNumber(11, "十一", "じゅういち")
        assertNumber(20, "二十", "にじゅう")
        assertNumber(100, "百", "ひゃく")
        assertNumber(300, "三百", "さんびゃく")
        assertNumber(600, "六百", "ろっぴゃく")
        assertNumber(800, "八百", "はっぴゃく")
        assertNumber(1_000, "千", "せん")
        assertNumber(3_000, "三千", "さんぜん")
        assertNumber(8_000, "八千", "はっせん")
    }

    @Test
    fun generatesNumbersAcrossFourDigitGroups() {
        assertNumber(10_000, "一万", "いちまん")
        assertNumber(100_000, "十万", "じゅうまん")
        assertNumber(1_000_000, "百万", "ひゃくまん")
        assertNumber(10_000_000, "千万", "せんまん")
        assertNumber(100_000_000, "一億", "いちおく")
        assertNumber(
            123_456_789,
            "一億二千三百四十五万六千七百八十九",
            "いちおくにせんさんびゃくよんじゅうごまんろくせんななひゃくはちじゅうきゅう"
        )
        assertNumber(30_800, "三万八百", "さんまんはっぴゃく")
    }

    @Test
    fun magnitudeOptionsHaveIndependentDigitLimitsThroughOku() {
        assertEquals(
            listOf(9, 99, 999, 9_999, 99_999, 999_999, 9_999_999, 99_999_999, 999_999_999),
            NumberMagnitude.values().map { it.maxValue }
        )
    }

    @Test
    fun randomQueueStaysInRangeAndAvoidsImmediateRepeatsWhenPossible() {
        val queue = JapaneseNumberGenerator.randomQueue(
            maxValue = 3,
            count = 10,
            random = Random(7)
        )

        assertEquals(10, queue.size)
        assertTrue(queue.all { it.value in 1..3 })
        assertTrue(queue.zipWithNext().all { (first, second) -> first.value != second.value })
    }

    @Test
    fun singleValueRangeCanRepeatToFillSession() {
        val queue = JapaneseNumberGenerator.randomQueue(
            maxValue = 1,
            count = 5,
            random = Random(1)
        )

        assertEquals(listOf(1, 1, 1, 1, 1), queue.map { it.value })
    }

    @Test
    fun optionsIncludeCorrectValueAndOnlyUniqueInRangeValues() {
        val correct = JapaneseNumberGenerator.generate(9)
        val options = JapaneseNumberGenerator.selectionOptions(
            correct = correct,
            maxValue = 9,
            answerCount = 6,
            random = Random(2)
        )

        assertEquals(6, options.size)
        assertEquals(6, options.map { it.value }.distinct().size)
        assertTrue(options.any { it.value == 9 })
        assertTrue(options.all { it.value in 1..9 })
    }

    @Test
    fun selectionOptionsReduceCountWhenRangeIsTooSmall() {
        val options = JapaneseNumberGenerator.selectionOptions(
            correct = JapaneseNumberGenerator.generate(1),
            maxValue = 2,
            answerCount = 6,
            random = Random(3)
        )

        assertEquals(listOf(1, 2), options.map { it.value }.sorted())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNumbersAboveOku() {
        JapaneseNumberGenerator.generate(NumberMagnitude.OKU.maxValue + 1)
    }

    @Test
    fun conversionProvidesRomajiFromCanonicalHiragana() {
        assertEquals("roppyaku", JapaneseNumberGenerator.generate(600).romaji)
        assertNotEquals("", JapaneseNumberGenerator.generate(123_456).romaji)
    }

    @Test
    fun katakanaAnswerNormalizesToCanonicalHiragana() {
        assertEquals("ろっぴゃく", "ロッピャク".toHiragana())
    }

    @Test
    fun parsesOnlyPositiveAsciiArabicNumbersThroughOku() {
        assertEquals(123_456, JapaneseNumberAnswerParser.parseArabic(" 123456 "))
        listOf("", "0", "-1", "+1", "1.000", "1,000", "١", "1000000000")
            .forEach { answer ->
                assertEquals(null, JapaneseNumberAnswerParser.parseArabic(answer))
            }
    }

    @Test
    fun readingComparisonAcceptsKatakanaButNotRomaji() {
        assertTrue(JapaneseNumberAnswerParser.matchesReading(" ロッピャク ", "ろっぴゃく"))
        assertTrue(!JapaneseNumberAnswerParser.matchesReading("roppyaku", "ろっぴゃく"))
    }

    private fun assertNumber(value: Int, kanji: String, reading: String) {
        val number = JapaneseNumberGenerator.generate(value)

        assertEquals(value, number.value)
        assertEquals(kanji, number.kanji)
        assertEquals(reading, number.readingHiragana)
    }
}
