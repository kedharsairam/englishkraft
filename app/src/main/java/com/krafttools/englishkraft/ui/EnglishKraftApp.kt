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
                    val current = history.lastOrNull()
                    if (current == null) {
                        SearchScreen(
                            query = search.query,
                            suggestions = search.suggestions,
                            didYouMean = search.didYouMean,
                            onQueryChange = viewModel::onQueryChange,
                            onSubmit = ::open,
                            onSuggestionClick = ::open,
                        )
                    } else {
                        EntryScreen(
                            lookup = current,
                            beginnerSafe = false,
                            starred = false,
                            onWordClick = ::open,
                            onToggleStar = { },
                            onSpeak = { text -> tts.speak(text) },
                        )
                    }
                }
            }
        }
    }
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