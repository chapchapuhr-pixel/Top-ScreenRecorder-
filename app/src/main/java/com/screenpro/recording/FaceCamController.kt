package com.screenpro.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FaceCamController
 * Global state manager for FaceCam preview, visibility, hiding during explanations,
 * and camera lens toggling across UI, background services, and recording.
 */
object FaceCamController {

    private val _isFaceCamEnabled = MutableStateFlow(false)
    val isFaceCamEnabled: StateFlow<Boolean> = _isFaceCamEnabled.asStateFlow()

    // Camera mode: "off", "facecam", "rear", "dual", "dual_only"
    private val _cameraMode = MutableStateFlow("off")
    val cameraMode: StateFlow<String> = _cameraMode.asStateFlow()

    // Dual layout: "pip", "split_horizontal", "split_vertical", "dual_bubbles"
    private val _dualLayout = MutableStateFlow("pip")
    val dualLayout: StateFlow<String> = _dualLayout.asStateFlow()

    // Hardware dual camera capability detection result
    private val _isDualCameraSupported = MutableStateFlow(false)
    val isDualCameraSupported: StateFlow<Boolean> = _isDualCameraSupported.asStateFlow()

    // True when the user temporarily collapses/hides the facecam during an explanation
    private val _isFaceCamHidden = MutableStateFlow(false)
    val isFaceCamHidden: StateFlow<Boolean> = _isFaceCamHidden.asStateFlow()

    // Camera lens selection: true = front camera, false = back camera
    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    fun setCameraMode(mode: String) {
        _cameraMode.value = mode
        val enabled = (mode != "off")
        _isFaceCamEnabled.value = enabled
        if (enabled) {
            _isFaceCamHidden.value = false
            if (mode == "rear") {
                _isFrontCamera.value = false
            } else if (mode == "facecam") {
                _isFrontCamera.value = true
            }
        }
    }

    fun setDualLayout(layout: String) {
        _dualLayout.value = layout
    }

    fun setDualCameraSupported(supported: Boolean) {
        _isDualCameraSupported.value = supported
    }

    fun setFaceCamEnabled(enabled: Boolean) {
        _isFaceCamEnabled.value = enabled
        if (enabled) {
            _isFaceCamHidden.value = false
            if (_cameraMode.value == "off") {
                _cameraMode.value = if (_isFrontCamera.value) "facecam" else "rear"
            }
        } else {
            _cameraMode.value = "off"
        }
    }

    fun toggleFaceCam() {
        val next = !_isFaceCamEnabled.value
        _isFaceCamEnabled.value = next
        if (next) {
            _isFaceCamHidden.value = false
        }
    }

    fun hideFaceCam() {
        _isFaceCamHidden.value = true
    }

    fun showFaceCam() {
        _isFaceCamHidden.value = false
    }

    fun toggleHideShow() {
        _isFaceCamHidden.value = !_isFaceCamHidden.value
    }

    fun switchCameraLens() {
        _isFrontCamera.value = !_isFrontCamera.value
    }

    fun setCameraLens(useFront: Boolean) {
        _isFrontCamera.value = useFront
    }
}
