package com.loki.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase { OFF, LOADING, WAKE_LISTENING, COMMAND_LISTENING, THINKING, SPEAKING, PAUSED, ERROR }

/** Process-wide state shared between the service and the UI. */
object LokiState {
    private val _phase = MutableStateFlow(Phase.OFF)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    @Volatile var appVisible = false

    fun setPhase(p: Phase) { _phase.value = p }
    fun log(line: String) { _log.update { (it + line).takeLast(40) } }
}
