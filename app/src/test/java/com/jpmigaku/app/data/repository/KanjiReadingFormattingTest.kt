package com.jpmigaku.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class KanjiReadingFormattingTest {
    @Test
    fun separatesOnAndKunReadingsWithCommas() {
        val rawReadings = "On: ライ\u001Fリ\nKun: く.る\u001Fきた.る"

        assertEquals(
            "On: ライ, リ\nKun: く.る, きた.る",
            rawReadings.withCommaSeparatedKanjiReadings()
        )
    }
}
