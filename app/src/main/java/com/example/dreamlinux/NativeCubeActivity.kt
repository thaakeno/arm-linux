package com.example.dreamlinux

import android.app.Activity
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Native Android Surface benchmark for Vessel's post-VNC display path.
 *
 * This deliberately uses the most compatible Android GPU path we can get: GLSurfaceView +
 * OpenGL ES 2.0 + an explicit depth-buffer EGL config. It does not depend on Termux, X11,
 * VNC/RFB, Debian, Venus, or the guest runtime.
 */
class NativeCubeActivity : Activity() {
    private var cubeView: NativeCubeView? = null
    private lateinit var stats: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Do not use experimental fullscreen/frame-rate APIs here. This screen is our baseline
        // compatibility test, so launch reliability matters more than decoration.
        stats = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC050807.toInt())
            textSize = 13f
            setPadding(28, 18, 28, 18)
            text = "Vessel Native Surface\nStarting GPU render…\nDrag to rotate · Pinch to zoom"
        }

        try {
            val view = NativeCubeView(
                this,
                statsCallback = { fps, frameMs, renderer ->
                    runOnUiThread {
                        stats.setBackgroundColor(0xAA050807.toInt())
                        stats.text = "Vessel Native Surface  •  %.1f FPS  •  %.2f ms\n%s\nDrag to rotate  •  Pinch to zoom".format(
                            fps, frameMs, renderer
                        )
                    }
                },
                errorCallback = { message -> showError(message) }
            )
            cubeView = view

            val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
            root.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            root.addView(
                stats,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = 28
                    topMargin = 28
                }
            )
            setContentView(root)
        } catch (t: Throwable) {
            showFatalLaunchError(t)
        }
    }

    private fun showError(message: String) {
        runCatching {
            File(filesDir, "native-cube-error.txt").writeText(message)
        }
        runOnUiThread {
            stats.setBackgroundColor(0xEE3B171A.toInt())
            stats.text = "Native Surface render error\n$message\nPress Back to return to Vessel"
        }
    }

    private fun showFatalLaunchError(t: Throwable) {
        val message = buildString {
            append(t.javaClass.simpleName)
            if (!t.message.isNullOrBlank()) append(": ").append(t.message)
        }
        runCatching {
            File(filesDir, "native-cube-error.txt").writeText(message + "\n" + t.stackTraceToString())
        }
        val fallback = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(36, 72, 36, 36)
            text = "Vessel Native Surface failed to start\n\n$message\n\nPress Back to return to Vessel"
        }
        setContentView(fallback)
    }

    override fun onResume() {
        super.onResume()
        cubeView?.onResume()
    }

    override fun onPause() {
        cubeView?.onPause()
        super.onPause()
    }
}

private class NativeCubeView(
    context: android.content.Context,
    statsCallback: (fps: Float, frameMs: Float, renderer: String) -> Unit,
    errorCallback: (String) -> Unit
) : GLSurfaceView(context) {
    private val cubeRenderer = CubeRenderer(statsCallback, errorCallback)
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                cubeRenderer.zoomBy(detector.scaleFactor)
                return true
            }
        }
    )

    private var lastX = 0f
    private var lastY = 0f

    init {
        // ES 2.0 is guaranteed on practically every modern Android device and avoids the extra
        // EGL/driver failure surface of the earlier ES 3-only benchmark.
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(cubeRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                cubeRenderer.markInteraction()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                cubeRenderer.markInteraction()
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    cubeRenderer.rotateBy(dx * 0.32f, dy * 0.32f)
                    lastX = event.x
                    lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cubeRenderer.markInteraction()
                return true
            }
        }
        return true
    }
}

private class CubeRenderer(
    private val statsCallback: (fps: Float, frameMs: Float, renderer: String) -> Unit,
    private val errorCallback: (String) -> Unit
) : GLSurfaceView.Renderer {
    @Volatile private var yaw = -28f
    @Volatile private var pitch = 22f
    @Volatile private var cameraDistance = 6.2f
    @Volatile private var lastInteractionNs = 0L

    private var program = 0
    private var vbo = 0
    private var vertexCount = 0
    private var ready = false

    private var positionLoc = -1
    private var normalLoc = -1
    private var colorLoc = -1
    private var mvpLoc = -1
    private var modelLoc = -1
    private var lightLoc = -1
    private var eyeLoc = -1

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)

    private var frames = 0
    private var statsStartNs = 0L
    private var previousFrameNs = 0L
    private var rendererName = "OpenGL ES"

    fun markInteraction() {
        lastInteractionNs = System.nanoTime()
    }

    fun rotateBy(dx: Float, dy: Float) {
        yaw += dx
        pitch = (pitch + dy).coerceIn(-89f, 89f)
    }

    fun zoomBy(scale: Float) {
        markInteraction()
        cameraDistance = (cameraDistance / scale).coerceIn(3.2f, 11f)
    }

    override fun onSurfaceCreated(
        gl: javax.microedition.khronos.opengles.GL10?,
        config: javax.microedition.khronos.egl.EGLConfig?
    ) {
        try {
            GLES20.glClearColor(0.012f, 0.018f, 0.016f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)

            rendererName = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Android GPU"
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)

            positionLoc = GLES20.glGetAttribLocation(program, "aPos")
            normalLoc = GLES20.glGetAttribLocation(program, "aNormal")
            colorLoc = GLES20.glGetAttribLocation(program, "aColor")
            mvpLoc = GLES20.glGetUniformLocation(program, "uMvp")
            modelLoc = GLES20.glGetUniformLocation(program, "uModel")
            lightLoc = GLES20.glGetUniformLocation(program, "uLightDir")
            eyeLoc = GLES20.glGetUniformLocation(program, "uEye")

            val data = buildCube()
            vertexCount = data.size / FLOATS_PER_VERTEX
            val buffer = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(data); position(0) }

            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            vbo = ids[0]
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES20.GL_STATIC_DRAW)

            statsStartNs = System.nanoTime()
            previousFrameNs = statsStartNs
            ready = true
        } catch (t: Throwable) {
            ready = false
            errorCallback(t.message ?: t.javaClass.simpleName)
        }
    }

    override fun onSurfaceChanged(
        gl: javax.microedition.khronos.opengles.GL10?,
        width: Int,
        height: Int
    ) {
        GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(
            projection,
            0,
            50f,
            width.toFloat() / max(1, height).toFloat(),
            0.1f,
            100f
        )
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        val frameStart = System.nanoTime()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!ready) return

        try {
            val deltaSeconds =
                ((frameStart - previousFrameNs).coerceIn(0L, 50_000_000L)) / 1_000_000_000f
            previousFrameNs = frameStart
            if (frameStart - lastInteractionNs > 220_000_000L) {
                yaw += 24f * deltaSeconds
            }

            Matrix.setLookAtM(view, 0, 0f, 0.15f, cameraDistance, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.setIdentityM(model, 0)
            Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
            Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(modelLoc, 1, false, model, 0)
            GLES20.glUniform3f(lightLoc, -0.35f, 0.75f, 0.55f)
            GLES20.glUniform3f(eyeLoc, 0f, 0.15f, cameraDistance)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            val stride = FLOATS_PER_VERTEX * 4
            GLES20.glEnableVertexAttribArray(positionLoc)
            GLES20.glVertexAttribPointer(positionLoc, 3, GLES20.GL_FLOAT, false, stride, 0)
            GLES20.glEnableVertexAttribArray(normalLoc)
            GLES20.glVertexAttribPointer(normalLoc, 3, GLES20.GL_FLOAT, false, stride, 3 * 4)
            GLES20.glEnableVertexAttribArray(colorLoc)
            GLES20.glVertexAttribPointer(colorLoc, 3, GLES20.GL_FLOAT, false, stride, 6 * 4)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

            frames++
            val now = System.nanoTime()
            val elapsed = now - statsStartNs
            if (elapsed >= 500_000_000L) {
                val fps = frames * 1_000_000_000f / elapsed.toFloat()
                val frameMs = 1000f / max(1f, fps)
                statsCallback(fps, frameMs, rendererName)
                frames = 0
                statsStartNs = now
            }
        } catch (t: Throwable) {
            ready = false
            errorCallback(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun createProgram(vs: String, fs: String): Int {
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source)
            GLES20.glCompileShader(id)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(id)
                GLES20.glDeleteShader(id)
                error("Shader compile failed: $log")
            }
            return id
        }

        val v = shader(GLES20.GL_VERTEX_SHADER, vs)
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("Program link failed: ${GLES20.glGetProgramInfoLog(p)}")
        return p
    }

    private fun buildCube(): FloatArray {
        val out = ArrayList<Float>(36 * FLOATS_PER_VERTEX)
        fun tri(a: FloatArray, b: FloatArray, c: FloatArray, n: FloatArray, color: FloatArray) {
            for (p in arrayOf(a, b, c)) {
                out.add(p[0]); out.add(p[1]); out.add(p[2])
                out.add(n[0]); out.add(n[1]); out.add(n[2])
                out.add(color[0]); out.add(color[1]); out.add(color[2])
            }
        }
        fun face(
            a: FloatArray,
            b: FloatArray,
            c: FloatArray,
            d: FloatArray,
            n: FloatArray,
            color: FloatArray
        ) {
            tri(a, b, c, n, color)
            tri(a, c, d, n, color)
        }

        val p000 = floatArrayOf(-1f, -1f, -1f)
        val p001 = floatArrayOf(-1f, -1f, 1f)
        val p010 = floatArrayOf(-1f, 1f, -1f)
        val p011 = floatArrayOf(-1f, 1f, 1f)
        val p100 = floatArrayOf(1f, -1f, -1f)
        val p101 = floatArrayOf(1f, -1f, 1f)
        val p110 = floatArrayOf(1f, 1f, -1f)
        val p111 = floatArrayOf(1f, 1f, 1f)

        face(p001, p101, p111, p011, floatArrayOf(0f, 0f, 1f), floatArrayOf(0.18f, 0.95f, 0.72f))
        face(p100, p000, p010, p110, floatArrayOf(0f, 0f, -1f), floatArrayOf(0.20f, 0.52f, 1f))
        face(p101, p100, p110, p111, floatArrayOf(1f, 0f, 0f), floatArrayOf(0.92f, 0.35f, 0.40f))
        face(p000, p001, p011, p010, floatArrayOf(-1f, 0f, 0f), floatArrayOf(0.64f, 0.36f, 1f))
        face(p011, p111, p110, p010, floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0.76f, 0.22f))
        face(p000, p100, p101, p001, floatArrayOf(0f, -1f, 0f), floatArrayOf(0.20f, 0.78f, 0.92f))
        return out.toFloatArray()
    }

    companion object {
        private const val FLOATS_PER_VERTEX = 9

        private const val VERTEX_SHADER = """
attribute vec3 aPos;
attribute vec3 aNormal;
attribute vec3 aColor;
uniform mat4 uMvp;
uniform mat4 uModel;
varying vec3 vNormal;
varying vec3 vColor;
varying vec3 vWorld;
void main() {
    vec4 world = uModel * vec4(aPos, 1.0);
    vWorld = world.xyz;
    vNormal = normalize(mat3(uModel) * aNormal);
    vColor = aColor;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

        private const val FRAGMENT_SHADER = """
precision mediump float;
varying vec3 vNormal;
varying vec3 vColor;
varying vec3 vWorld;
uniform vec3 uLightDir;
uniform vec3 uEye;
void main() {
    vec3 n = normalize(vNormal);
    vec3 l = normalize(uLightDir);
    vec3 v = normalize(uEye - vWorld);
    vec3 h = normalize(l + v);
    float diffuse = max(dot(n, l), 0.0);
    float spec = pow(max(dot(n, h), 0.0), 32.0);
    float rim = pow(1.0 - max(dot(n, v), 0.0), 2.2);
    vec3 col = vColor * (0.20 + diffuse * 0.82) + vec3(spec * 0.45) + vColor * rim * 0.20;
    gl_FragColor = vec4(col, 1.0);
}
"""
    }
}
