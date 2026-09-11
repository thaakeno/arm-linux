package com.example.dreamlinux

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Native Android Surface benchmark for Vessel's post-VNC display path.
 *
 * This intentionally bypasses VNC, RFB, Termux:X11 and XCB completely. It gives us a clean
 * SurfaceView/BufferQueue baseline for frame pacing and touch latency before the guest scanout is
 * wired into the same Surface path.
 */
class NativeCubeActivity : Activity() {
    private lateinit var cubeView: NativeCubeView
    private lateinit var stats: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        cubeView = NativeCubeView(this) { fps, frameMs, renderer ->
            runOnUiThread {
                stats.text = "Vessel Native Surface  •  %.1f FPS  •  %.2f ms\n%s\nDrag to rotate  •  Pinch to zoom".format(
                    fps, frameMs, renderer
                )
            }
        }

        stats = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            textSize = 13f
            setPadding(28, 18, 28, 18)
            text = "Starting native Surface…"
        }

        val root = FrameLayout(this)
        root.addView(
            cubeView,
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
    }

    override fun onResume() {
        super.onResume()
        cubeView.onResume()
    }

    override fun onPause() {
        cubeView.onPause()
        super.onPause()
    }
}

private class NativeCubeView(
    context: android.content.Context,
    statsCallback: (fps: Float, frameMs: Float, renderer: String) -> Unit
) : GLSurfaceView(context) {
    private val cubeRenderer = CubeRenderer(statsCallback)
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            cubeRenderer.zoomBy(detector.scaleFactor)
            return true
        }
    })

    private var lastX = 0f
    private var lastY = 0f

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        holder.setFormat(PixelFormat.OPAQUE)
        setRenderer(cubeRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isFocusableInTouchMode = true
        systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    cubeRenderer.rotateBy(dx * 0.32f, dy * 0.32f)
                    lastX = event.x
                    lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }
}

private class CubeRenderer(
    private val statsCallback: (fps: Float, frameMs: Float, renderer: String) -> Unit
) : GLSurfaceView.Renderer {
    @Volatile private var yaw = -28f
    @Volatile private var pitch = 22f
    @Volatile private var cameraDistance = 6.2f

    private var program = 0
    private var vbo = 0
    private var vao = 0
    private var vertexCount = 0

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)

    private var frames = 0
    private var statsStartNs = 0L
    private var rendererName = "OpenGL ES"

    fun rotateBy(dx: Float, dy: Float) {
        yaw += dx
        pitch = (pitch + dy).coerceIn(-89f, 89f)
    }

    fun zoomBy(scale: Float) {
        cameraDistance = (cameraDistance / scale).coerceIn(3.2f, 11f)
    }

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES30.glClearColor(0.018f, 0.024f, 0.022f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glEnable(GLES30.GL_MULTISAMPLE)

        rendererName = GLES30.glGetString(GLES30.GL_RENDERER) ?: "Android GPU"
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        val data = buildCube()
        vertexCount = data.size / FLOATS_PER_VERTEX
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(data); position(0) }

        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glBindVertexArray(vao)

        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES30.GL_STATIC_DRAW)

        val stride = FLOATS_PER_VERTEX * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 6 * 4)

        GLES30.glBindVertexArray(0)
        statsStartNs = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        Matrix.perspectiveM(projection, 0, 50f, width.toFloat() / max(1, height).toFloat(), 0.1f, 100f)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        val frameStart = System.nanoTime()
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        Matrix.setLookAtM(view, 0, 0f, 0.15f, cameraDistance, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uModel"), 1, false, model, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(program, "uLightDir"), -0.35f, 0.75f, 0.55f)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(program, "uEye"), 0f, 0.15f, cameraDistance)

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
        GLES30.glBindVertexArray(0)

        frames++
        val now = System.nanoTime()
        val elapsed = now - statsStartNs
        if (elapsed >= 500_000_000L) {
            val fps = frames * 1_000_000_000f / elapsed.toFloat()
            val frameMs = (now - frameStart) / 1_000_000f
            statsCallback(fps, frameMs, rendererName)
            frames = 0
            statsStartNs = now
        }
    }

    private fun createProgram(vs: String, fs: String): Int {
        fun shader(type: Int, source: String): Int {
            val id = GLES30.glCreateShader(type)
            GLES30.glShaderSource(id, source)
            GLES30.glCompileShader(id)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(id)
                GLES30.glDeleteShader(id)
                error("Shader compile failed: $log")
            }
            return id
        }

        val v = shader(GLES30.GL_VERTEX_SHADER, vs)
        val f = shader(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("Program link failed: ${GLES30.glGetProgramInfoLog(p)}")
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
        fun face(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, n: FloatArray, color: FloatArray) {
            tri(a, b, c, n, color)
            tri(a, c, d, n, color)
        }

        val p000 = floatArrayOf(-1f,-1f,-1f); val p001 = floatArrayOf(-1f,-1f, 1f)
        val p010 = floatArrayOf(-1f, 1f,-1f); val p011 = floatArrayOf(-1f, 1f, 1f)
        val p100 = floatArrayOf( 1f,-1f,-1f); val p101 = floatArrayOf( 1f,-1f, 1f)
        val p110 = floatArrayOf( 1f, 1f,-1f); val p111 = floatArrayOf( 1f, 1f, 1f)

        face(p001,p101,p111,p011,floatArrayOf(0f,0f,1f),floatArrayOf(0.18f,0.95f,0.72f))
        face(p100,p000,p010,p110,floatArrayOf(0f,0f,-1f),floatArrayOf(0.20f,0.52f,1f))
        face(p101,p100,p110,p111,floatArrayOf(1f,0f,0f),floatArrayOf(0.92f,0.35f,0.40f))
        face(p000,p001,p011,p010,floatArrayOf(-1f,0f,0f),floatArrayOf(0.64f,0.36f,1f))
        face(p011,p111,p110,p010,floatArrayOf(0f,1f,0f),floatArrayOf(1f,0.76f,0.22f))
        face(p000,p100,p101,p001,floatArrayOf(0f,-1f,0f),floatArrayOf(0.20f,0.78f,0.92f))
        return out.toFloatArray()
    }

    companion object {
        private const val FLOATS_PER_VERTEX = 9

        private const val VERTEX_SHADER = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec3 aColor;
uniform mat4 uMvp;
uniform mat4 uModel;
out vec3 vNormal;
out vec3 vColor;
out vec3 vWorld;
void main() {
    vec4 world = uModel * vec4(aPos, 1.0);
    vWorld = world.xyz;
    vNormal = normalize(mat3(uModel) * aNormal);
    vColor = aColor;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

        private const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vColor;
in vec3 vWorld;
uniform vec3 uLightDir;
uniform vec3 uEye;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    vec3 l = normalize(uLightDir);
    vec3 v = normalize(uEye - vWorld);
    vec3 h = normalize(l + v);
    float diffuse = max(dot(n, l), 0.0);
    float spec = pow(max(dot(n, h), 0.0), 52.0);
    float rim = pow(1.0 - max(dot(n, v), 0.0), 2.4);
    vec3 col = vColor * (0.20 + diffuse * 0.82) + vec3(spec * 0.55) + vColor * rim * 0.22;
    col = pow(col, vec3(0.92));
    fragColor = vec4(col, 1.0);
}
"""
    }
}
