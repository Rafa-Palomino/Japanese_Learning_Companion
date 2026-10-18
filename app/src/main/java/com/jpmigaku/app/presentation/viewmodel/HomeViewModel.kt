package com.jpmigaku.app.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jpmigaku.app.data.local.DictionaryAssetImporter
import com.jpmigaku.app.data.local.ConjugationAssetImporter
import com.jpmigaku.app.data.repository.NumberQuizStatsRepository
import com.jpmigaku.app.data.repository.DeckRepository
import com.jpmigaku.app.data.repository.DictionaryVocabularyRepository
import com.jpmigaku.app.data.repository.DictionaryKanjiRepository
import com.jpmigaku.app.data.repository.VocabularyRepository
import com.jpmigaku.app.domain.model.Deck
import com.jpmigaku.app.domain.model.DictionaryVocabulary
import com.jpmigaku.app.domain.model.DictionaryKanji
import com.jpmigaku.app.domain.model.KanjiQuizQuestion
import com.jpmigaku.app.domain.model.JapaneseNumber
import com.jpmigaku.app.domain.model.NumberMagnitude
import com.jpmigaku.app.domain.model.NumberQuizStatsSummary
import com.jpmigaku.app.domain.model.StudyStatisticsCalculator
import com.jpmigaku.app.domain.model.TopicStatisticsSummary
import com.jpmigaku.app.domain.model.ConjugationFormOption
import com.jpmigaku.app.domain.model.ConjugationQuizQuestion
import com.jpmigaku.app.domain.model.VocabularyEntry
import com.jpmigaku.app.domain.usecase.CreateDeckUseCase
import com.jpmigaku.app.domain.usecase.CreateVocabularyUseCase
import com.jpmigaku.app.domain.usecase.ReviewVocabularyUseCase
import com.jpmigaku.app.domain.usecase.SearchVocabularyUseCase
import com.jpmigaku.app.domain.util.JapaneseNumberGenerator
import com.jpmigaku.app.domain.util.JapaneseNumberAnswerParser
import com.jpmigaku.app.data.repository.ConjugationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import java.lang.Character
import javax.inject.Inject

enum class StudyArea {
    KANJI,
    VOCABULARY,
    CONJUGATION,
    NUMBERS
}

/**
 * Defines the prompt and answer direction for a quiz session.
 */
enum class QuizMode {
    STUDY,
    KANJI_STUDY,
    JAPANESE_TO_SPANISH,
    JAPANESE_TO_SPANISH_SELECTION,
    JAPANESE_TO_SPANISH_WRITTEN,
    SPANISH_TO_JAPANESE,
    SPANISH_TO_JAPANESE_SELECTION,
    SPANISH_TO_JAPANESE_WRITTEN,
    KANJI_TO_SPANISH_SELECTION,
    SPANISH_TO_KANJI_SELECTION,
    KANJI_TO_READINGS_SELECTION,
    READINGS_TO_KANJI_SELECTION,
    FIND_KANJI_SELECTION,
    CONJUGATION_STUDY,
    CONJUGATION_SELECTION,
    CONJUGATION_WRITTEN,
    NUMBERS_STUDY,
    NUMBERS_KANJI_TO_ARABIC_WRITTEN,
    NUMBERS_ARABIC_TO_KANJI_SELECTION,
    NUMBERS_READING_SELECTION,
    NUMBERS_READING_WRITTEN
}

enum class StatisticsTopic {
    VOCABULARY,
    KANJI,
    CONJUGATION,
    NUMBERS
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val vocabularyRepository: VocabularyRepository,
    private val deckRepository: DeckRepository,
    private val createVocabularyUseCase: CreateVocabularyUseCase,
    private val createDeckUseCase: CreateDeckUseCase,
    private val reviewVocabularyUseCase: ReviewVocabularyUseCase,
    private val searchVocabularyUseCase: SearchVocabularyUseCase,
    private val dictionaryVocabularyRepository: DictionaryVocabularyRepository,
    private val dictionaryKanjiRepository: DictionaryKanjiRepository,
    private val dictionaryAssetImporter: DictionaryAssetImporter,
    private val conjugationAssetImporter: ConjugationAssetImporter,
    private val conjugationRepository: ConjugationRepository,
    private val numberQuizStatsRepository: NumberQuizStatsRepository
) : ViewModel() {
    private val preferences = context.getSharedPreferences("jpmigaku_settings", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()
    private var dictionarySearchJob: Job? = null
    private var kanjiSearchJob: Job? = null
    private var dictionaryImportJob: Job? = null

    init {
        viewModelScope.launch { refreshState() }
    }

    fun onAddVocabularyClicked() {
        openVocabularySearch()
    }

    fun onAddManagedDeckItemClicked() {
        val deck = _uiState.value.decks.firstOrNull { it.id == _uiState.value.managedDeckId } ?: return
        if (deck.kind == KANJI_KIND) {
            openKanjiSearch(deck.id)
        } else {
            openVocabularySearch(deck.id)
        }
    }

    fun onAddManualVocabularyClicked() {
        viewModelScope.launch {
            refreshState()
            _uiState.update {
                it.copy(
                    screen = HomeScreen.PersonalVocabulary,
                    feedback = null,
                    addJlptLevel = "",
                    selectedDeckIds = it.decks.firstOrNull { deck ->
                        deck.kind == VOCABULARY_KIND && deck.name == DEFAULT_VOCABULARY_DECK
                    }
                        ?.let { deck -> setOf(deck.id) }
                        ?: emptySet()
                )
            }
        }
    }

    fun onAddKanjiClicked() {
        openKanjiSearch()
    }

    fun onNumberSelectionClicked() {
        _uiState.update { it.copy(screen = HomeScreen.NumberSelection, feedback = null) }
    }

    fun onNumberMagnitudeSelected(magnitude: NumberMagnitude) {
        preferences.edit().putString(SETTING_NUMBER_MAGNITUDE, magnitude.name).apply()
        _uiState.update { it.copy(numberMagnitude = magnitude) }
    }

    fun onQuizClicked(area: StudyArea) {
        _uiState.update {
            it.copy(
                screen = HomeScreen.QuizMode,
                studyArea = area,
                selectedQuizDeckId = null,
                feedback = null
            )
        }
        viewModelScope.launch {
            refreshState()
            if (area == StudyArea.CONJUGATION) {
                conjugationAssetImporter.importIfNeeded()
                val forms = conjugationRepository.getSelectionForms()
                _uiState.update { state ->
                    state.copy(
                        conjugationForms = forms,
                        selectedConjugationFormKeys = state.selectedConjugationFormKeys.ifEmpty {
                            forms.filter { it.formKey == DICTIONARY_FORM }
                                .mapTo(mutableSetOf()) { it.selectionKey }
                        }
                    )
                }
                ensureDictionaryImport().join()
                updateConjugationDeckCandidates()
            }
            _uiState.update { state ->
                state.copy(
                    selectedQuizDeckId = preferredQuizDeckId(area, state)
                )
            }
        }
    }

    fun onConjugationSelectionClicked() {
        _uiState.update { it.copy(screen = HomeScreen.ConjugationSelection, feedback = null) }
        viewModelScope.launch {
            conjugationAssetImporter.importIfNeeded()
            val forms = conjugationRepository.getSelectionForms()
            _uiState.update { state ->
                state.copy(
                    conjugationForms = forms,
                    selectedConjugationFormKeys = state.selectedConjugationFormKeys.ifEmpty {
                        forms.filter { it.formKey == DICTIONARY_FORM }
                            .mapTo(mutableSetOf()) { it.selectionKey }
                    }
                )
            }
            ensureDictionaryImport().join()
            updateConjugationDeckCandidates()
        }
    }

    fun onConjugationFormSelected(selectionKey: String) {
        val form = _uiState.value.conjugationForms.firstOrNull { it.selectionKey == selectionKey } ?: return
        if (form.formKey == DICTIONARY_FORM) return
        _uiState.update { state ->
            val selected = state.selectedConjugationFormKeys.toMutableSet()
            if (!selected.add(selectionKey)) selected.remove(selectionKey)
            state.copy(selectedConjugationFormKeys = selected)
        }
        viewModelScope.launch { updateConjugationDeckCandidates() }
    }

    fun onConjugationFormDetails(form: ConjugationFormOption) {
        _uiState.update { it.copy(selectedConjugationForm = form) }
    }

    fun onConjugationFormDetailsDismissed() {
        _uiState.update { it.copy(selectedConjugationForm = null) }
    }

    fun onStartQuizClicked(mode: QuizMode) {
        rememberQuizDeckUsed(_uiState.value)
        _uiState.update {
            it.copy(
                screen = HomeScreen.Quiz,
                quizMode = mode,
                quizEntry = null,
                quizAnswer = "",
                quizFeedback = null,
                quizCompleted = false,
                quizCorrectAnswers = 0,
                quizIncorrectAnswers = 0,
                quizOptions = emptyList(),
                quizKanjiQuestion = null,
                quizKanjiQuestions = emptyList(),
                quizConjugationQuestion = null,
                quizConjugationQuestions = emptyList(),
                quizNumber = null,
                quizNumbers = emptyList(),
                quizNumberOptions = emptyList()
            )
        }
        viewModelScope.launch {
            val currentState = _uiState.value
            if (mode.isNumbersMode()) {
                startNumberQuiz(currentState, mode)
                return@launch
            }
            if (mode == QuizMode.FIND_KANJI_SELECTION) {
                startKanjiQuiz(currentState)
                return@launch
            }
            if (mode == QuizMode.CONJUGATION_STUDY ||
                mode == QuizMode.CONJUGATION_SELECTION ||
                mode == QuizMode.CONJUGATION_WRITTEN
            ) {
                startConjugationQuiz(currentState)
                return@launch
            }
            val dueEntries = vocabularyRepository.getDue(
                limit = currentState.vocabularies.size.coerceAtLeast(currentState.quizQuestionCount),
                kind = mode.kindFilter(),
                deckId = currentState.selectedQuizDeckId
            )
            val deckEntries = currentState.selectedQuizDeckId?.let {
                vocabularyRepository.getByDeck(it)
            }.orEmpty().filter { it.kind == mode.kindFilter() }
            val candidateEntries = dueEntries + deckEntries
                .filter { entry ->
                    dueEntries.none { dueEntry -> dueEntry.id == entry.id }
                }
            val quizQueue = buildQuizQueue(candidateEntries, currentState.quizQuestionCount)
            val nextEntry = quizQueue.firstOrNull()
            val options = nextEntry?.let {
                buildQuizOptions(
                    mode = mode,
                    currentEntry = it,
                    queue = quizQueue.drop(1),
                    allEntries = deckEntries,
                    answerCount = currentState.multipleChoiceAnswerCount
                )
            } ?: emptyList()

            _uiState.update {
                it.copy(
                    screen = HomeScreen.Quiz,
                    quizMode = mode,
                    quizEntry = nextEntry,
                    quizAnswer = "",
                    quizFeedback = null,
                    quizCompleted = nextEntry == null,
                    quizCorrectAnswers = 0,
                    quizIncorrectAnswers = 0,
                    quizEntries = quizQueue.drop(1),
                    quizQuestionsRemaining = quizQueue.size,
                    quizOptions = options
                )
            }
        }
    }

    private fun startNumberQuiz(state: HomeUiState, mode: QuizMode) {
        val queue = JapaneseNumberGenerator.randomQueue(
            maxValue = state.numberMagnitude.maxValue,
            count = state.quizQuestionCount
        )
        val current = queue.firstOrNull()
        _uiState.update {
            it.copy(
                screen = HomeScreen.Quiz,
                studyArea = StudyArea.NUMBERS,
                quizMode = mode,
                quizEntry = null,
                quizNumber = current,
                quizNumbers = queue.drop(1),
                quizNumberOptions = current?.let { number ->
                    buildNumberOptions(number, state)
                }.orEmpty(),
                quizAnswer = "",
                quizFeedback = null,
                quizCompleted = current == null,
                quizCorrectAnswers = 0,
                quizIncorrectAnswers = 0,
                quizQuestionsRemaining = queue.size,
                quizOptions = emptyList()
            )
        }
    }

    private fun buildNumberOptions(
        correct: JapaneseNumber,
        state: HomeUiState
    ): List<JapaneseNumber> =
        if (state.quizMode.usesNumberSelection()) {
            JapaneseNumberGenerator.selectionOptions(
                correct = correct,
                maxValue = state.numberMagnitude.maxValue,
                answerCount = state.multipleChoiceAnswerCount
            )
        } else {
            emptyList()
        }

    private suspend fun startConjugationQuiz(state: HomeUiState) {
            val deckId = state.selectedQuizDeckId ?: return
            val candidates = vocabularyRepository.getByDeck(deckId)
                .filter { it.kind == VOCABULARY_KIND && it.sourceProvider.equals(JMDICT_PROVIDER, true) }
            val questions = candidates.mapNotNull {
                conjugationRepository.createStudyQuestion(it, state.selectedConjugationFormKeys)
            }
            val queue = buildConjugationQueue(questions, state.quizQuestionCount)
            val next = queue.firstOrNull()
            _uiState.update {
                it.copy(
                    screen = HomeScreen.Quiz,
                    quizMode = state.quizMode,
                    quizEntry = next?.vocabularyEntry,
                    quizConjugationQuestion = next,
                    quizConjugationQuestions = queue.drop(1),
                    quizAnswer = "",
                    quizFeedback = null,
                    quizCompleted = next == null,
                    quizCorrectAnswers = 0,
                    quizIncorrectAnswers = 0,
                    quizQuestionsRemaining = queue.size,
                    quizOptions = if (state.quizMode == QuizMode.CONJUGATION_SELECTION) {
                        next?.let {
                            buildConjugationOptions(it, state.multipleChoiceAnswerCount)
                        }.orEmpty()
                    } else {
                        emptyList()
                    }
                )
        }
    }

    private suspend fun startKanjiQuiz(state: HomeUiState) {
        val questionPool = buildKanjiQuizQuestionPool(state)
        val quizQueue = buildKanjiQuizQueue(questionPool, state.quizQuestionCount)
        val nextQuestion = quizQueue.firstOrNull()
        val distractorPool = loadKanjiDistractorPool(state)

        _uiState.update {
            it.copy(
                screen = HomeScreen.Quiz,
                quizMode = QuizMode.FIND_KANJI_SELECTION,
                quizEntry = nextQuestion?.targetKanji,
                quizKanjiQuestion = nextQuestion,
                quizAnswer = "",
                quizFeedback = null,
                quizCompleted = nextQuestion == null,
                quizCorrectAnswers = 0,
                quizIncorrectAnswers = 0,
                quizEntries = emptyList(),
                quizKanjiQuestions = quizQueue.drop(1),
                quizQuestionsRemaining = quizQueue.size,
                quizOptions = nextQuestion?.let {
                    buildKanjiQuizOptions(it, distractorPool, state.multipleChoiceAnswerCount)
                }.orEmpty()
            )
        }
    }

    private suspend fun buildKanjiQuizQuestionPool(state: HomeUiState): List<KanjiQuizQuestion> {
        val examDeckId = state.selectedQuizDeckId ?: return emptyList()
        val vocabularyDeckId = state.decks.firstOrNull {
            it.kind == VOCABULARY_KIND && it.name == DEFAULT_VOCABULARY_DECK
        }?.id ?: return emptyList()
        val kanjiEntries = vocabularyRepository.getByDeck(examDeckId)
            .filter { it.kind == KANJI_KIND }
        val vocabularyEntries = vocabularyRepository.getByDeck(vocabularyDeckId)
            .filter { it.kind == VOCABULARY_KIND && it.reading.isNotBlank() }
        val kanjiByCharacter = kanjiEntries
            .mapNotNull { entry ->
                entry.japanese.trim().takeIf { it.isNotEmpty() }?.let { it to entry }
            }
            .toMap()

        return vocabularyEntries.flatMap { vocabularyEntry ->
            vocabularyEntry.japanese.kanjiCharacters().mapNotNull { character ->
                val targetKanji = kanjiByCharacter[character] ?: return@mapNotNull null
                KanjiQuizQuestion(
                    vocabularyEntry = vocabularyEntry,
                    targetKanji = targetKanji,
                    maskedWord = vocabularyEntry.japanese.replaceFirst(character, "＿"),
                    reading = vocabularyEntry.reading,
                    answer = character
                )
            }
        }
    }

    private fun buildKanjiQuizQueue(
        candidates: List<KanjiQuizQuestion>,
        questionCount: Int
    ): List<KanjiQuizQuestion> {
        if (candidates.isEmpty() || questionCount <= 0) return emptyList()

        val queue = ArrayList<KanjiQuizQuestion>(questionCount)
        var previousKey: String? = null
        while (queue.size < questionCount) {
            val round = candidates.shuffled()
            var addedInRound = false
            round.forEach { question ->
                val key = "${question.vocabularyEntry.id}:${question.answer}"
                if (queue.size < questionCount && key != previousKey) {
                    queue += question
                    previousKey = key
                    addedInRound = true
                }
            }
            if (!addedInRound) {
                queue += candidates.first()
                previousKey = "${candidates.first().vocabularyEntry.id}:${candidates.first().answer}"
            }
        }
        return queue
    }

    private suspend fun loadKanjiDistractorPool(state: HomeUiState): List<VocabularyEntry> {
        val selectedDeckEntries = state.selectedQuizDeckId
            ?.let { vocabularyRepository.getByDeck(it) }
            .orEmpty()
            .filter { it.kind == KANJI_KIND }
        if (selectedDeckEntries.size >= KANJI_DISTRACTOR_THRESHOLD) {
            return selectedDeckEntries
        }

        val generalDeckId = state.decks.firstOrNull {
            it.kind == KANJI_KIND && it.name == DEFAULT_KANJI_DECK
        }?.id ?: return selectedDeckEntries
        return vocabularyRepository.getByDeck(generalDeckId)
            .filter { it.kind == KANJI_KIND }
    }

    private fun buildKanjiQuizOptions(
        question: KanjiQuizQuestion,
        distractorPool: List<VocabularyEntry>,
        answerCount: Int
    ): List<String> {
        val distractors = applyKanjiDistractorFilter(
            _question = question,
            candidates = distractorPool.asSequence()
        )
            .filter { it.id != question.targetKanji.id }
            .map { it.japanese.trim() }
            .filter { it.isNotBlank() && it != question.answer }
            .distinct()
            .shuffled()
            .take((answerCount - 1).coerceAtLeast(0))
            .toList()

        return (listOf(question.answer) + distractors).shuffled()
    }

    /*
     * Reserved seam for the future vocabulary-reading ambiguity filter.
     * The beta implementation intentionally leaves all candidates unchanged.
     */
    private fun applyKanjiDistractorFilter(
        _question: KanjiQuizQuestion,
        candidates: Sequence<VocabularyEntry>
    ): Sequence<VocabularyEntry> = candidates

    fun onSearchClicked() {
        viewModelScope.launch {
            refreshState()
            _uiState.update { it.copy(screen = HomeScreen.Search, searchQuery = "", searchResults = emptyList()) }
        }
    }

    fun onBrowseEntriesClicked() {
        viewModelScope.launch {
            refreshState()
            _uiState.update { it.copy(screen = HomeScreen.List, feedback = null) }
        }
    }

    fun onStatisticsClicked() {
        viewModelScope.launch {
            try {
                refreshState()
                conjugationAssetImporter.importIfNeeded()
                val topicStatistics = loadTopicStatistics()
                _uiState.update {
                    it.copy(
                        screen = HomeScreen.Statistics,
                        selectedStatisticsTopic = null,
                        topicStatistics = topicStatistics,
                        feedback = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        screen = HomeScreen.Statistics,
                        feedback = "No se pudieron cargar las estadísticas: ${error.message}"
                    )
                }
            }
        }
    }

    fun onStatisticsTopicClicked(topic: StatisticsTopic) {
        _uiState.update {
            it.copy(
                screen = HomeScreen.StatisticsDetail,
                selectedStatisticsTopic = topic,
                feedback = null
            )
        }
    }

    fun onManageDecksClicked() {
        viewModelScope.launch {
            refreshState()
            _uiState.update { it.copy(screen = HomeScreen.Decks, feedback = null) }
        }
    }

    fun onSettingsClicked() {
        _uiState.update { it.copy(screen = HomeScreen.Settings, feedback = null) }
    }

    fun onShowRomajiChanged(value: Boolean) {
        preferences.edit().putBoolean(SETTING_SHOW_ROMAJI, value).apply()
        _uiState.update { it.copy(showRomaji = value) }
    }

    fun onShowKanaChanged(value: Boolean) {
        preferences.edit().putBoolean(SETTING_SHOW_KANA, value).apply()
        _uiState.update { it.copy(showKanaInVocabulary = value) }
    }

    fun onShowKanjiMeaningChanged(value: Boolean) {
        preferences.edit().putBoolean(SETTING_SHOW_KANJI_MEANING, value).apply()
        _uiState.update { it.copy(showKanjiMeaning = value) }
    }

    fun onQuizQuestionCountChanged(value: Int) {
        require(value in QUIZ_QUESTION_COUNTS)
        preferences.edit().putInt(SETTING_QUIZ_COUNT, value).apply()
        _uiState.update { it.copy(quizQuestionCount = value) }
    }

    fun onMultipleChoiceAnswerCountChanged(value: Int) {
        require(value in MULTIPLE_CHOICE_ANSWER_COUNTS)
        preferences.edit().putInt(SETTING_MULTIPLE_CHOICE_ANSWER_COUNT, value).apply()
        _uiState.update { it.copy(multipleChoiceAnswerCount = value) }
    }

    fun onDeckOpened(deckId: String) {
        viewModelScope.launch {
            val deck = _uiState.value.decks.firstOrNull { it.id == deckId } ?: return@launch
            val entries = vocabularyRepository.getByDeck(deck.id)
            _uiState.update {
                it.copy(
                    screen = HomeScreen.DeckDetail,
                    managedDeckId = deck.id,
                    selectedDeckIds = setOf(deck.id),
                    managedDeckEntries = entries
                )
            }
        }

    }

    fun onDeleteDeckClicked() {
        val deckId = _uiState.value.managedDeckId ?: return
        viewModelScope.launch {
            if (deckRepository.deleteDeckIfEmpty(deckId)) {
                refreshState()
                _uiState.update {
                    it.copy(
                        screen = HomeScreen.Decks,
                        managedDeckId = null,
                        managedDeckEntries = emptyList(),
                        feedback = "Deck eliminado"
                    )
                }
            } else {
                _uiState.update { it.copy(feedback = "Solo se pueden eliminar decks vacíos") }
            }
        }
    }

    fun onManagedDeckEntrySelected(entry: VocabularyEntry) {
        viewModelScope.launch {
            val entryDeckIds = vocabularyRepository.getDeckIdsForCard(entry.id).toSet()
            _uiState.update { state ->
                if (entry.id !in state.managedDeckEntries.map { it.id }) {
                    state
                } else {
                    state.copy(
                        selectedManagedDeckEntry = entry,
                        managedEntryDeckIds = entryDeckIds,
                        selectedDeckIds = emptySet(),
                        newDeckName = "",
                        feedback = null
                    )
                }
            }
        }
    }

    fun onManagedDeckEntryDismissed() {
        _uiState.update {
            it.copy(
                selectedManagedDeckEntry = null,
                managedEntryDeckIds = emptySet(),
                selectedDeckIds = emptySet(),
                newDeckName = ""
            )
        }
    }

    fun onAddManagedDeckEntryToSelectedDecks() {
        val state = _uiState.value
        val entry = state.selectedManagedDeckEntry ?: return
        val deckIdsToAdd = state.selectedDeckIds.filter { deckId ->
            deckId !in state.managedEntryDeckIds &&
                state.decks.any { it.id == deckId && it.kind == entry.kind }
        }
        if (deckIdsToAdd.isEmpty()) {
            _uiState.update { it.copy(feedback = "Selecciona al menos un deck nuevo") }
            return
        }

        viewModelScope.launch {
            vocabularyRepository.addToDecks(entry.id, deckIdsToAdd)
            _uiState.update { current ->
                if (current.selectedManagedDeckEntry?.id != entry.id) {
                    current
                } else {
                    current.copy(
                        selectedManagedDeckEntry = null,
                        managedEntryDeckIds = current.managedEntryDeckIds + deckIdsToAdd,
                        selectedDeckIds = emptySet(),
                        newDeckName = "",
                        feedback = "Tarjeta añadida a los decks seleccionados"
                    )
                }
            }
        }
    }

    private fun buildQuizQueue(
        candidates: List<VocabularyEntry>,
        questionCount: Int
    ): List<VocabularyEntry> {
        if (candidates.isEmpty() || questionCount <= 0) return emptyList()

        // Repeat shuffled rounds so a short collection can fill the configured session.
        val queue = ArrayList<VocabularyEntry>(questionCount)
        var previousId: String? = null
        while (queue.size < questionCount) {
            val round = candidates.shuffled()
            var addedInRound = false
            round.forEach { entry ->
                if (queue.size < questionCount && entry.id != previousId) {
                    queue += entry
                    previousId = entry.id
                    addedInRound = true
                }
            }
            if (!addedInRound) {
                queue += candidates.first()
                previousId = candidates.first().id
            }
        }
        return queue
    }

    private fun buildConjugationQueue(
        candidates: List<ConjugationQuizQuestion>,
        questionCount: Int
    ): List<ConjugationQuizQuestion> {
        if (candidates.isEmpty() || questionCount <= 0) return emptyList()
        val queue = ArrayList<ConjugationQuizQuestion>(questionCount)
        var previousKey: String? = null
        while (queue.size < questionCount) {
            var added = false
            candidates.shuffled().forEach { question ->
                val key = "${question.vocabularyEntry.id}:${question.formDisplayName}"
                if (queue.size < questionCount && key != previousKey) {
                    queue += question
                    previousKey = key
                    added = true
                }
            }
            if (!added) queue += candidates.first()
        }
        return queue
    }

    private suspend fun updateConjugationDeckCandidates() {
        val state = _uiState.value
        val selectedForms = state.selectedConjugationFormKeys
        if (selectedForms.isEmpty()) {
            _uiState.update {
                it.copy(
                    conjugationDeckIds = emptySet(),
                    conjugationDeckWarning = "Selecciona al menos una forma"
                )
            }
            return
        }
        val eligibleDeckIds = buildSet {
            state.decks.filter { it.kind == VOCABULARY_KIND }.forEach { deck ->
                val entries = vocabularyRepository.getByDeck(deck.id)
                val selectedClasses = selectedForms
                    .mapNotNull { it.substringBefore(':').takeIf(String::isNotBlank) }
                    .toSet()
                val containsEverySelectedClass = selectedClasses.all { wordClass ->
                    val classForms = selectedForms.filter {
                        it.startsWith("$wordClass:")
                    }.toSet()
                    entries.any { entry ->
                        entry.kind == VOCABULARY_KIND &&
                            conjugationRepository.isEligibleEntry(entry, classForms)
                    }
                }
                if (containsEverySelectedClass) add(deck.id)
            }
        }
        _uiState.update {
            it.copy(
                conjugationDeckIds = eligibleDeckIds,
                conjugationDeckWarning = if (eligibleDeckIds.isEmpty()) {
                    "No hay decks con verbos o adjetivos compatibles"
                } else {
                    null
                },
                selectedQuizDeckId = it.selectedQuizDeckId?.takeIf { id -> id in eligibleDeckIds }
                    ?: eligibleDeckIds.firstOrNull()
            )
        }
    }

    fun onRemoveEntryFromDeck(entry: VocabularyEntry) {
        val deckId = _uiState.value.managedDeckId ?: return
        viewModelScope.launch {
            vocabularyRepository.removeFromDeck(entry.id, deckId)
            _uiState.update {
                it.copy(managedDeckEntries = vocabularyRepository.getByDeck(deckId))
            }
        }
    }

    fun onBackClicked() {
        _uiState.update { state ->
            state.copy(
                screen = if (state.screen == HomeScreen.StatisticsDetail) {
                    HomeScreen.Statistics
                } else {
                    HomeScreen.Home
                },
                selectedStatisticsTopic = if (state.screen == HomeScreen.StatisticsDetail) {
                    null
                } else {
                    state.selectedStatisticsTopic
                },
                feedback = null
            )
        }
    }

    private suspend fun loadTopicStatistics(): Map<StatisticsTopic, TopicStatisticsSummary> {
        val vocabularyRecords = vocabularyRepository.getReviewStatistics(VOCABULARY_KIND)
        val kanjiRecords = vocabularyRepository.getReviewStatistics(KANJI_KIND)
        val conjugationRecords = conjugationRepository.getReviewStatistics()
        val numbers = _uiState.value.numberQuizStats
        return mapOf(
            StatisticsTopic.VOCABULARY to StudyStatisticsCalculator.summarize(
                savedCount = vocabularyRecords.size,
                records = vocabularyRecords
            ),
            StatisticsTopic.KANJI to StudyStatisticsCalculator.summarize(
                savedCount = kanjiRecords.size,
                records = kanjiRecords
            ),
            StatisticsTopic.CONJUGATION to StudyStatisticsCalculator.summarize(
                savedCount = conjugationRecords.size,
                records = conjugationRecords
            ),
            StatisticsTopic.NUMBERS to TopicStatisticsSummary(
                savedCount = numbers.practicedNumbers,
                attempts = numbers.attempts,
                correctCount = numbers.correctCount,
                incorrectCount = numbers.incorrectCount
            )
        )
    }

    fun onSearchQueryChanged(value: String) {
        _uiState.update { it.copy(searchQuery = value) }
    }

    fun onPerformSearch() {
        viewModelScope.launch {
            val query = _uiState.value.searchQuery.trim()
            if (query.isBlank()) {
                _uiState.update { it.copy(feedback = "Escribe algo para buscar") }
                return@launch
            }
            val results = searchVocabularyUseCase(query, limit = 20)
            _uiState.update { it.copy(searchResults = results) }
        }
    }

    fun onJapaneseChanged(value: String) {
        _uiState.update { it.copy(addJapanese = value) }
    }

    fun onDictionaryQueryChanged(value: String) {
        _uiState.update {
            it.copy(
                dictionaryQuery = value,
                selectedDictionaryVocabulary = null,
                dictionaryResults = if (value.isBlank()) emptyList() else it.dictionaryResults
            )
        }
        dictionarySearchJob?.cancel()
        if (value.isBlank()) return

        dictionarySearchJob = viewModelScope.launch {
            try {
                ensureDictionaryImport().join()
                val results = dictionaryVocabularyRepository.search(value)
                val vocabularyKeys = defaultDeckKeys(VOCABULARY_KIND)
                if (_uiState.value.dictionaryQuery == value) {
                    _uiState.update {
                        it.copy(
                            dictionaryResults = results,
                            dictionaryKeysInDefaultDeck = vocabularyKeys,
                            feedback = null
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(feedback = "No se pudo buscar vocabulario: ${error.message}")
                }
            }
        }
    }

    fun onClipboardSearchLoaded(text: String?) {
        val normalized = text?.trim().orEmpty()
        if (normalized.isBlank()) {
            _uiState.update { it.copy(feedback = "El portapapeles está vacío") }
            return
        }
        onDictionaryQueryChanged(normalized)
    }

    fun onDictionaryVocabularySelected(entry: DictionaryVocabulary) {
        _uiState.update {
            it.copy(
                selectedDictionaryVocabulary = entry,
                addJlptLevel = entry.jlptLevel
            )
        }
    }

    fun onKanjiQueryChanged(value: String) {
        _uiState.update {
            it.copy(
                kanjiQuery = value,
                selectedDictionaryKanji = null,
                kanjiResults = if (value.isBlank()) emptyList() else it.kanjiResults
            )
        }
        kanjiSearchJob?.cancel()
        if (value.isBlank()) return

        kanjiSearchJob = viewModelScope.launch {
            try {
                ensureDictionaryImport().join()
                val results = dictionaryKanjiRepository.search(value)
                val kanjiKeys = defaultDeckKeys(KANJI_KIND)
                if (_uiState.value.kanjiQuery == value) {
                    _uiState.update {
                        it.copy(
                            kanjiResults = results,
                            kanjiKeysInDefaultDeck = kanjiKeys,
                            feedback = null
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(feedback = "No se pudo buscar kanji: ${error.message}")
                }
            }
        }
    }

    fun onDictionaryKanjiSelected(entry: DictionaryKanji) {
        _uiState.update {
            it.copy(
                selectedDictionaryKanji = entry,
                addJlptLevel = entry.jlptLevel
            )
        }
    }

    fun onSelectedDictionaryKanjiDismissed() {
        _uiState.update { it.copy(selectedDictionaryKanji = null) }
    }

    fun onAddSelectedDictionaryKanji() {
        val current = _uiState.value
        val entry = current.selectedDictionaryKanji ?: return
        viewModelScope.launch {
            createVocabularyUseCase(
                japanese = entry.character,
                reading = entry.kanjiReadings(),
                meaningEs = entry.meaning,
                deckIds = compatibleDeckIds(current, KANJI_KIND),
                kind = "KANJI",
                sourceProvider = "kanjidic2",
                sourceKey = entry.character,
                sourceVersion = "3.6.2",
                jlptLevel = current.addJlptLevel,
                sourceSnapshot = "${entry.character}|${entry.onyomi}|${entry.kunyomi}|${entry.meaning}"
            )
            refreshState()
            _uiState.update {
                it.copy(
                    selectedDictionaryKanji = null,
                    kanjiQuery = "",
                    kanjiResults = emptyList(),
                    addJlptLevel = "",
                    feedback = "Kanji añadido: ${entry.character}"
                )
            }
        }
    }

    fun onSelectedDictionaryVocabularyDismissed() {
        _uiState.update { it.copy(selectedDictionaryVocabulary = null) }
    }

    fun onAddSelectedDictionaryVocabulary() {
        val current = _uiState.value
        val entry = current.selectedDictionaryVocabulary ?: return
        viewModelScope.launch {
            createVocabularyUseCase(
                japanese = entry.japanese,
                reading = entry.reading,
                meaningEs = entry.meaning,
                deckIds = compatibleDeckIds(current, VOCABULARY_KIND),
                kind = "VOCABULARY",
                sourceProvider = "jmdict",
                sourceKey = entry.sequenceId,
                sourceVersion = "3.6.2",
                romaji = entry.romaji,
                jlptLevel = current.addJlptLevel,
                sourceSnapshot = "${entry.japanese}|${entry.reading}|${entry.romaji}|${entry.meaning}"
            )
            refreshState()
            _uiState.update {
                it.copy(
                    selectedDictionaryVocabulary = null,
                    dictionaryQuery = "",
                    dictionaryResults = emptyList(),
                    addJlptLevel = "",
                    feedback = "Añadido: ${entry.japanese}"
                )
            }
        }
    }

    fun onReadingChanged(value: String) {
        _uiState.update { it.copy(addReading = value) }
    }

    fun onMeaningChanged(value: String) {
        _uiState.update { it.copy(addMeaning = value) }
    }

    fun onJlptLevelChanged(value: String) {
        _uiState.update { it.copy(addJlptLevel = value.trim().uppercase()) }
    }

    fun onDeckSelected(deckId: String?) {
        if (deckId == null) return
        _uiState.update { state ->
            val selected = state.selectedDeckIds.toMutableSet()
            if (!selected.add(deckId)) selected.remove(deckId)
            state.copy(selectedDeckIds = selected)
        }
    }

    fun onQuizDeckSelected(deckId: String?) {
        _uiState.update { it.copy(selectedQuizDeckId = deckId) }
    }

    fun onDeckNameChanged(value: String) {
        _uiState.update { it.copy(newDeckName = value) }
    }

    fun onCreateDeckClicked() {
        val name = _uiState.value.newDeckName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(feedback = "Introduce un nombre para el deck") }
            return
        }

        viewModelScope.launch {
            val currentState = _uiState.value
            val kind = currentState.selectedManagedDeckEntry?.kind ?: when (currentState.screen) {
                HomeScreen.AddKanji -> KANJI_KIND
                else -> VOCABULARY_KIND
            }
            val created = createDeckUseCase(name, kind)
            refreshState()
            _uiState.update {
                it.copy(
                    selectedDeckIds = it.selectedDeckIds + created.id,
                    newDeckName = "",
                    feedback = "Deck creado: ${created.name}"
                )
            }
        }
    }

    fun onClipboardTextLoaded(text: String?) {
        val normalized = text?.trim().orEmpty()
        if (normalized.isBlank()) {
            _uiState.update { it.copy(feedback = "El portapapeles está vacío") }
            return
        }

        _uiState.update {
            it.copy(
                addJapanese = normalized,
                feedback = "Texto del portapapeles cargado"
            )
        }
    }

    fun onSaveVocabulary() {
        val current = _uiState.value

        viewModelScope.launch {
            if (current.addJapanese.isBlank() || current.addMeaning.isBlank()) {
                _uiState.update { it.copy(feedback = "Completa los campos japones y significado") }
                return@launch
            }

            val saved = createVocabularyUseCase(
                japanese = current.addJapanese,
                reading = current.addReading,
                meaningEs = current.addMeaning,
                jlptLevel = current.addJlptLevel,
                deckIds = compatibleDeckIds(current, VOCABULARY_KIND)
            )

            refreshState()
            _uiState.update {
                it.copy(
                    screen = HomeScreen.List,
                    feedback = "Guardado: ${saved.japanese}",
                    addJapanese = "",
                    addReading = "",
                    addMeaning = "",
                    addJlptLevel = ""
                )
            }
        }
    }

    fun onQuizAnswerChanged(value: String) {
        _uiState.update { it.copy(quizAnswer = value) }
    }

    fun onQuizOptionSelected(option: String) {
        _uiState.update { it.copy(quizAnswer = option) }
        onSubmitQuizAnswer()
    }

    fun onSubmitQuizAnswer() {
        val state = _uiState.value
        if (state.quizMode.isNumbersMode()) {
            submitNumberAnswer(state, state.quizAnswer)
            return
        }
        val currentEntry = state.quizEntry ?: return
        val answer = state.quizAnswer.trim()

        if (!state.quizMode.isStudyMode() && answer.isBlank()) {
            _uiState.update { it.copy(quizFeedback = "Selecciona o escribe una respuesta") }
            return
        }

        val expectedAnswer = if (
            state.quizMode == QuizMode.CONJUGATION_SELECTION ||
            state.quizMode == QuizMode.CONJUGATION_WRITTEN
        ) {
            state.quizConjugationQuestion?.conjugatedText.orEmpty()
        } else if (state.quizMode == QuizMode.FIND_KANJI_SELECTION) {
            state.quizKanjiQuestion?.answer.orEmpty()
        } else {
            state.quizMode.expectedAnswer(currentEntry)
        }
        val isCorrect = if (state.quizMode.isStudyMode()) {
            true
        } else {
            answer.equals(expectedAnswer.trim(), ignoreCase = state.quizMode.usesCaseInsensitiveComparison())
        }

        viewModelScope.launch {
            val reviewEntry = if (state.quizMode == QuizMode.FIND_KANJI_SELECTION) {
                state.quizKanjiQuestion?.targetKanji ?: currentEntry
            } else {
                currentEntry
            }
            if (state.quizMode == QuizMode.CONJUGATION_STUDY ||
                state.quizMode == QuizMode.CONJUGATION_SELECTION ||
                state.quizMode == QuizMode.CONJUGATION_WRITTEN
            ) {
                state.quizConjugationQuestion?.let {
                    conjugationRepository.recordReview(it.conjugationFormId, isCorrect)
                }
            } else {
                reviewVocabularyUseCase(reviewEntry, isCorrect)
            }
            refreshState()

            if (state.quizMode == QuizMode.FIND_KANJI_SELECTION) {
                val nextQuestion = state.quizKanjiQuestions.firstOrNull()
                val nextQuestions = state.quizKanjiQuestions.drop(1)
                val distractorPool = loadKanjiDistractorPool(state)
                _uiState.update {
                    it.copy(
                        quizEntry = nextQuestion?.targetKanji,
                        quizKanjiQuestion = nextQuestion,
                        quizKanjiQuestions = nextQuestions,
                        quizAnswer = "",
                        quizFeedback = if (isCorrect) {
                            "Correcto"
                        } else {
                            "Respuesta esperada: $expectedAnswer"
                        },
                        quizCompleted = nextQuestion == null,
                        quizCorrectAnswers = state.quizCorrectAnswers + if (isCorrect) 1 else 0,
                        quizIncorrectAnswers = state.quizIncorrectAnswers + if (isCorrect) 0 else 1,
                        quizQuestionsRemaining = if (nextQuestion == null) 0 else nextQuestions.size + 1,
                        quizOptions = nextQuestion?.let {
                            buildKanjiQuizOptions(it, distractorPool, state.multipleChoiceAnswerCount)
                        }.orEmpty()
                    )
                }
                return@launch
            }

            if (state.quizMode == QuizMode.CONJUGATION_STUDY ||
                state.quizMode == QuizMode.CONJUGATION_SELECTION ||
                state.quizMode == QuizMode.CONJUGATION_WRITTEN
            ) {
                val nextQuestion = state.quizConjugationQuestions.firstOrNull()
                _uiState.update {
                    it.copy(
                        quizEntry = nextQuestion?.vocabularyEntry,
                        quizConjugationQuestion = nextQuestion,
                        quizConjugationQuestions = state.quizConjugationQuestions.drop(1),
                        quizQuestionsRemaining = if (nextQuestion == null) 0 else state.quizConjugationQuestions.size,
                        quizCompleted = nextQuestion == null,
                        quizOptions = if (state.quizMode == QuizMode.CONJUGATION_SELECTION) {
                            nextQuestion?.let {
                                buildConjugationOptions(it, state.multipleChoiceAnswerCount)
                            }.orEmpty()
                        } else {
                            emptyList()
                        }
                    )
                }
                return@launch
            }

            val remainingEntries = state.quizEntries
            val nextEntry = remainingEntries.firstOrNull()
            val nextQueue = remainingEntries.drop(1)
            val nextOptions = nextEntry?.let {
                buildQuizOptions(
                    mode = state.quizMode,
                    currentEntry = it,
                    queue = nextQueue,
                    allEntries = state.selectedQuizDeckId?.let {
                        vocabularyRepository.getByDeck(it)
                    }.orEmpty().filter { it.kind == state.quizMode.kindFilter() },
                    answerCount = state.multipleChoiceAnswerCount
                )
            } ?: emptyList()

            _uiState.update {
                it.copy(
                    quizEntry = nextEntry,
                    quizEntries = nextQueue,
                    quizAnswer = "",
                    quizFeedback = if (isCorrect) "Correcto" else "Respuesta esperada: $expectedAnswer",
                    quizCompleted = nextEntry == null,
                    quizCorrectAnswers = state.quizCorrectAnswers + if (isCorrect) 1 else 0,
                    quizIncorrectAnswers = state.quizIncorrectAnswers + if (isCorrect) 0 else 1,
                    quizQuestionsRemaining = if (nextEntry == null) 0 else nextQueue.size + 1,
                    quizOptions = nextOptions
                )
            }
        }
    }

    fun onNumberQuizOptionSelected(numberValue: Int) {
        val state = _uiState.value
        if (!state.quizMode.usesNumberSelection()) return
        submitNumberAnswer(state, numberValue.toString())
    }

    private fun submitNumberAnswer(state: HomeUiState, answer: String) {
        val currentNumber = state.quizNumber ?: return
        if (state.quizMode == QuizMode.NUMBERS_STUDY) {
            advanceNumberQuestion(state, isCorrect = null, expectedAnswer = "")
            return
        }
        if (answer.isBlank()) {
            _uiState.update { it.copy(quizFeedback = "Selecciona o escribe una respuesta") }
            return
        }

        val isCorrect = when (state.quizMode) {
            QuizMode.NUMBERS_KANJI_TO_ARABIC_WRITTEN ->
                JapaneseNumberAnswerParser.parseArabic(answer) == currentNumber.value
            QuizMode.NUMBERS_ARABIC_TO_KANJI_SELECTION,
            QuizMode.NUMBERS_READING_SELECTION ->
                JapaneseNumberAnswerParser.parseArabic(answer) == currentNumber.value
            QuizMode.NUMBERS_READING_WRITTEN ->
                JapaneseNumberAnswerParser.matchesReading(answer, currentNumber.readingHiragana)
            else -> false
        }
        viewModelScope.launch {
            numberQuizStatsRepository.recordAnswer(
                numberValue = currentNumber.value,
                quizMode = state.quizMode.name,
                isCorrect = isCorrect
            )
            val statsSummary = numberQuizStatsRepository.getSummary()
            advanceNumberQuestion(
                state = state,
                isCorrect = isCorrect,
                statsSummary = statsSummary,
                expectedAnswer = when (state.quizMode) {
                    QuizMode.NUMBERS_KANJI_TO_ARABIC_WRITTEN -> currentNumber.value.toString()
                    QuizMode.NUMBERS_ARABIC_TO_KANJI_SELECTION -> currentNumber.kanji
                    QuizMode.NUMBERS_READING_SELECTION,
                    QuizMode.NUMBERS_READING_WRITTEN -> currentNumber.readingHiragana
                    else -> ""
                }
            )
        }
    }

    private fun advanceNumberQuestion(
        state: HomeUiState,
        isCorrect: Boolean?,
        statsSummary: NumberQuizStatsSummary? = null,
        expectedAnswer: String
    ) {
        val nextNumber = state.quizNumbers.firstOrNull()
        val remainingNumbers = state.quizNumbers.drop(1)
        _uiState.update {
            it.copy(
                quizNumber = nextNumber,
                quizNumbers = remainingNumbers,
                quizNumberOptions = nextNumber?.let { number -> buildNumberOptions(number, state) }.orEmpty(),
                quizAnswer = "",
                quizFeedback = when {
                    isCorrect == null -> null
                    isCorrect -> "Correcto"
                    else -> "Respuesta esperada: $expectedAnswer"
                },
                quizCompleted = nextNumber == null,
                quizCorrectAnswers = state.quizCorrectAnswers + if (isCorrect == true) 1 else 0,
                quizIncorrectAnswers = state.quizIncorrectAnswers + if (isCorrect == false) 1 else 0,
                quizQuestionsRemaining = if (nextNumber == null) 0 else remainingNumbers.size + 1,
                numberQuizStats = statsSummary ?: it.numberQuizStats
            )
        }
    }

    private fun buildQuizOptions(
        mode: QuizMode,
        currentEntry: VocabularyEntry,
        queue: List<VocabularyEntry>,
        allEntries: List<VocabularyEntry>,
        answerCount: Int
    ): List<String> {
        if (!mode.usesSelectionOptions()) return emptyList()

        val correctAnswer = mode.expectedAnswer(currentEntry).trim()
        if (correctAnswer.isBlank()) return emptyList()

        val normalizedCorrect = correctAnswer.normalizeQuizText()
        val currentConflictKey = mode.answerConflictKey(currentEntry)

        /*
         * Keep the source entry paired with each option. For Spanish-to-Japanese
         * quizzes, two different Japanese forms can share one valid meaning.
         */
        val distractors = (queue + allEntries.filter { it.kind == mode.kindFilter() })
            .asSequence()
            .filter { it.id != currentEntry.id }
            .map { entry -> entry to mode.expectedAnswer(entry).trim() }
            .filter { (_, answer) -> answer.isNotBlank() }
            .distinctBy {
                it.second.normalizeQuizText()
            }

            .filter { (entry, candidate) ->
                candidate.normalizeQuizText() != normalizedCorrect &&
                    mode.answerConflictKey(entry) != currentConflictKey
            }
            .map { (_, answer) -> answer }
            .shuffled()
            .take((answerCount - 1).coerceAtLeast(0))
            .toList()

        return (listOf(correctAnswer) + distractors).shuffled()
    }

    private fun buildConjugationOptions(
        question: ConjugationQuizQuestion,
        answerCount: Int
    ): List<String> {
        val distractors = question.availableAnswers
            .filter { it != question.conjugatedText }
            .shuffled()
            .take((answerCount - 1).coerceAtLeast(0))
        return (listOf(question.conjugatedText) + distractors).shuffled()
    }

    private fun QuizMode.kindFilter(): String = when (this) {
        QuizMode.KANJI_STUDY,
        QuizMode.KANJI_TO_SPANISH_SELECTION,
        QuizMode.SPANISH_TO_KANJI_SELECTION,
        QuizMode.KANJI_TO_READINGS_SELECTION,
        QuizMode.READINGS_TO_KANJI_SELECTION,
        QuizMode.FIND_KANJI_SELECTION -> "KANJI"
        else -> "VOCABULARY"
    }

    private fun QuizMode.expectedAnswer(entry: VocabularyEntry): String = when (this) {
        QuizMode.JAPANESE_TO_SPANISH,
        QuizMode.JAPANESE_TO_SPANISH_SELECTION,
        QuizMode.JAPANESE_TO_SPANISH_WRITTEN,
        QuizMode.KANJI_TO_SPANISH_SELECTION -> entry.meaningEs

        QuizMode.KANJI_TO_READINGS_SELECTION -> entry.reading

        QuizMode.SPANISH_TO_JAPANESE,
        QuizMode.SPANISH_TO_JAPANESE_SELECTION,
        QuizMode.SPANISH_TO_JAPANESE_WRITTEN,
        QuizMode.SPANISH_TO_KANJI_SELECTION,
        QuizMode.READINGS_TO_KANJI_SELECTION,
        QuizMode.FIND_KANJI_SELECTION -> entry.japanese

        QuizMode.STUDY,
        QuizMode.KANJI_STUDY,
        QuizMode.CONJUGATION_STUDY -> entry.meaningEs
        QuizMode.CONJUGATION_SELECTION,
        QuizMode.CONJUGATION_WRITTEN -> ""
        else -> ""
    }

    private fun QuizMode.usesSelectionOptions(): Boolean = when (this) {
        QuizMode.JAPANESE_TO_SPANISH_SELECTION,
        QuizMode.SPANISH_TO_JAPANESE_SELECTION,
        QuizMode.KANJI_TO_SPANISH_SELECTION,
        QuizMode.SPANISH_TO_KANJI_SELECTION,
        QuizMode.KANJI_TO_READINGS_SELECTION,
        QuizMode.READINGS_TO_KANJI_SELECTION -> true
        QuizMode.FIND_KANJI_SELECTION -> true
        QuizMode.CONJUGATION_SELECTION -> true

        else -> false
    }

    private fun QuizMode.usesCaseInsensitiveComparison(): Boolean = when (this) {
        QuizMode.JAPANESE_TO_SPANISH,
        QuizMode.JAPANESE_TO_SPANISH_SELECTION,
        QuizMode.JAPANESE_TO_SPANISH_WRITTEN,
        QuizMode.KANJI_TO_SPANISH_SELECTION,
        QuizMode.KANJI_TO_READINGS_SELECTION -> true

        else -> false
    }

    private fun QuizMode.answerConflictKey(entry: VocabularyEntry): String = when (this) {
        QuizMode.SPANISH_TO_JAPANESE,
        QuizMode.SPANISH_TO_JAPANESE_SELECTION,
        QuizMode.SPANISH_TO_JAPANESE_WRITTEN,
        QuizMode.SPANISH_TO_KANJI_SELECTION -> entry.meaningEs.normalizeQuizText()

        QuizMode.READINGS_TO_KANJI_SELECTION -> entry.reading.normalizeQuizText()
        else -> expectedAnswer(entry).normalizeQuizText()
    }

    private fun String.normalizeQuizText(): String =
        trim().lowercase().replace(Regex("[\\p{Punct}\\s]+"), " ")

    private fun String.kanjiCharacters(): List<String> =
        codePoints().toArray().toList().mapNotNull { codePoint ->
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                String(Character.toChars(codePoint))
            } else {
                null
            }
        }

    private fun QuizMode.isStudyMode(): Boolean =
        this == QuizMode.STUDY ||
            this == QuizMode.KANJI_STUDY ||
            this == QuizMode.CONJUGATION_STUDY ||
            this == QuizMode.NUMBERS_STUDY

    private fun QuizMode.isNumbersMode(): Boolean = this in NUMBER_QUIZ_MODES

    private fun QuizMode.usesNumberSelection(): Boolean =
        this == QuizMode.NUMBERS_ARABIC_TO_KANJI_SELECTION ||
            this == QuizMode.NUMBERS_READING_SELECTION

    private fun StudyArea.deckKind(): String = when (this) {
        StudyArea.KANJI -> KANJI_KIND
        StudyArea.VOCABULARY, StudyArea.CONJUGATION, StudyArea.NUMBERS -> VOCABULARY_KIND
    }

    private fun compatibleDeckIds(state: HomeUiState, kind: String): List<String> =
        state.selectedDeckIds.filter { deckId ->
            state.decks.any { deck -> deck.id == deckId && deck.kind == kind }
        }

    private fun openVocabularySearch(targetDeckId: String? = null) {
        _uiState.update { state ->
            state.copy(
                screen = HomeScreen.AddVocabulary,
                feedback = null,
                dictionaryQuery = "",
                dictionaryResults = emptyList(),
                dictionaryKeysInDefaultDeck = emptySet(),
                selectedDictionaryVocabulary = null,
                addJlptLevel = "",
                selectedDeckIds = decksToAdd(state, VOCABULARY_KIND, targetDeckId)
            )
        }
        refreshDeckSelection(VOCABULARY_KIND, targetDeckId)
    }

    private fun openKanjiSearch(targetDeckId: String? = null) {
        _uiState.update { state ->
            state.copy(
                screen = HomeScreen.AddKanji,
                feedback = null,
                kanjiQuery = "",
                kanjiResults = emptyList(),
                kanjiKeysInDefaultDeck = emptySet(),
                selectedDictionaryKanji = null,
                addJlptLevel = "",
                selectedDeckIds = decksToAdd(state, KANJI_KIND, targetDeckId)
            )
        }
        refreshDeckSelection(KANJI_KIND, targetDeckId)
    }

    private fun refreshDeckSelection(kind: String, targetDeckId: String?) {
        viewModelScope.launch {
            refreshState()
            _uiState.update { state ->
                state.copy(selectedDeckIds = decksToAdd(state, kind, targetDeckId))
            }
        }
    }

    private fun decksToAdd(state: HomeUiState, kind: String, targetDeckId: String?): Set<String> =
        buildSet {
            state.decks.firstOrNull { it.kind == kind && it.name == defaultDeckName(kind) }
                ?.id
                ?.let(::add)
            state.decks.firstOrNull { it.id == targetDeckId && it.kind == kind }
                ?.id
                ?.let(::add)
        }

    private suspend fun defaultDeckKeys(kind: String): Set<String> {
        val deck = _uiState.value.decks.firstOrNull {
            it.kind == kind && it.name == defaultDeckName(kind)
        } ?: return emptySet()
        return vocabularyRepository.getByDeck(deck.id).mapTo(mutableSetOf()) { it.sourceKey }
    }

    private fun defaultDeckName(kind: String): String =
        if (kind == KANJI_KIND) DEFAULT_KANJI_DECK else DEFAULT_VOCABULARY_DECK

    private fun preferredQuizDeckId(area: StudyArea, state: HomeUiState): String? {
        val preferenceKey = area.lastUsedQuizDeckPreferenceKey() ?: return null
        val eligibleDecks = state.decks.filter { deck ->
            deck.kind == area.deckKind() &&
                (area != StudyArea.CONJUGATION || deck.id in state.conjugationDeckIds)
        }
        val lastUsedDeckId = preferences.getString(preferenceKey, null)
        return eligibleDecks.firstOrNull { it.id == lastUsedDeckId }?.id
            ?: eligibleDecks.firstOrNull {
                it.name == defaultDeckName(area.deckKind())
            }?.id
            ?: eligibleDecks.firstOrNull()?.id
    }

    private fun rememberQuizDeckUsed(state: HomeUiState) {
        val preferenceKey = state.studyArea.lastUsedQuizDeckPreferenceKey() ?: return
        val selectedDeckId = state.selectedQuizDeckId ?: return
        val selectedDeck = state.decks.firstOrNull { it.id == selectedDeckId } ?: return
        if (selectedDeck.kind != state.studyArea.deckKind()) return
        if (state.studyArea == StudyArea.CONJUGATION &&
            selectedDeckId !in state.conjugationDeckIds
        ) {
            return
        }
        preferences.edit().putString(preferenceKey, selectedDeckId).apply()
    }

    private fun StudyArea.lastUsedQuizDeckPreferenceKey(): String? = when (this) {
        StudyArea.KANJI -> SETTING_LAST_KANJI_QUIZ_DECK
        StudyArea.VOCABULARY -> SETTING_LAST_VOCABULARY_QUIZ_DECK
        StudyArea.CONJUGATION -> SETTING_LAST_CONJUGATION_QUIZ_DECK
        StudyArea.NUMBERS -> null
    }

    private fun DictionaryKanji.kanjiReadings(): String = buildString {
        if (onyomi.isNotBlank()) append("On: $onyomi")
        if (onyomi.isNotBlank() && kunyomi.isNotBlank()) append("\n")
        if (kunyomi.isNotBlank()) append("Kun: $kunyomi")
    }

    private suspend fun refreshState() {
        val storedDecks = deckRepository.listDecks()
        val decksWithVocabulary = if (storedDecks.none {
                it.name == DEFAULT_VOCABULARY_DECK && it.kind == VOCABULARY_KIND
            }) {
            storedDecks + createDeckUseCase(DEFAULT_VOCABULARY_DECK, VOCABULARY_KIND)
        } else {
            storedDecks
        }
        val decks = if (decksWithVocabulary.none {
                it.name == DEFAULT_KANJI_DECK && it.kind == KANJI_KIND
            }) {
            decksWithVocabulary + createDeckUseCase(DEFAULT_KANJI_DECK, KANJI_KIND)
        } else {
            decksWithVocabulary
        }
        val defaultDeck = decks.firstOrNull {
            it.name == DEFAULT_VOCABULARY_DECK && it.kind == VOCABULARY_KIND
        }
        val vocabulary = vocabularyRepository.getAll()
        val numberMagnitude = NumberMagnitude.values().firstOrNull {
            it.name == preferences.getString(SETTING_NUMBER_MAGNITUDE, null)
        } ?: NumberMagnitude.UNITS
        val numberQuizStats = numberQuizStatsRepository.getSummary()
        _uiState.update { state ->
            state.copy(
                decks = decks,
                vocabularies = vocabulary,
                showRomaji = preferences.getBoolean(SETTING_SHOW_ROMAJI, false),
                showKanaInVocabulary = preferences.getBoolean(SETTING_SHOW_KANA, true),
                showKanjiMeaning = preferences.getBoolean(SETTING_SHOW_KANJI_MEANING, true),
                quizQuestionCount = preferences.getInt(SETTING_QUIZ_COUNT, 10)
                    .coerceIn(QUIZ_QUESTION_COUNTS.first(), QUIZ_QUESTION_COUNTS.last()),
                multipleChoiceAnswerCount = preferences.getInt(
                    SETTING_MULTIPLE_CHOICE_ANSWER_COUNT,
                    DEFAULT_MULTIPLE_CHOICE_ANSWER_COUNT
                ).coerceIn(
                    MULTIPLE_CHOICE_ANSWER_COUNTS.first(),
                    MULTIPLE_CHOICE_ANSWER_COUNTS.last()
                ),
                numberMagnitude = numberMagnitude,
                numberQuizStats = numberQuizStats,
                selectedDeckIds = if (state.selectedDeckIds.isEmpty()) {
                    setOfNotNull(defaultDeck?.id)
                } else {
                    state.selectedDeckIds.intersect(decks.map { it.id }.toSet())
                },
                selectedQuizDeckId = state.selectedQuizDeckId?.takeIf { id ->
                    decks.any { deck ->
                        deck.id == id && deck.kind == state.studyArea.deckKind()
                    }
                }
            )
        }
    }

    private companion object {
        const val DEFAULT_VOCABULARY_DECK = "Vocabulario"
        const val DEFAULT_KANJI_DECK = "Kanji"
        const val VOCABULARY_KIND = "VOCABULARY"
        const val KANJI_KIND = "KANJI"
        const val SETTING_SHOW_ROMAJI = "show_romaji"
        const val SETTING_SHOW_KANA = "show_kana"
        const val SETTING_SHOW_KANJI_MEANING = "show_kanji_meaning"
        const val SETTING_QUIZ_COUNT = "quiz_question_count"
        const val SETTING_MULTIPLE_CHOICE_ANSWER_COUNT = "multiple_choice_answer_count"
        const val SETTING_NUMBER_MAGNITUDE = "number_magnitude"
        const val SETTING_LAST_KANJI_QUIZ_DECK = "last_kanji_quiz_deck"
        const val SETTING_LAST_VOCABULARY_QUIZ_DECK = "last_vocabulary_quiz_deck"
        const val SETTING_LAST_CONJUGATION_QUIZ_DECK = "last_conjugation_quiz_deck"
        const val DEFAULT_MULTIPLE_CHOICE_ANSWER_COUNT = 4
        const val KANJI_DISTRACTOR_THRESHOLD = 20
        const val DICTIONARY_FORM = "DICTIONARY"
        const val JMDICT_PROVIDER = "jmdict"
        val QUIZ_QUESTION_COUNTS = (5..100 step 5).toList()
        val MULTIPLE_CHOICE_ANSWER_COUNTS = listOf(4, 5, 6)
        val NUMBER_QUIZ_MODES = setOf(
            QuizMode.NUMBERS_STUDY,
            QuizMode.NUMBERS_KANJI_TO_ARABIC_WRITTEN,
            QuizMode.NUMBERS_ARABIC_TO_KANJI_SELECTION,
            QuizMode.NUMBERS_READING_SELECTION,
            QuizMode.NUMBERS_READING_WRITTEN
        )
    }

    private fun ensureDictionaryImport(): Job {
        dictionaryImportJob?.let { return it }

        return viewModelScope.launch {
            _uiState.update { it.copy(feedback = "Cargando diccionario local...") }
            try {
                dictionaryAssetImporter.importIfNeeded()
                conjugationAssetImporter.importIfNeeded()
                _uiState.update { state ->
                    if (state.feedback == "Cargando diccionario local...") {
                        state.copy(feedback = null)
                    } else {
                        state
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(feedback = "No se pudo cargar el diccionario: ${error.message}")
                }
            }
        }.also { dictionaryImportJob = it }
    }
}

sealed interface HomeScreen {
    data object Home : HomeScreen
    data object AddVocabulary : HomeScreen
    data object PersonalVocabulary : HomeScreen
    data object AddKanji : HomeScreen
    data object NumberSelection : HomeScreen
    data object ConjugationSelection : HomeScreen
    data object QuizMode : HomeScreen
    data object Quiz : HomeScreen
    data object List : HomeScreen
    data object Search : HomeScreen
    data object Statistics : HomeScreen
    data object StatisticsDetail : HomeScreen
    data object Decks : HomeScreen
    data object DeckDetail : HomeScreen
    data object Settings : HomeScreen
}

/**
 * Immutable state rendered by the home flow and its child screens.
 */
data class HomeUiState(
    val screen: HomeScreen = HomeScreen.Home,
    val studyArea: StudyArea = StudyArea.KANJI,
    val feedback: String? = null,
    val addJapanese: String = "",
    val addReading: String = "",
    val addMeaning: String = "",
    val addJlptLevel: String = "",
    val newDeckName: String = "",
    val selectedDeckIds: Set<String> = emptySet(),
    val selectedQuizDeckId: String? = null,
    val managedDeckId: String? = null,
    val managedDeckEntries: List<VocabularyEntry> = emptyList(),
    val selectedManagedDeckEntry: VocabularyEntry? = null,
    val managedEntryDeckIds: Set<String> = emptySet(),
    val decks: List<Deck> = emptyList(),
    val vocabularies: List<VocabularyEntry> = emptyList(),
    val quizMode: QuizMode = QuizMode.JAPANESE_TO_SPANISH,
    val quizEntry: VocabularyEntry? = null,
    val quizAnswer: String = "",
    val quizFeedback: String? = null,
    val quizCompleted: Boolean = false,
    val quizCorrectAnswers: Int = 0,
    val quizIncorrectAnswers: Int = 0,
    val quizEntries: List<VocabularyEntry> = emptyList(),
    val quizQuestionsRemaining: Int = 0,
    val quizOptions: List<String> = emptyList(),
    val quizKanjiQuestion: KanjiQuizQuestion? = null,
    val quizKanjiQuestions: List<KanjiQuizQuestion> = emptyList(),
    val conjugationForms: List<ConjugationFormOption> = emptyList(),
    val selectedConjugationFormKeys: Set<String> = emptySet(),
    val selectedConjugationForm: ConjugationFormOption? = null,
    val conjugationDeckIds: Set<String> = emptySet(),
    val conjugationDeckWarning: String? = null,
    val numberMagnitude: NumberMagnitude = NumberMagnitude.UNITS,
    val numberQuizStats: NumberQuizStatsSummary = NumberQuizStatsSummary(),
    val topicStatistics: Map<StatisticsTopic, TopicStatisticsSummary> = emptyMap(),
    val selectedStatisticsTopic: StatisticsTopic? = null,
    val quizNumber: JapaneseNumber? = null,
    val quizNumbers: List<JapaneseNumber> = emptyList(),
    val quizNumberOptions: List<JapaneseNumber> = emptyList(),
    val quizConjugationQuestion: ConjugationQuizQuestion? = null,
    val quizConjugationQuestions: List<ConjugationQuizQuestion> = emptyList(),
    val showRomaji: Boolean = false,
    val showKanaInVocabulary: Boolean = true,
    val showKanjiMeaning: Boolean = true,
    val quizQuestionCount: Int = 10,
    val multipleChoiceAnswerCount: Int = 4,
    val searchQuery: String = "",
    val searchResults: List<VocabularyEntry> = emptyList(),
    val dictionaryQuery: String = "",
    val dictionaryResults: List<DictionaryVocabulary> = emptyList(),
    val dictionaryKeysInDefaultDeck: Set<String> = emptySet(),
    val selectedDictionaryVocabulary: DictionaryVocabulary? = null,
    val kanjiQuery: String = "",
    val kanjiResults: List<DictionaryKanji> = emptyList(),
    val kanjiKeysInDefaultDeck: Set<String> = emptySet(),
    val selectedDictionaryKanji: DictionaryKanji? = null
)
