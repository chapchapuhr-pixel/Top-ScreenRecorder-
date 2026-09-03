package com.screenpro.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object RecordingController {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var timerJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun onRecordingStarted() {
        _isRecording.value = true
        _isPaused.value = false
        _elapsedSeconds.value = 0L
        _lastError.value = null

        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (!_isPaused.value && _isRecording.value) {
                    _elapsedSeconds.value += 1
                }
            }
        }
    }

    fun onRecordingPaused() {
        _isPaused.value = true
    }

    fun onRecordingResumed() {
        _isPaused.value = false
    }

    fun onRecordingStopped() {
        _isRecording.value = false
        _isPaused.value = false
        timerJob?.cancel()
        timerJob = null
    }

    fun setError(error: String?) {
        _lastError.value = error
    }
}
