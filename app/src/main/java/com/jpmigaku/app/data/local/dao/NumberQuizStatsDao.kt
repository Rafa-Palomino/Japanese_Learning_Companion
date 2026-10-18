package com.jpmigaku.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jpmigaku.app.data.local.entity.NumberQuizStatsEntity

@Dao
interface NumberQuizStatsDao {
    @Query(
        """
        SELECT * FROM number_quiz_stats
        WHERE numberValue = :numberValue AND quizMode = :quizMode
        """
    )
    suspend fun get(numberValue: Int, quizMode: String): NumberQuizStatsEntity?

    @Query("SELECT * FROM number_quiz_stats ORDER BY numberValue, quizMode")
    suspend fun getAll(): List<NumberQuizStatsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stats: NumberQuizStatsEntity)
}
