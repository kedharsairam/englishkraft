package com.krafttools.englishkraft.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the reader screen shows. */
data class ReaderState(
    val level: Double = 0.98,
    val ranks: Map<Double, Int?> = emptyMap(),
    val passage: AdaptiveReader.Passage? = null,
    val loading: Boolean = false,
    /** Bumped to ask for a different passage at the same level. */
    val requestId: Int = 0,
) {
    val knownRank: Int? get() = ranks[level]
    /** True only when a search has run and found nothing. Not a pending state. */
    val failed: Boolean
        get() = passage != null && passage.sentences.isEmpty() && !loading
}

/**
 * Drives the adaptive reader.
 *
 * Holds the repository rather than reopening it: the corpus is 450 MB and
 * [com.krafttools.englishkraft.data.AppViewModel] already has it open.
 */
class ReaderViewModel(
    private val repository: DictionaryRepository,
) : ViewModel() {

    private val reader = AdaptiveReader(repository)

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var job: Job? = null

    /** Computes the level thresholds once, then builds a passage. */
    fun start() {
        viewModelScope.launch {
            val ranks = withContext(Dispatchers.IO) { reader.ranksFor(reader.LEVELS) }
            _state.value = _state.value.copy(ranks = ranks)
            load()
        }
    }

    fun setLevel(level: Double) {
        if (level == _state.value.level) return
        _state.value = _state.value.copy(level = level)
        load()
    }

    fun another() {
        _state.value = _state.value.copy(requestId = _state.value.requestId + 1)
        load()
    }

    private fun load() {
        val rank = _state.value.ranks[_state.value.level] ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val passage = withContext(Dispatchers.IO) { reader.passage(rank) }
            _state.value = _state.value.copy(passage = passage, loading = false)
        }
    }
}