package com.screenpro.recording.compositor

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ScreenCameraCompositor
 * Hardware-accelerated OpenGL ES compositor merging the MediaProjection virtual display
 * and camera stream(s) (Front FaceCam, Rear Camera, or Concurrent Dual Cameras)
 * into a single MediaRecorder input surface.
 *
 * Supported Presentation Modes:
 * - Screen + FaceCam
 * - Screen + Rear Camera + FaceCam (Dual floating overlays)
 * - Rear Camera + FaceCam (Dual Camera mode without screen)
 * - Split Screen (Horizontal top/bottom or Vertical left/right)
 * - Picture-in-Picture (PiP)
 */
class ScreenCameraCompositor(
    private val outputSurface: Surface,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val targetFps: Int = 60
) {
    private val tag = "ScreenCameraCompositor"

    private val isRunning = AtomicBoolean(false)
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private var eglCore: EglCore? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var glProgram: FaceCamGlProgram? = null

    private var screenTexId = 0
    private var camera1TexId = 0
    private var camera2TexId = 0

    private var screenSurfaceTexture: SurfaceTexture? = null
    private var camera1SurfaceTexture: SurfaceTexture? = null
    private var camera2SurfaceTexture: SurfaceTexture? = null

    var screenSurface: Surface? = null
        private set
    var camera1Surface: Surface? = null
        private set
    var camera2Surface: Surface? = null
        private set

    // Backward compatibility alias for single-camera callers
    val cameraSurface: Surface?
        get() = camera1Surface

    private val screenTexMatrix = FloatArray(16)
    private val camera1TexMatrix = FloatArray(16)
    private val camera2TexMatrix = FloatArray(16)

    // Configuration
    @Volatile var cameraMode: String = "facecam" // "off", "facecam", "rear", "dual", "dual_only"
    @Volatile var dualLayout: String = "pip" // "pip", "split_horizontal", "split_vertical", "dual_bubbles"

    // Primary Camera (e.g. Front FaceCam or Rear Camera)
    @Volatile var camera1Enabled: Boolean = true
    @Volatile var camera1Shape: String = "circle" // "circle", "rounded-square", "rectangle"
    @Volatile var camera1PositionX: Float = 0.75f // 0.0 (left) to 1.0 (right)
    @Volatile var camera1PositionY: Float = 0.08f // 0.0 (top) to 1.0 (bottom)
    @Volatile var camera1Scale: Float = 0.26f // Fraction of screen width
    @Volatile var camera1BorderWidthDp: Int = 3
    @Volatile var camera1BorderColorHex: String = "#FF4B2B"
    @Volatile var camera1Mirrored: Boolean = true

    // Secondary Camera (e.g. Rear camera in Dual Mode or secondary bubble)
    @Volatile var camera2Enabled: Boolean = false
    @Volatile var camera2Shape: String = "circle"
    @Volatile var camera2PositionX: Float = 0.08f
    @Volatile var camera2PositionY: Float = 0.08f
    @Volatile var camera2Scale: Float = 0.22f
    @Volatile var camera2BorderWidthDp: Int = 3
    @Volatile var camera2BorderColorHex: String = "#00E5FF"
    @Volatile var camera2Mirrored: Boolean = false

    // Backward compatibility delegates
    var cameraEnabled: Boolean
        get() = camera1Enabled
        set(value) { camera1Enabled = value }
    var cameraShape: String
        get() = camera1Shape
        set(value) { camera1Shape = value }
    var cameraPositionX: Float
        get() = camera1PositionX
        set(value) { camera1PositionX = value }
    var cameraPositionY: Float
        get() = camera1PositionY
        set(value) { camera1PositionY = value }
    var cameraScale: Float
        get() = camera1Scale
        set(value) { camera1Scale = value }
    var cameraBorderWidthDp: Int
        get() = camera1BorderWidthDp
        set(value) { camera1BorderWidthDp = value }
    var cameraBorderColorHex: String
        get() = camera1BorderColorHex
        set(value) { camera1BorderColorHex = value }
    var cameraMirrored: Boolean
        get() = camera1Mirrored
        set(value) { camera1Mirrored = value }

    fun start() {
        if (isRunning.getAndSet(true)) return

        val latch = CountDownLatch(1)
        val thread = HandlerThread("CompositorRenderThread").apply { start() }
        renderThread = thread
        val handler = Handler(thread.looper)
        renderHandler = handler

        handler.post {
            try {
                initGL()
                latch.countDown()
            } catch (e: Exception) {
                Log.e(tag, "Failed to initialize OpenGL Compositor", e)
                latch.countDown()
            }
        }

        try {
            latch.await(3, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {}

        scheduleNextFrame()
    }

    private fun initGL() {
        eglCore = EglCore().also { core ->
            eglSurface = core.createWindowSurface(outputSurface)
            core.makeCurrent(eglSurface)
        }

        glProgram = FaceCamGlProgram()

        // Create textures for Screen, Primary Camera, and Secondary Camera
        screenTexId = FaceCamGlProgram.createTextureObject()
        camera1TexId = FaceCamGlProgram.createTextureObject()
        camera2TexId = FaceCamGlProgram.createTextureObject()

        // Setup Screen SurfaceTexture
        screenSurfaceTexture = SurfaceTexture(screenTexId).apply {
            setDefaultBufferSize(videoWidth, videoHeight)
        }
        screenSurface = Surface(screenSurfaceTexture)

        // Setup Primary Camera SurfaceTexture (Front/FaceCam)
        camera1SurfaceTexture = SurfaceTexture(camera1TexId).apply {
            setDefaultBufferSize(1280, 720)
        }
        camera1Surface = Surface(camera1SurfaceTexture)

        // Setup Secondary Camera SurfaceTexture (Rear Camera)
        camera2SurfaceTexture = SurfaceTexture(camera2TexId).apply {
            setDefaultBufferSize(1280, 720)
        }
        camera2Surface = Surface(camera2SurfaceTexture)

        GLES20.glViewport(0, 0, videoWidth, videoHeight)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        Log.d(tag, "Compositor OpenGL initialized ($videoWidth x $videoHeight @ ${targetFps}fps) with dual camera support")
    }

    private fun scheduleNextFrame() {
        val handler = renderHandler ?: return
        if (!isRunning.get()) return

        val intervalMs = (1000L / targetFps.coerceIn(15, 60)).coerceAtLeast(16L)
        handler.postDelayed({
            if (isRunning.get()) {
                drawFrame()
                scheduleNextFrame()
            }
        }, intervalMs)
    }

    private fun drawFrame() {
        val core = eglCore ?: return
        val prog = glProgram ?: return
        if (eglSurface == EGL14.EGL_NO_SURFACE) return

        try {
            core.makeCurrent(eglSurface)

            val isDualOnly = (cameraMode == "dual_only")

            // Update Screen Texture if screen capture is active
            if (!isDualOnly) {
                try {
                    screenSurfaceTexture?.updateTexImage()
                    screenSurfaceTexture?.getTransformMatrix(screenTexMatrix)
                } catch (_: Exception) {}
            }

            // Update Primary Camera Texture
            if (camera1Enabled) {
                try {
                    camera1SurfaceTexture?.updateTexImage()
                    camera1SurfaceTexture?.getTransformMatrix(camera1TexMatrix)
                } catch (_: Exception) {}
            }

            // Update Secondary Camera Texture
            if (camera2Enabled) {
                try {
                    camera2SurfaceTexture?.updateTexImage()
                    camera2SurfaceTexture?.getTransformMatrix(camera2TexMatrix)
                } catch (_: Exception) {}
            }

            GLES20.glViewport(0, 0, videoWidth, videoHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            when (cameraMode) {
                "off" -> {
                    // Screen only
                    prog.drawScreen(screenTexId, screenTexMatrix)
                }
                "facecam", "rear" -> {
                    // Screen + Single Camera
                    prog.drawScreen(screenTexId, screenTexMatrix)
                    if (camera1Enabled) {
                        drawCameraOverlay(
                            prog = prog,
                            textureId = camera1TexId,
                            texMatrix = camera1TexMatrix,
                            shape = camera1Shape,
                            posX = camera1PositionX,
                            posY = camera1PositionY,
                            scale = camera1Scale,
                            borderWidthDp = camera1BorderWidthDp,
                            borderColorHex = camera1BorderColorHex,
                            isMirrored = camera1Mirrored
                        )
                    }
                }
                "dual" -> {
                    // Screen + Rear Camera + FaceCam
                    prog.drawScreen(screenTexId, screenTexMatrix)

                    // Draw Camera 1 (e.g. FaceCam)
                    if (camera1Enabled) {
                        drawCameraOverlay(
                            prog = prog,
                            textureId = camera1TexId,
                            texMatrix = camera1TexMatrix,
                            shape = camera1Shape,
                            posX = camera1PositionX,
                            posY = camera1PositionY,
                            scale = camera1Scale,
                            borderWidthDp = camera1BorderWidthDp,
                            borderColorHex = camera1BorderColorHex,
                            isMirrored = camera1Mirrored
                        )
                    }

                    // Draw Camera 2 (e.g. Rear Camera overlay)
                    if (camera2Enabled) {
                        drawCameraOverlay(
                            prog = prog,
                            textureId = camera2TexId,
                            texMatrix = camera2TexMatrix,
                            shape = camera2Shape,
                            posX = camera2PositionX,
                            posY = camera2PositionY,
                            scale = camera2Scale,
                            borderWidthDp = camera2BorderWidthDp,
                            borderColorHex = camera2BorderColorHex,
                            isMirrored = camera2Mirrored
                        )
                    }
                }
                "dual_only" -> {
                    // Rear Camera + FaceCam (No screen recording)
                    when (dualLayout) {
                        "split_horizontal" -> {
                            // Top Half: Camera 1 (or Rear), Bottom Half: Camera 2 (or Front)
                            val topMvp = FloatArray(16).apply {
                                Matrix.setIdentityM(this, 0)
                                Matrix.translateM(this, 0, 0f, 0.5f, 0f)
                                Matrix.scaleM(this, 0, 1.0f, 0.5f, 1.0f)
                            }
                            val bottomMvp = FloatArray(16).apply {
                                Matrix.setIdentityM(this, 0)
                                Matrix.translateM(this, 0, 0f, -0.5f, 0f)
                                Matrix.scaleM(this, 0, 1.0f, 0.5f, 1.0f)
                            }
                            val dummyBorder = floatArrayOf(0f, 0f, 0f, 0f)
                            prog.drawFaceCam(camera1TexId, camera1TexMatrix, topMvp, 2, dummyBorder, 0f, 0f, camera1Mirrored)
                            prog.drawFaceCam(camera2TexId, camera2TexMatrix, bottomMvp, 2, dummyBorder, 0f, 0f, camera2Mirrored)
                        }
                        "split_vertical" -> {
                            // Left Half: Camera 1, Right Half: Camera 2
                            val leftMvp = FloatArray(16).apply {
                                Matrix.setIdentityM(this, 0)
                                Matrix.translateM(this, 0, -0.5f, 0f, 0f)
                                Matrix.scaleM(this, 0, 0.5f, 1.0f, 1.0f)
                            }
                            val rightMvp = FloatArray(16).apply {
                                Matrix.setIdentityM(this, 0)
                                Matrix.translateM(this, 0, 0.5f, 0f, 0f)
                                Matrix.scaleM(this, 0, 0.5f, 1.0f, 1.0f)
                            }
                            val dummyBorder = floatArrayOf(0f, 0f, 0f, 0f)
                            prog.drawFaceCam(camera1TexId, camera1TexMatrix, leftMvp, 2, dummyBorder, 0f, 0f, camera1Mirrored)
                            prog.drawFaceCam(camera2TexId, camera2TexMatrix, rightMvp, 2, dummyBorder, 0f, 0f, camera2Mirrored)
                        }
                        else -> {
                            // "pip" or "dual_bubbles": Camera 1 is background, Camera 2 is floating PiP overlay
                            val fullMvp = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
                            val dummyBorder = floatArrayOf(0f, 0f, 0f, 0f)
                            prog.drawFaceCam(camera1TexId, camera1TexMatrix, fullMvp, 2, dummyBorder, 0f, 0f, camera1Mirrored)

                            if (camera2Enabled) {
                                drawCameraOverlay(
                                    prog = prog,
                                    textureId = camera2TexId,
                                    texMatrix = camera2TexMatrix,
                                    shape = camera2Shape,
                                    posX = camera2PositionX,
                                    posY = camera2PositionY,
                                    scale = camera2Scale,
                                    borderWidthDp = camera2BorderWidthDp,
                                    borderColorHex = camera2BorderColorHex,
                                    isMirrored = camera2Mirrored
                                )
                            }
                        }
                    }
                }
                else -> {
                    // Default fallback to screen
                    prog.drawScreen(screenTexId, screenTexMatrix)
                }
            }

            // Set presentation time and swap buffers
            core.setPresentationTime(eglSurface, System.nanoTime())
            core.swapBuffers(eglSurface)
        } catch (e: Exception) {
            Log.e(tag, "Error drawing compositor frame", e)
        }
    }

    private fun drawCameraOverlay(
        prog: FaceCamGlProgram,
        textureId: Int,
        texMatrix: FloatArray,
        shape: String,
        posX: Float,
        posY: Float,
        scale: Float,
        borderWidthDp: Int,
        borderColorHex: String,
        isMirrored: Boolean
    ) {
        val mvpMatrix = calculateCameraMvpMatrix(shape, posX, posY, scale)
        val shapeCode = when (shape) {
            "rounded-square" -> 1
            "rectangle" -> 2
            else -> 0 // circle
        }

        val parsedColor = try {
            Color.parseColor(borderColorHex)
        } catch (_: Exception) {
            Color.parseColor("#FF4B2B")
        }

        val borderColor = floatArrayOf(
            Color.red(parsedColor) / 255.0f,
            Color.green(parsedColor) / 255.0f,
            Color.blue(parsedColor) / 255.0f,
            Color.alpha(parsedColor) / 255.0f
        )

        val relBorderWidth = (borderWidthDp.toFloat() / 60.0f).coerceIn(0.0f, 0.15f)

        prog.drawFaceCam(
            textureId = textureId,
            texMatrix = texMatrix,
            mvpMatrix = mvpMatrix,
            shape = shapeCode,
            borderColor = borderColor,
            borderWidth = relBorderWidth,
            cornerRadius = 0.25f,
            isMirrored = isMirrored
        )
    }

    private fun calculateCameraMvpMatrix(
        shape: String,
        posX: Float,
        posY: Float,
        scale: Float
    ): FloatArray {
        val mvp = FloatArray(16)
        Matrix.setIdentityM(mvp, 0)

        // Screen aspect ratio
        val aspect = videoHeight.toFloat() / videoWidth.toFloat()

        // Size of camera bubble in clip space (clip space is -1 to 1, total width = 2.0)
        val clipWidth = (scale.coerceIn(0.10f, 0.80f) * 2.0f)
        val clipHeight = if (shape == "rectangle") {
            clipWidth * (3.0f / 4.0f) * (1.0f / aspect)
        } else {
            clipWidth * (1.0f / aspect)
        }

        // Coordinate mapping: posX and posY (0.0 to 1.0)
        val margin = 0.04f
        val minX = -1.0f + clipWidth / 2.0f + margin
        val maxX = 1.0f - clipWidth / 2.0f - margin
        val targetX = minX + (posX.coerceIn(0.0f, 1.0f)) * (maxX - minX)

        val maxY = 1.0f - clipHeight / 2.0f - margin
        val minY = -1.0f + clipHeight / 2.0f + margin
        val targetY = maxY - (posY.coerceIn(0.0f, 1.0f)) * (maxY - minY)

        Matrix.translateM(mvp, 0, targetX, targetY, 0.0f)
        Matrix.scaleM(mvp, 0, clipWidth / 2.0f, clipHeight / 2.0f, 1.0f)

        return mvp
    }

    fun updatePosition(xPercent: Float, yPercent: Float) {
        camera1PositionX = xPercent.coerceIn(0.0f, 1.0f)
        camera1PositionY = yPercent.coerceIn(0.0f, 1.0f)
    }

    fun updateSecondaryPosition(xPercent: Float, yPercent: Float) {
        camera2PositionX = xPercent.coerceIn(0.0f, 1.0f)
        camera2PositionY = yPercent.coerceIn(0.0f, 1.0f)
    }

    fun updateConfig(
        enabled: Boolean,
        shape: String,
        posX: Float,
        posY: Float,
        scale: Float,
        borderWidthDp: Int,
        borderColorHex: String,
        isMirrored: Boolean,
        mode: String = cameraMode,
        layout: String = dualLayout
    ) {
        camera1Enabled = enabled
        camera1Shape = shape
        camera1PositionX = posX
        camera1PositionY = posY
        camera1Scale = scale
        camera1BorderWidthDp = borderWidthDp
        camera1BorderColorHex = borderColorHex
        camera1Mirrored = isMirrored
        cameraMode = mode
        dualLayout = layout
    }

    fun updateSecondaryConfig(
        enabled: Boolean,
        shape: String,
        posX: Float,
        posY: Float,
        scale: Float,
        borderWidthDp: Int,
        borderColorHex: String,
        isMirrored: Boolean
    ) {
        camera2Enabled = enabled
        camera2Shape = shape
        camera2PositionX = posX
        camera2PositionY = posY
        camera2Scale = scale
        camera2BorderWidthDp = borderWidthDp
        camera2BorderColorHex = borderColorHex
        camera2Mirrored = isMirrored
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        val latch = CountDownLatch(1)
        renderHandler?.post {
            try {
                screenSurface?.release()
                camera1Surface?.release()
                camera2Surface?.release()

                screenSurfaceTexture?.release()
                camera1SurfaceTexture?.release()
                camera2SurfaceTexture?.release()

                glProgram?.release()

                eglCore?.let { core ->
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        core.releaseSurface(eglSurface)
                        eglSurface = EGL14.EGL_NO_SURFACE
                    }
                    core.release()
                }
                eglCore = null
            } catch (e: Exception) {
                Log.e(tag, "Error releasing compositor resources", e)
            } finally {
                latch.countDown()
            }
        }

        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {}

        renderThread?.quitSafely()
        renderThread = null
        renderHandler = null
        Log.d(tag, "ScreenCameraCompositor stopped and released")
    }
}
