package com.krafttools.englishkraft.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AppState {
    data object Loading : AppState
    data class Installing(val state: DictionaryInstaller.State) : AppState
    data class Ready(
        val repository: DictionaryRepository,
        val entries: Int,
        val senses: Int,
    ) : AppState
    data class Failed(val reason: String) : AppState
}

data class SearchUiState(
    val query: String = "",
    val suggestions: List<Suggestion> = emptyList(),
    val didYouMean: List<Suggestion> = emptyList(),
)

/**
 * Holds the open dictionary and the current query.
 *
 * The database is opened once and kept. 427 MB is far too large to reopen per
 * query, and SQLite's own page cache is left to do its job rather than being
 * second-guessed with a bespoke LRU.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val _appState = MutableStateFlow<AppState>(AppState.Loading)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private var db: SQLiteDatabase? = null
    private var suggestJob: Job? = null

    init {
        viewModelScope.launch {
            val installer = DictionaryInstaller(getApplication())
            val result = installer.install { copied, total ->
                _appState.value = AppState.Installing(
                    DictionaryInstaller.State.Copying(copied, total)
                )
            }
            when (result) {
                is DictionaryInstaller.State.Ready -> {
                    val opened = withContext(Dispatchers.IO) {
                        runCatching { openDictionary(result.file) }
                    }
                    opened.fold(
                        onSuccess = {
                            db = it
                            _appState.value = AppState.Ready(
                                DictionaryRepository(it), result.entries, result.senses
                            )
                        },
                        onFailure = {
                            _appState.value = AppState.Failed(
                                it.message ?: "could not open the database"
                            )
                        },
                    )
                }
                is DictionaryInstaller.State.Failed ->
                    _appState.value = AppState.Failed(result.reason)
                else -> _appState.value = AppState.Loading
            }
        }
    }

    fun onQueryChange(raw: String) {
        _search.value = _search.value.copy(query = raw, didYouMean = emptyList())
        suggestJob?.cancel()

        val repo = repository() ?: return
        if (raw.trim().length < 2) {
            _search.value = _search.value.copy(suggestions = emptyList())
            return
        }

        // Debounced. Typing six characters fires six LIKE range scans over
        // 1.4M rows; nobody needs every intermediate answer.
        suggestJob = viewModelScope.launch {
            delay(90)
            val found = withContext(Dispatchers.IO) { repo.suggest(raw) }
            val typos = if (found.isEmpty()) {
                withContext(Dispatchers.IO) { repo.didYouMean(raw) }
            } else {
                emptyList()
            }
            _search.value = _search.value.copy(suggestions = found, didYouMean = typos)
        }
    }

    fun lookup(word: String): Lookup? {
        val repo = repository() ?: return null
        return repo.lookup(word)
    }

    fun clearQuery() {
        suggestJob?.cancel()
        _search.value = SearchUiState()
    }

    private fun repository(): DictionaryRepository? =
        (_appState.value as? AppState.Ready)?.repository

    override fun onCleared() {
        db?.close()
        super.onCleared()
    }
}