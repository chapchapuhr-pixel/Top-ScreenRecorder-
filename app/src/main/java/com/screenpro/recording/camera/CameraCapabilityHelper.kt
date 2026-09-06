package com.screenpro.recording.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

/**
 * CameraCapabilityHelper
 * Queries device hardware and Android Camera2 API to detect:
 * 1. Front and rear camera availability
 * 2. Concurrent camera streaming support (Android 11+ / API 30+)
 * 3. Graceful fallback explanations when concurrent streaming is not supported
 */
object CameraCapabilityHelper {

    private const val TAG = "CameraCapabilityHelper"

    data class CameraDeviceInfo(
        val cameraId: String,
        val lensFacing: Int, // CameraCharacteristics.LENS_FACING_FRONT or LENS_FACING_BACK
        val isFront: Boolean,
        val maxResolution: String = ""
    )

    data class DualCameraSupportInfo(
        val isSupported: Boolean,
        val frontCameraId: String?,
        val rearCameraId: String?,
        val availableCameras: List<CameraDeviceInfo>,
        val statusMessage: String,
        val isApiSupported: Boolean
    )

    /**
     * Checks if the device supports simultaneous front + rear camera capture.
     * Uses CameraManager.getConcurrentCameraIds() on Android 11+ (API 30+).
     */
    fun checkDualCameraSupport(context: Context): DualCameraSupportInfo {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return DualCameraSupportInfo(
                isSupported = false,
                frontCameraId = null,
                rearCameraId = null,
                availableCameras = emptyList(),
                statusMessage = "Camera service is unavailable on this device",
                isApiSupported = false
            )

        val cameraList = mutableListOf<CameraDeviceInfo>()
        var frontId: String? = null
        var rearId: String? = null

        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                val isFront = (facing == CameraCharacteristics.LENS_FACING_FRONT)
                val isRear = (facing == CameraCharacteristics.LENS_FACING_BACK)

                if (isFront && frontId == null) {
                    frontId = id
                }
                if (isRear && rearId == null) {
                    rearId = id
                }

                cameraList.add(
                    CameraDeviceInfo(
                        cameraId = id,
                        lensFacing = facing,
                        isFront = isFront
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating cameras", e)
            return DualCameraSupportInfo(
                isSupported = false,
                frontCameraId = null,
                rearCameraId = null,
                availableCameras = emptyList(),
                statusMessage = "Error querying camera characteristics: ${e.message}",
                isApiSupported = false
            )
        }

        if (frontId == null || rearId == null) {
            return DualCameraSupportInfo(
                isSupported = false,
                frontCameraId = frontId,
                rearCameraId = rearId,
                availableCameras = cameraList,
                statusMessage = if (frontId == null && rearId == null) "No cameras detected on this device"
                else if (frontId == null) "Front camera not detected; single rear camera mode only"
                else "Rear camera not detected; single front camera mode only",
                isApiSupported = false
            )
        }

        // On Android 11+ (API 30+), query CameraManager.concurrentCameraIds
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val concurrentSets = cameraManager.concurrentCameraIds
                Log.d(TAG, "Concurrent camera sets found: ${concurrentSets.size}")

                for (set in concurrentSets) {
                    var hasFront = false
                    var hasRear = false
                    var setFrontId: String? = null
                    var setRearId: String? = null

                    for (id in set) {
                        val chars = cameraManager.getCameraCharacteristics(id)
                        val facing = chars.get(CameraCharacteristics.LENS_FACING)
                        if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                            hasFront = true
                            setFrontId = id
                        } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                            hasRear = true
                            setRearId = id
                        }
                    }

                    if (hasFront && hasRear) {
                        return DualCameraSupportInfo(
                            isSupported = true,
                            frontCameraId = setFrontId ?: frontId,
                            rearCameraId = setRearId ?: rearId,
                            availableCameras = cameraList,
                            statusMessage = "Dual camera hardware supported",
                            isApiSupported = true
                        )
                    }
                }

                // If concurrentSets is empty or does not contain a front+rear pair
                return DualCameraSupportInfo(
                    isSupported = false,
                    frontCameraId = frontId,
                    rearCameraId = rearId,
                    availableCameras = cameraList,
                    statusMessage = "Dual Camera isn't supported on this device.",
                    isApiSupported = true
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query concurrent camera ids", e)
                return DualCameraSupportInfo(
                    isSupported = false,
                    frontCameraId = frontId,
                    rearCameraId = rearId,
                    availableCameras = cameraList,
                    statusMessage = "Dual Camera isn't supported on this device.",
                    isApiSupported = true
                )
            }
        } else {
            // Android 10 or below
            return DualCameraSupportInfo(
                isSupported = false,
                frontCameraId = frontId,
                rearCameraId = rearId,
                availableCameras = cameraList,
                statusMessage = "Dual Camera isn't supported on this device.",
                isApiSupported = false
            )
        }
    }
}
