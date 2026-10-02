package com.krafttools.englishkraft.ui

import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krafttools.englishkraft.data.AppState
import com.krafttools.englishkraft.data.AppViewModel
import com.krafttools.englishkraft.data.Lookup
import com.krafttools.englishkraft.data.PlacementRepository
import com.krafttools.englishkraft.data.PlacementUiState
import com.krafttools.englishkraft.data.PlacementViewModel
import com.krafttools.englishkraft.data.Progress
import com.krafttools.englishkraft.data.ReaderViewModel
import com.krafttools.englishkraft.data.ReviewState
import com.krafttools.englishkraft.data.Wordbook
import com.krafttools.englishkraft.data.WordbookViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The whole app: install, search, entry.
 *
 * Navigation is a plain list of visited entries rather than a NavHost. There is
 * exactly one destination and the back stack holds only words the user actually
 * tapped through, so a routing library would be machinery for a stack that holds
 * no URLs and no arguments worth serialising.
 *
 * Popping restores the previous entry instead of dropping to an empty search box,
 * which is what makes tapping through a definition feel like reading.
 */
@Composable
fun EnglishKraftApp(state: AppState, viewModel: AppViewModel) {
    EnglishKraftTheme {
        var history by remember { mutableStateOf<List<Lookup>>(emptyList()) }
        // Three destinations and no URL arguments worth serialising, so this is a
        // sealed value rather than a NavHost.
        var destination by remember { mutableStateOf(Destination.Dictionary) }
        var readerVm by remember { mutableStateOf<ReaderViewModel?>(null) }
        var reviewState by remember { mutableStateOf<ReviewState?>(null) }
        // Placement is a third destination. The saved level feeds the reader, which is
        // the whole reason to take it: without it the reader assumes 98%, which is
        // wrong for a beginner.
        var placementState by remember { mutableStateOf<PlacementUiState?>(null) }
        var placementVm by remember { mutableStateOf<PlacementViewModel?>(null) }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val search by viewModel.search.collectAsStateWithLifecycle()
        val tts = remember { Speaker(context) }

        // One TTS engine for the screen, shut down when it leaves. Constructing an
        // engine per tap would start a synthesis service on every press.
        DisposableEffect(tts) {
            onDispose { tts.shutdown() }
        }

        fun open(word: String) {
            scope.launch {
                val found = withContext(Dispatchers.IO) { viewModel.lookup(word) }
                if (found != null) history = history + found
            }
        }

        BackHandler(enabled = history.isNotEmpty()) { history = history.dropLast(1) }
        // The reader's own BackHandler above fires first when it is open, so this one
        // only ever sees the dictionary.

        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            when (state) {
                AppState.Loading -> Text(
                    text = "Opening…",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                )

                is AppState.Installing -> InstallScreen(state.state)

                is AppState.Failed -> Text(
                    text = "The dictionary could not be opened.\n\n${state.reason}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .testTag("fatal_error"),
                )

                is AppState.Ready -> {
                    val placementHolder = placementVm
                    val progress = Progress.get(LocalContext.current)
                    // One wordbook for the whole app, created once the corpus is open.
                    // It owns `progress.db`, and there is only one wordbook.
                    val wordbookVm = remember(state.repository) {
                        WordbookViewModel(context, state.repository)
                    }
                    // The wordbook's contents live in state, not in the ViewModel's
                    // database handle. Reading membership straight from SQLite on every
                    // recomposition looked simpler and was wrong: the bookmark on the
                    // entry screen never changed after a word was saved, because
                    // nothing Compose was tracking had changed. Membership is now
                    // derived from this list, so saving a word updates the icon.
                    var wordbookCards by remember { mutableStateOf(emptyList<Wordbook.Card>()) }
                    var wordbookDue by remember { mutableStateOf(0) }
                    var wordbookAnswers by remember { mutableStateOf(0) }

                    fun refreshWordbook() {
                        scope.launch {
                            val snapshot = withContext(Dispatchers.IO) {
                                Triple(wordbookVm.all(), wordbookVm.dueCount(), wordbookVm.reviewCount())
                            }
                            wordbookCards = snapshot.first
                            wordbookDue = snapshot.second
                            wordbookAnswers = snapshot.third
                        }
                    }

                    // Loaded once the corpus is open, so the search screen's wordbook
                    // row and every bookmark show the truth on the first frame rather
                    // than after the learner has opened the wordbook once.
                    LaunchedEffect(state.repository) { refreshWordbook() }

                    if (placementHolder != null) {
                        BackHandler { placementVm = null; placementState = null }
                        when (val ps = placementState) {
                            is PlacementUiState.Asking -> PlacementScreen(
                                question = ps.question,
                                index = ps.index,
                                total = ps.total,
                                chosen = ps.chosen,
                                onAnswer = { picked ->
                                    // Answering and advancing happen off the main
                                    // thread: building the next question reads the
                                    // corpus, and 15 questions of a janky spinner is
                                    // worse than one slow first question.
                                    scope.launch {
                                        val nextState = withContext(Dispatchers.IO) {
                                            if (ps.chosen == null) {
                                                placementHolder.answer(picked)
                                            } else {
                                                placementHolder.advance()
                                            }
                                        }
                                        placementState = nextState
                                    }
                                },
                                onFinish = {
                                    scope.launch {
                                        placementState = withContext(Dispatchers.IO) {
                                            placementHolder.advance()
                                        }
                                    }
                                },
                            )
                            is PlacementUiState.Done -> PlacementResult(
                                result = ps.result,
                                onRead = {
                                    readerVm = ReaderViewModel(
                                        state.repository,
                                        progress.vocabularyRank,
                                    )
                                    placementVm = null
                                    placementState = null
                                },
                                onRetake = {
                                    placementHolder.reset()
                                    scope.launch {
                                        placementState = withContext(Dispatchers.IO) {
                                            placementHolder.next()
                                        }
                                    }
                                },
                            )
                            else -> Unit
                        }
                    } else {
                    val readerState = readerVm?.state?.collectAsState()
                    val readerViewModel = readerVm
                    when (destination) {
                        Destination.Wordbook -> {
                            BackHandler { destination = Destination.Dictionary }
                            if (wordbookCards.isEmpty()) {
                                NothingSavedYet(
                                    onBack = { destination = Destination.Dictionary },
                                )
                            } else {
                                WordbookScreen(
                                    cards = wordbookCards,
                                    answerCount = wordbookAnswers,
                                    now = System.currentTimeMillis(),
                                    onReview = {
                                        scope.launch {
                                            reviewState = withContext(Dispatchers.IO) { wordbookVm.start() }
                                            destination = Destination.Review
                                        }
                                    },
                                    onOpenWord = { headword, pos ->
                                        scope.launch {
                                            val found = withContext(Dispatchers.IO) {
                                                viewModel.lookup("$headword/$pos")
                                            }
                                            if (found != null) {
                                                history = listOf(found)
                                                destination = Destination.Dictionary
                                            }
                                        }
                                    },
                                    onRemove = { headword, pos ->
                                        scope.launch {
                                            withContext(Dispatchers.IO) { wordbookVm.remove(headword, pos) }
                                            refreshWordbook()
                                        }
                                    },
                                    onForgetAll = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { wordbookVm.clear() }
                                            refreshWordbook()
                                            destination = Destination.Dictionary
                                        }
                                    },
                                    onBack = { destination = Destination.Dictionary },
                                )
                            }
                        }

                        Destination.Review -> {
                            BackHandler { destination = Destination.Wordbook; refreshWordbook() }
                            val rs = reviewState
                            if (rs == null || (rs.done && rs.answered == 0)) {
                                NothingSavedYet(
                                    onBack = { destination = Destination.Wordbook },
                                )
                            } else if (rs.done) {
                                ReviewDone(
                                    state = rs,
                                    onDone = {
                                        destination = Destination.Wordbook
                                        refreshWordbook()
                                    },
                                )
                            } else {
                                ReviewScreen(
                                    state = rs,
                                    onReveal = { reviewState = wordbookVm.reveal() },
                                    onAnswer = { grade ->
                                        // Every answer is a write to progress.db and a
                                        // read of the next card, so it cannot run on the
                                        // main thread without a dropped frame on each tap.
                                        scope.launch {
                                            reviewState = withContext(Dispatchers.IO) {
                                                wordbookVm.answer(grade)
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        Destination.Dictionary -> if (readerViewModel != null) {
                            LaunchedEffect(Unit) { readerViewModel.start() }
                            BackHandler { readerVm = null }
                            readerState?.value?.let { rs ->
                                ReaderScreen(
                                    level = rs.level.toFloat(),
                                    levels = listOf(0.80f, 0.90f, 0.95f, 0.98f),
                                    passages = rs.passage?.sentences.orEmpty(),
                                    unknown = rs.passage?.unknown.orEmpty(),
                                    knownRank = rs.knownRank ?: 0,
                                    loading = rs.loading,
                                    onLevelChange = {
                                    // Choosing a level by hand is how the placement result
                                    // is overridden, and it is remembered: a learner who
                                    // says "that is too hard" means it.
                                    readerViewModel.setLevel(it.toDouble())
                                },
                                    onNewPassage = readerViewModel::another,
                                    onWordClick = ::open,
                                )
                            }
                        } else if (history.isEmpty()) {
                        SearchScreen(
                            query = search.query,
                            suggestions = search.suggestions,
                            didYouMean = search.didYouMean,
                            onQueryChange = viewModel::onQueryChange,
                            onSubmit = ::open,
                            onSuggestionClick = ::open,
                            onOpenReader = {
                                readerVm = ReaderViewModel(state.repository, progress.vocabularyRank)
                            },
                            onStartPlacement = {
                                placementVm = PlacementViewModel(
                                    PlacementRepository(state.database),
                                    progress,
                                ).also { vm ->
                                    scope.launch {
                                        placementState = withContext(Dispatchers.IO) { vm.next() }
                                    }
                                }
                            },
                            onOpenWordbook = {
                                refreshWordbook()
                                destination = Destination.Wordbook
                            },
                            placementTaken = progress.takenAt > 0L,
                            savedCount = wordbookCards.size,
                            dueCount = wordbookDue,
                        )
                    } else {
                        val entry = history.last()
                        // Derived from the wordbook state rather than queried, so that
                        // saving a word flips the bookmark. `isSaved()` on the ViewModel
                        // is a plain database read and Compose cannot see it change.
                        val saved = wordbookCards.any {
                            it.headword == entry.entry.headword && it.pos == entry.entry.pos
                        }
                        EntryScreen(
                            lookup = entry,
                            beginnerSafe = false,
                            starred = saved,
                            onWordClick = ::open,
                            onToggleStar = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        // Saving reads the sense the learner was
                                        // actually looking at, which is not always the
                                        // first one: a word can be reached through an
                                        // inflected form, and the entry it resolves to
                                        // may open on a different sense.
                                        val (gloss, usage) = wordbookVm.senseToSave(entry)
                                        wordbookVm.toggle(
                                            entry.entry.headword,
                                            entry.entry.pos,
                                            gloss,
                                            usage,
                                        )
                                    }
                                    refreshWordbook()
                                }
                            },
                            onSpeak = { text -> tts.speak(text) },
                        )
                    }
                }
            }
        }
    }
}
}
}
 /**
 * The app's three screens.
 *
 * A sealed value rather than a routing library: there are three of them, none takes a
 * URL, and the only state worth surviving a rotation is which word is open, which is
 * already held here.
 */
private enum class Destination {
    /** Search when nothing is open, and the entry screen when something is. */
    Dictionary,
    Wordbook,
    Review,
}

/**
 * Text to speech on the device.
 *
 * On-device only: the engine is part of the phone, so this costs no network
 * permission, no audio files and no downloaded model — which is why there is no
 * pronunciation audio in the app despite 429,000 IPA transcriptions in the data.
 *
 * Failure is silent by design. A device with no installed voice must still be able
 * to read a definition; refusing to show the entry because it cannot be spoken
 * would be a worse failure than no sound.
 */
private class Speaker(context: android.content.Context) {
    private var engine: TextToSpeech? = null
    private var ready = false

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                engine?.language = Locale.US
            }
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        val mode = TextToSpeech.QUEUE_FLUSH
        engine?.speak(text, mode, null, "englishkraft")
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }
}