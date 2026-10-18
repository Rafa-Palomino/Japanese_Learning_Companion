package com.jpmigaku.app.data.repository

internal fun String.withCommaSeparatedKanjiReadings(): String =
    replace(KANJI_READING_SEPARATOR, ", ")

private const val KANJI_READING_SEPARATOR = "\u001F"
