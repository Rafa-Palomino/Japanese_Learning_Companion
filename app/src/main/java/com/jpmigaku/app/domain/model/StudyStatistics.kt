package com.jpmigaku.app.domain.model

data class StatisticsRecord(
    val id: String,
    val label: String,
    val detail: String = "",
    val attempts: Int = 0,
    val correctCount: Int = 0,
    val incorrectCount: Int = 0
) {
    val accuracyRate: Double
        get() = if (attempts == 0) 0.0 else correctCount.toDouble() / attempts
}

data class TopicStatisticsSummary(
    val savedCount: Int = 0,
    val attempts: Int = 0,
    val correctCount: Int = 0,
    val incorrectCount: Int = 0,
    val knownCount: Int = 0,
    val masteredCount: Int = 0,
    val commonErrors: List<StatisticsRecord> = emptyList()
)

object StudyStatisticsCalculator {
    fun summarize(
        savedCount: Int,
        records: List<StatisticsRecord>
    ): TopicStatisticsSummary {
        val commonErrors = records
            .filter { it.attempts > 0 && it.accuracyRate < COMMON_ERROR_ACCURACY_LIMIT }
            .sortedWith(
                compareBy<StatisticsRecord> { it.accuracyRate }
                    .thenByDescending { it.incorrectCount }
            )

        return TopicStatisticsSummary(
            savedCount = savedCount,
            attempts = records.sumOf { it.attempts },
            correctCount = records.sumOf { it.correctCount },
            incorrectCount = records.sumOf { it.incorrectCount },
            knownCount = records.count {
                it.correctCount > KNOWN_CORRECT_ANSWERS && it.accuracyRate > KNOWN_ACCURACY_LIMIT
            },
            masteredCount = records.count {
                it.correctCount > MASTERED_CORRECT_ANSWERS &&
                    it.accuracyRate > MASTERED_ACCURACY_LIMIT
            },
            commonErrors = commonErrors
        )
    }

    private const val COMMON_ERROR_ACCURACY_LIMIT = 0.5
    private const val KNOWN_CORRECT_ANSWERS = 10
    private const val KNOWN_ACCURACY_LIMIT = 0.5
    private const val MASTERED_CORRECT_ANSWERS = 25
    private const val MASTERED_ACCURACY_LIMIT = 0.85
}
