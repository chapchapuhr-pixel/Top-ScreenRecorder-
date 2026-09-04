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
 * and the front camera stream into a single MediaRecorder input surface.
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
    private var cameraTexId = 0

    private var screenSurfaceTexture: SurfaceTexture? = null
    private var cameraSurfaceTexture: SurfaceTexture? = null

    var screenSurface: Surface? = null
        private set
    var cameraSurface: Surface? = null
        private set

    private val screenTexMatrix = FloatArray(16)
    private val cameraTexMatrix = FloatArray(16)

    // Configuration
    @Volatile var cameraEnabled: Boolean = true
    @Volatile var cameraShape: String = "circle" // "circle", "rounded-square", "rectangle"
    @Volatile var cameraPositionX: Float = 0.75f // 0.0 (left) to 1.0 (right)
    @Volatile var cameraPositionY: Float = 0.08f // 0.0 (top) to 1.0 (bottom)
    @Volatile var cameraScale: Float = 0.26f // Fraction of screen width
    @Volatile var cameraBorderWidthDp: Int = 3
    @Volatile var cameraBorderColorHex: String = "#FF4B2B"
    @Volatile var cameraMirrored: Boolean = true

    private var screenFrameAvailable = false
    private var cameraFrameAvailable = false

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

        // Create textures
        screenTexId = FaceCamGlProgram.createTextureObject()
        cameraTexId = FaceCamGlProgram.createTextureObject()

        // Setup Screen SurfaceTexture
        screenSurfaceTexture = SurfaceTexture(screenTexId).apply {
            setDefaultBufferSize(videoWidth, videoHeight)
            setOnFrameAvailableListener({
                screenFrameAvailable = true
            }, renderHandler)
        }
        screenSurface = Surface(screenSurfaceTexture)

        // Setup Camera SurfaceTexture (Front camera default resolution 1280x720 or 640x480)
        cameraSurfaceTexture = SurfaceTexture(cameraTexId).apply {
            setDefaultBufferSize(1280, 720)
            setOnFrameAvailableListener({
                cameraFrameAvailable = true
            }, renderHandler)
        }
        cameraSurface = Surface(cameraSurfaceTexture)

        GLES20.glViewport(0, 0, videoWidth, videoHeight)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        Log.d(tag, "Compositor OpenGL initialized ($videoWidth x $videoHeight @ ${targetFps}fps)")
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

            // Update Screen Texture if new image available
            try {
                screenSurfaceTexture?.updateTexImage()
                screenSurfaceTexture?.getTransformMatrix(screenTexMatrix)
            } catch (_: Exception) {}

            // Update Camera Texture if new image available
            if (cameraEnabled) {
                try {
                    cameraSurfaceTexture?.updateTexImage()
                    cameraSurfaceTexture?.getTransformMatrix(cameraTexMatrix)
                } catch (_: Exception) {}
            }

            GLES20.glViewport(0, 0, videoWidth, videoHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // 1. Draw Screen (Base Layer)
            prog.drawScreen(screenTexId, screenTexMatrix)

            // 2. Draw Camera Overlay (if enabled)
            if (cameraEnabled) {
                val mvpMatrix = calculateCameraMvpMatrix()
                val shapeCode = when (cameraShape) {
                    "rounded-square" -> 1
                    "rectangle" -> 2
                    else -> 0 // circle
                }

                val parsedColor = try {
                    Color.parseColor(cameraBorderColorHex)
                } catch (_: Exception) {
                    Color.parseColor("#FF4B2B")
                }
                val borderColor = floatArrayOf(
                    Color.red(parsedColor) / 255.0f,
                    Color.green(parsedColor) / 255.0f,
                    Color.blue(parsedColor) / 255.0f,
                    Color.alpha(parsedColor) / 255.0f
                )

                // Relative border width (0.0 to 0.15)
                val relBorderWidth = (cameraBorderWidthDp.toFloat() / 60.0f).coerceIn(0.0f, 0.15f)

                prog.drawFaceCam(
                    textureId = cameraTexId,
                    texMatrix = cameraTexMatrix,
                    mvpMatrix = mvpMatrix,
                    shape = shapeCode,
                    borderColor = borderColor,
                    borderWidth = relBorderWidth,
                    cornerRadius = 0.25f,
                    isMirrored = cameraMirrored
                )
            }

            // Set presentation time and swap buffers
            core.setPresentationTime(eglSurface, System.nanoTime())
            core.swapBuffers(eglSurface)
        } catch (e: Exception) {
            Log.e(tag, "Error drawing compositor frame", e)
        }
    }

    private fun calculateCameraMvpMatrix(): FloatArray {
        val mvp = FloatArray(16)
        Matrix.setIdentityM(mvp, 0)

        // Screen aspect ratio
        val aspect = videoHeight.toFloat() / videoWidth.toFloat()

        // Size of camera bubble in clip space (clip space is -1 to 1, total width = 2.0)
        val clipWidth = (cameraScale.coerceIn(0.12f, 0.60f) * 2.0f)
        val clipHeight = if (cameraShape == "rectangle") {
            clipWidth * (3.0f / 4.0f) * (1.0f / aspect)
        } else {
            // Keep 1:1 square/circle in screen coordinates
            clipWidth * (1.0f / aspect)
        }

        // Coordinate mapping: posX and posY (0.0 to 1.0)
        // Margin in clip space
        val margin = 0.04f
        val minX = -1.0f + clipWidth / 2.0f + margin
        val maxX = 1.0f - clipWidth / 2.0f - margin
        val targetX = minX + (cameraPositionX.coerceIn(0.0f, 1.0f)) * (maxX - minX)

        val maxY = 1.0f - clipHeight / 2.0f - margin
        val minY = -1.0f + clipHeight / 2.0f + margin
        // posY: 0.0 is top, 1.0 is bottom
        val targetY = maxY - (cameraPositionY.coerceIn(0.0f, 1.0f)) * (maxY - minY)

        Matrix.translateM(mvp, 0, targetX, targetY, 0.0f)
        Matrix.scaleM(mvp, 0, clipWidth / 2.0f, clipHeight / 2.0f, 1.0f)

        return mvp
    }

    fun updatePosition(xPercent: Float, yPercent: Float) {
        cameraPositionX = xPercent.coerceIn(0.0f, 1.0f)
        cameraPositionY = yPercent.coerceIn(0.0f, 1.0f)
    }

    fun updateConfig(
        enabled: Boolean,
        shape: String,
        posX: Float,
        posY: Float,
        scale: Float,
        borderWidthDp: Int,
        borderColorHex: String,
        isMirrored: Boolean
    ) {
        cameraEnabled = enabled
        cameraShape = shape
        cameraPositionX = posX
        cameraPositionY = posY
        cameraScale = scale
        cameraBorderWidthDp = borderWidthDp
        cameraBorderColorHex = borderColorHex
        cameraMirrored = isMirrored
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        val latch = CountDownLatch(1)
        renderHandler?.post {
            try {
                screenSurface?.release()
                cameraSurface?.release()

                screenSurfaceTexture?.release()
                cameraSurfaceTexture?.release()

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
