package com.krafttools.englishkraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.krafttools.englishkraft.data.AppViewModel
import com.krafttools.englishkraft.ui.EnglishKraftApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AppViewModel = viewModel()
            val state by vm.appState.collectAsStateWithLifecycle()
            EnglishKraftApp(state = state, viewModel = vm)
        }
    }
}
