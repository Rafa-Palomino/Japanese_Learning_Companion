package com.jpmigaku.app.data.repository

import androidx.room.withTransaction
import com.jpmigaku.app.data.local.JPMigakuDatabase
import com.jpmigaku.app.data.local.entity.NumberQuizStatsEntity
import com.jpmigaku.app.domain.model.NumberQuizStatsSummary
import javax.inject.Inject

class NumberQuizStatsRepository @Inject constructor(
    private val database: JPMigakuDatabase
) {
    private val dao = database.numberQuizStatsDao()

    suspend fun recordAnswer(numberValue: Int, quizMode: String, isCorrect: Boolean) {
        database.withTransaction {
            val previous = dao.get(numberValue, quizMode)
            val now = System.currentTimeMillis()
            dao.insert(
                NumberQuizStatsEntity(
                    numberValue = numberValue,
                    quizMode = quizMode,
                    attempts = (previous?.attempts ?: 0) + 1,
                    correctCount = (previous?.correctCount ?: 0) + if (isCorrect) 1 else 0,
                    incorrectCount = (previous?.incorrectCount ?: 0) + if (isCorrect) 0 else 1,
                    lastReviewedAt = now
                )
            )
        }
    }

    suspend fun getAll(): List<NumberQuizStatsEntity> = dao.getAll()

    suspend fun getSummary(): NumberQuizStatsSummary {
        val stats = dao.getAll()
        return NumberQuizStatsSummary(
            practicedNumbers = stats.map { it.numberValue }.distinct().size,
            attempts = stats.sumOf { it.attempts },
            correctCount = stats.sumOf { it.correctCount },
            incorrectCount = stats.sumOf { it.incorrectCount }
        )
    }
}
