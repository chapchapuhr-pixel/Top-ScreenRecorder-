package com.screenpro.recording.compositor

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * FaceCamGlProgram
 * Handles OpenGL ES 2.0 rendering of screen texture and camera overlay with
 * shape clipping (circle, rounded rectangle, rect), borders, and mirroring.
 */
class FaceCamGlProgram {
    private val tag = "FaceCamGlProgram"

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        uniform mat4 uMVPMatrix;
        uniform mat4 uTexMatrix;
        varying vec2 vTextureCoord;
        varying vec2 vLocalPos;

        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = (uTexMatrix * aTextureCoord).xy;
            vLocalPos = aPosition.xy;
        }
    """.trimIndent()

    private val screenFragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;

        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """.trimIndent()

    private val cameraFragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        varying vec2 vLocalPos;

        uniform samplerExternalOES sCameraTexture;
        uniform int uShape; // 0: circle, 1: rounded-square, 2: rectangle
        uniform vec4 uBorderColor;
        uniform float uBorderWidth;
        uniform float uCornerRadius;
        uniform int uMirrored;

        void main() {
            vec2 p = vLocalPos;
            vec2 uv = vTextureCoord;
            if (uMirrored == 1) {
                uv.x = 1.0 - uv.x;
            }

            vec4 texColor = texture2D(sCameraTexture, uv);
            float alpha = 1.0;
            float borderFactor = 0.0;

            if (uShape == 0) {
                // Circle with smooth anti-aliasing
                float dist = length(p);
                float edge = 0.025;
                if (dist > 1.0) {
                    discard;
                }
                alpha = 1.0 - smoothstep(1.0 - edge, 1.0, dist);
                if (uBorderWidth > 0.001) {
                    borderFactor = smoothstep(1.0 - uBorderWidth - edge, 1.0 - uBorderWidth, dist);
                }
            } else if (uShape == 1) {
                // Rounded Rectangle with SDF
                float cr = max(uCornerRadius, 0.18);
                vec2 b = vec2(1.0 - cr);
                vec2 q = abs(p) - b;
                float dist = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - cr;
                float edge = 0.02;
                if (dist > 0.0) {
                    discard;
                }
                alpha = 1.0 - smoothstep(-edge, 0.0, dist);
                if (uBorderWidth > 0.001) {
                    borderFactor = smoothstep(-uBorderWidth - edge, -uBorderWidth, dist);
                }
            } else {
                // Standard Rectangle
                if (abs(p.x) > 1.0 || abs(p.y) > 1.0) {
                    discard;
                }
                if (uBorderWidth > 0.001) {
                    if (abs(p.x) > (1.0 - uBorderWidth) || abs(p.y) > (1.0 - uBorderWidth)) {
                        borderFactor = 1.0;
                    }
                }
            }

            vec4 finalColor = mix(texColor, uBorderColor, borderFactor * uBorderColor.a);
            gl_FragColor = vec4(finalColor.rgb, finalColor.a * alpha);
        }
    """.trimIndent()

    private var screenProgramId = 0
    private var cameraProgramId = 0

    // Screen program handles
    private var sPositionHandle = 0
    private var sTextureCoordHandle = 0
    private var sMVPMatrixHandle = 0
    private var sTexMatrixHandle = 0
    private var sTextureHandle = 0

    // Camera program handles
    private var cPositionHandle = 0
    private var cTextureCoordHandle = 0
    private var cMVPMatrixHandle = 0
    private var cTexMatrixHandle = 0
    private var cTextureHandle = 0
    private var cShapeHandle = 0
    private var cBorderColorHandle = 0
    private var cBorderWidthHandle = 0
    private var cCornerRadiusHandle = 0
    private var cMirroredHandle = 0

    private val vertexBuffer: FloatBuffer
    private val textureBuffer: FloatBuffer

    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    init {
        val quadCoords = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )
        vertexBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quadCoords)
                position(0)
            }

        val texCoords = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )
        textureBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(texCoords)
                position(0)
            }

        initPrograms()
    }

    private fun initPrograms() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val screenFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, screenFragmentShaderCode)
        val cameraFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, cameraFragmentShaderCode)

        // Screen Program
        screenProgramId = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vertexShader)
            GLES20.glAttachShader(prog, screenFragmentShader)
            GLES20.glLinkProgram(prog)
        }
        sPositionHandle = GLES20.glGetAttribLocation(screenProgramId, "aPosition")
        sTextureCoordHandle = GLES20.glGetAttribLocation(screenProgramId, "aTextureCoord")
        sMVPMatrixHandle = GLES20.glGetUniformLocation(screenProgramId, "uMVPMatrix")
        sTexMatrixHandle = GLES20.glGetUniformLocation(screenProgramId, "uTexMatrix")
        sTextureHandle = GLES20.glGetUniformLocation(screenProgramId, "sTexture")

        // Camera Program
        cameraProgramId = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vertexShader)
            GLES20.glAttachShader(prog, cameraFragmentShader)
            GLES20.glLinkProgram(prog)
        }
        cPositionHandle = GLES20.glGetAttribLocation(cameraProgramId, "aPosition")
        cTextureCoordHandle = GLES20.glGetAttribLocation(cameraProgramId, "aTextureCoord")
        cMVPMatrixHandle = GLES20.glGetUniformLocation(cameraProgramId, "uMVPMatrix")
        cTexMatrixHandle = GLES20.glGetUniformLocation(cameraProgramId, "uTexMatrix")
        cTextureHandle = GLES20.glGetUniformLocation(cameraProgramId, "sCameraTexture")
        cShapeHandle = GLES20.glGetUniformLocation(cameraProgramId, "uShape")
        cBorderColorHandle = GLES20.glGetUniformLocation(cameraProgramId, "uBorderColor")
        cBorderWidthHandle = GLES20.glGetUniformLocation(cameraProgramId, "uBorderWidth")
        cCornerRadiusHandle = GLES20.glGetUniformLocation(cameraProgramId, "uCornerRadius")
        cMirroredHandle = GLES20.glGetUniformLocation(cameraProgramId, "uMirrored")
    }

    fun drawScreen(textureId: Int, texMatrix: FloatArray) {
        GLES20.glUseProgram(screenProgramId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(sTextureHandle, 0)

        GLES20.glUniformMatrix4fv(sMVPMatrixHandle, 1, false, identityMatrix, 0)
        GLES20.glUniformMatrix4fv(sTexMatrixHandle, 1, false, texMatrix, 0)

        GLES20.glEnableVertexAttribArray(sPositionHandle)
        GLES20.glVertexAttribPointer(sPositionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

        GLES20.glEnableVertexAttribArray(sTextureCoordHandle)
        GLES20.glVertexAttribPointer(sTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(sPositionHandle)
        GLES20.glDisableVertexAttribArray(sTextureCoordHandle)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun drawFaceCam(
        textureId: Int,
        texMatrix: FloatArray,
        mvpMatrix: FloatArray,
        shape: Int,
        borderColor: FloatArray,
        borderWidth: Float,
        cornerRadius: Float,
        isMirrored: Boolean
    ) {
        GLES20.glUseProgram(cameraProgramId)

        // Enable alpha blending for shape cutout and anti-aliasing
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(cTextureHandle, 0)

        GLES20.glUniformMatrix4fv(cMVPMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(cTexMatrixHandle, 1, false, texMatrix, 0)

        GLES20.glUniform1i(cShapeHandle, shape)
        GLES20.glUniform4fv(cBorderColorHandle, 1, borderColor, 0)
        GLES20.glUniform1f(cBorderWidthHandle, borderWidth)
        GLES20.glUniform1f(cCornerRadiusHandle, cornerRadius)
        GLES20.glUniform1i(cMirroredHandle, if (isMirrored) 1 else 0)

        GLES20.glEnableVertexAttribArray(cPositionHandle)
        GLES20.glVertexAttribPointer(cPositionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

        GLES20.glEnableVertexAttribArray(cTextureCoordHandle)
        GLES20.glVertexAttribPointer(cTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(cPositionHandle)
        GLES20.glDisableVertexAttribArray(cTextureCoordHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun release() {
        if (screenProgramId != 0) {
            GLES20.glDeleteProgram(screenProgramId)
            screenProgramId = 0
        }
        if (cameraProgramId != 0) {
            GLES20.glDeleteProgram(cameraProgramId)
            cameraProgramId = 0
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val error = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Could not compile shader $type: $error")
            }
        }
    }

    companion object {
        const val GL_TEXTURE_EXTERNAL_OES = 0x8D65

        fun createTextureObject(): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val texId = textures[0]
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return texId
        }
    }
}
