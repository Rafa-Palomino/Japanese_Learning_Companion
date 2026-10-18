package com.jpmigaku.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StudyStatisticsCalculatorTest {
    @Test
    fun aggregatesTotalsAndCountsKnownAndMasteredItemsWithStrictThresholds() {
        val summary = StudyStatisticsCalculator.summarize(
            savedCount = 5,
            records = listOf(
                record("known", attempts = 21, correct = 11, incorrect = 10),
                record("mastered", attempts = 30, correct = 26, incorrect = 4),
                record("known-boundary", attempts = 20, correct = 10, incorrect = 10),
                record("accuracy-boundary", attempts = 20, correct = 10, incorrect = 10),
                record("mastery-accuracy-boundary", attempts = 100, correct = 85, incorrect = 15)
            )
        )

        assertEquals(5, summary.savedCount)
        assertEquals(191, summary.attempts)
        assertEquals(142, summary.correctCount)
        assertEquals(49, summary.incorrectCount)
        assertEquals(3, summary.knownCount)
        assertEquals(1, summary.masteredCount)
    }

    @Test
    fun listsOnlyItemsBelowHalfAccuracyOrderedByAccuracyThenErrorCount() {
        val summary = StudyStatisticsCalculator.summarize(
            savedCount = 4,
            records = listOf(
                record("tie-fewer-errors", attempts = 10, correct = 4, incorrect = 6),
                record("tie-more-errors", attempts = 20, correct = 8, incorrect = 12),
                record("lowest", attempts = 10, correct = 2, incorrect = 8),
                record("at-limit", attempts = 10, correct = 5, incorrect = 5),
                record("unattempted", attempts = 0, correct = 0, incorrect = 0)
            )
        )

        assertEquals(
            listOf("lowest", "tie-more-errors", "tie-fewer-errors"),
            summary.commonErrors.map { it.id }
        )
    }

    private fun record(
        id: String,
        attempts: Int,
        correct: Int,
        incorrect: Int
    ) = StatisticsRecord(
        id = id,
        label = id,
        attempts = attempts,
        correctCount = correct,
        incorrectCount = incorrect
    )
}
