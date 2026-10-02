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
    /**
     * The rank to open at. Passed in from the placement result, or nil-equivalent
     * when the learner has not been tested — in which case [start] falls back to the
     * coverage offered on screen.
     */
    private val startRank: Int? = null,
) : ViewModel() {

    private val reader = AdaptiveReader(repository)

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var job: Job? = null

    /** Computes the level thresholds once, then builds a passage. */
    /**
     * Computes the level thresholds, opens at the placement level, builds a passage.
     *
     * When no placement has been taken the reader opens at 98%, the highest offered,
     * and the screen says which level it is on. Assuming a level the learner has not
     * agreed to is the one guess this app is not allowed to make.
     *
     * A placement rank that falls between two offered levels snaps to the nearest
     * one, so the label on screen is always true. Snapping *up* to 98% because 93%
     * is nearer to it than to 95% would make the label wrong, which is why the
     * comparison is on absolute distance and the levels are then ordered.
     */
    fun start() {
        viewModelScope.launch {
            val ranks = withContext(Dispatchers.IO) { reader.ranksFor(reader.LEVELS) }
            val level = startRank?.let { rank ->
                // Snap DOWN, by coverage, never by nearest rank.
                //
                // Nearest rank is the wrong comparison twice over. A 93% placement
                // sits at rank 1,443, which is 1,003 from the 95% chip and 658 from the
                // 90% chip — so nearest rank lands on 90%, fine. But the gaps are not
                // comparable: 7,627 is 6,184 away from 1,443, which is a different
                // kind of distance from 1,239 to 204. Comparing ranks across a
                // five-fold range invites snapping a beginner to 98%.
                //
                // Rounding down in coverage guarantees the label on screen is one the
                // learner has actually reached. Under-shooting costs them a level they
                // can choose from the chips; over-shooting shows them text they cannot
                // read, which is the failure this app exists to avoid.
                reader.LEVELS.filter { candidate ->
                    (ranks[candidate] ?: Int.MAX_VALUE) <= rank
                }.maxOrNull() ?: reader.LEVELS.first()
            } ?: reader.LEVELS.last()
            _state.value = _state.value.copy(ranks = ranks, level = level)
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