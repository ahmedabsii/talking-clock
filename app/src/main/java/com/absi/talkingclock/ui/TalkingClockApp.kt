package com.absi.talkingclock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

enum class Screen { HOME, SETTINGS, SETUP, HISTORY }

@Composable
fun TalkingClockApp(
    requestedScreen: Screen?,
    onRequestHandled: () -> Unit,
    viewModel: MainViewModel = viewModel(),
) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    LaunchedEffect(requestedScreen) {
        if (requestedScreen != null) {
            screen = requestedScreen
            onRequestHandled()
        }
    }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    // The user may have just changed a system setting (permissions, battery) and come back.
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    // The first settings read takes a few milliseconds; drawing defaults first would flash.
    if (state.loaded) {
        val back = { screen = Screen.HOME }
        when (screen) {
            Screen.HOME -> HomeScreen(state, viewModel, onOpen = { screen = it })
            Screen.SETTINGS -> SettingsScreen(state, viewModel, onBack = back)
            Screen.SETUP -> SetupScreen(state, viewModel, onBack = back)
            Screen.HISTORY -> HistoryScreen(state, onBack = back)
        }
    }
}
