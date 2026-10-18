package com.jpmigaku.app.data.local.entity

import androidx.room.Entity

@Entity(
    tableName = "number_quiz_stats",
    primaryKeys = ["numberValue", "quizMode"]
)
data class NumberQuizStatsEntity(
    val numberValue: Int,
    val quizMode: String,
    val attempts: Int = 0,
    val correctCount: Int = 0,
    val incorrectCount: Int = 0,
    val lastReviewedAt: Long? = null
)
