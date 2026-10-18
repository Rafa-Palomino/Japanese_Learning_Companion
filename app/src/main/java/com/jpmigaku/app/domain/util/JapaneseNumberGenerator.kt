package com.jpmigaku.app.domain.util

import com.jpmigaku.app.domain.model.JapaneseNumber
import com.jpmigaku.app.domain.model.NumberMagnitude
import kotlin.random.Random

object JapaneseNumberGenerator {
    private val digitKanji = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    private val digitReadings = listOf("", "いち", "に", "さん", "よん", "ご", "ろく", "なな", "はち", "きゅう")
    private val scales = listOf(
        NumberScale(100_000_000, "億", "おく"),
        NumberScale(10_000, "万", "まん"),
        NumberScale(1, "", "")
    )

    fun generate(value: Int): JapaneseNumber {
        require(value in 1..NUMBER_MAGNITUDE_LIMIT) {
            "El número debe estar entre 1 y $NUMBER_MAGNITUDE_LIMIT"
        }

        var remainder = value
        val kanji = StringBuilder()
        val reading = StringBuilder()
        scales.forEach { scale ->
            val group = remainder / scale.value
            remainder %= scale.value
            if (group == 0) return@forEach

            val renderedGroup = renderGroup(group)
            kanji.append(renderedGroup.kanji).append(scale.kanji)
            reading.append(renderedGroup.reading).append(scale.reading)
        }

        val readingHiragana = reading.toString()
        return JapaneseNumber(
            value = value,
            kanji = kanji.toString(),
            readingHiragana = readingHiragana,
            romaji = readingHiragana.toRomaji()
        )
    }

    fun randomQueue(
        maxValue: Int,
        count: Int,
        random: Random = Random.Default
    ): List<JapaneseNumber> {
        require(maxValue in 1..NUMBER_MAGNITUDE_LIMIT)
        require(count >= 0)
        if (count == 0) return emptyList()

        val uniqueCount = minOf(maxValue, count)
        val values = LinkedHashSet<Int>(uniqueCount)
        while (values.size < uniqueCount) {
            values += random.nextInt(from = 1, until = maxValue + 1)
        }

        val candidates = values.map(::generate)
        val queue = ArrayList<JapaneseNumber>(count)
        var previousValue: Int? = null
        while (queue.size < count) {
            candidates.shuffled(random).forEach { number ->
                if (queue.size < count &&
                    (candidates.size == 1 || number.value != previousValue)
                ) {
                    queue += number
                    previousValue = number.value
                }
            }
        }
        return queue
    }

    fun selectionOptions(
        correct: JapaneseNumber,
        maxValue: Int,
        answerCount: Int,
        random: Random = Random.Default
    ): List<JapaneseNumber> {
        require(maxValue in 1..NUMBER_MAGNITUDE_LIMIT)
        require(answerCount > 0)

        val neededDistractors = minOf(answerCount - 1, maxValue - 1)
        val values = LinkedHashSet<Int>(neededDistractors + 1).apply {
            add(correct.value)
        }
        while (values.size < neededDistractors + 1) {
            values += random.nextInt(from = 1, until = maxValue + 1)
        }
        return values.map(::generate).shuffled(random)
    }

    private fun renderGroup(value: Int): RenderedGroup {
        require(value in 1..9_999)
        var remainder = value
        val kanji = StringBuilder()
        val reading = StringBuilder()
        val positions = listOf(
            NumberPosition(1_000, "千"),
            NumberPosition(100, "百"),
            NumberPosition(10, "十"),
            NumberPosition(1, "")
        )

        positions.forEach { position ->
            val digit = remainder / position.value
            remainder %= position.value
            if (digit == 0) return@forEach

            if (position.value == 1 || digit != 1) {
                kanji.append(digitKanji[digit])
            }
            kanji.append(position.kanji)
            reading.append(position.readingFor(digit))
        }

        return RenderedGroup(kanji.toString(), reading.toString())
    }

    private data class NumberScale(val value: Int, val kanji: String, val reading: String)

    private data class NumberPosition(val value: Int, val kanji: String) {
        fun readingFor(digit: Int): String = when (value) {
            1 -> digitReadings[digit]
            10 -> if (digit == 1) "じゅう" else digitReadings[digit] + "じゅう"
            100 -> when (digit) {
                1 -> "ひゃく"
                3 -> "さんびゃく"
                6 -> "ろっぴゃく"
                8 -> "はっぴゃく"
                else -> digitReadings[digit] + "ひゃく"
            }
            1_000 -> when (digit) {
                1 -> "せん"
                3 -> "さんぜん"
                8 -> "はっせん"
                else -> digitReadings[digit] + "せん"
            }
            else -> error("Posición numérica no soportada: $value")
        }
    }

    private data class RenderedGroup(val kanji: String, val reading: String)

    private const val NUMBER_MAGNITUDE_LIMIT = 999_999_999
}
