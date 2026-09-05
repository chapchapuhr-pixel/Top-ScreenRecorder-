package com.screenpro.recording

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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

    private val _latestRecordedSegmentFile = MutableStateFlow<File?>(null)
    val latestRecordedSegmentFile: StateFlow<File?> = _latestRecordedSegmentFile.asStateFlow()

    private val _latestRecordedSegmentUri = MutableStateFlow<Uri?>(null)
    val latestRecordedSegmentUri: StateFlow<Uri?> = _latestRecordedSegmentUri.asStateFlow()

    private val _isFloatingPreviewVisible = MutableStateFlow(false)
    val isFloatingPreviewVisible: StateFlow<Boolean> = _isFloatingPreviewVisible.asStateFlow()

    fun onRecordingStarted() {
        _isRecording.value = true
        _isPaused.value = false
        _elapsedSeconds.value = 0L
        _lastError.value = null
        _latestRecordedSegmentFile.value = null
        _latestRecordedSegmentUri.value = null
        _isFloatingPreviewVisible.value = false

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
        _isFloatingPreviewVisible.value = false
    }

    fun onSegmentReady(file: File, uri: Uri) {
        _latestRecordedSegmentFile.value = file
        _latestRecordedSegmentUri.value = uri
        _isFloatingPreviewVisible.value = true
        _isPaused.value = true
    }

    fun showFloatingPreview() {
        if (_latestRecordedSegmentFile.value != null || _latestRecordedSegmentUri.value != null) {
            _isFloatingPreviewVisible.value = true
        }
    }

    fun hideFloatingPreview() {
        _isFloatingPreviewVisible.value = false
    }

    fun clearSegmentPreview() {
        _latestRecordedSegmentFile.value = null
        _latestRecordedSegmentUri.value = null
        _isFloatingPreviewVisible.value = false
    }

    fun onRecordingStopped() {
        _isRecording.value = false
        _isPaused.value = false
        _isFloatingPreviewVisible.value = false
        _latestRecordedSegmentFile.value = null
        _latestRecordedSegmentUri.value = null
        timerJob?.cancel()
        timerJob = null
    }

    fun setError(error: String?) {
        _lastError.value = error
    }
}

