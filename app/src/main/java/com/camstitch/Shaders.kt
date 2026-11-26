package com.camstitch

import android.opengl.GLES11Ext
import android.opengl.GLES20

object Gl {
    fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("shader compile failed: $log")
        }
        return s
    }

    fun program(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("program link failed: $log")
        }
        return p
    }

    fun newExternalTexture(): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }
}

object Shaders {
    /** Draws one camera texture into the current viewport (feed grid and snapshots). */
    const val TILE_VS = """
attribute vec2 aPos;
attribute vec2 aUv;
uniform mat4 uTexM;
uniform float uFlip;
varying vec2 vUv;
void main() {
    gl_Position = vec4(aPos.x, aPos.y * uFlip, 0.0, 1.0);
    vUv = (uTexM * vec4(aUv, 0.0, 1.0)).xy;
}
"""

    const val TILE_FS = """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUv;
uniform samplerExternalOES uTex;
void main() { gl_FragColor = texture2D(uTex, vUv); }
"""

    const val STITCH_VS = """
uniform mat4 uMVP;
attribute vec3 aWorld;
varying vec3 vWorld;
void main() {
    vWorld = aWorld;
    gl_Position = uMVP * vec4(aWorld, 1.0);
}
"""

    /**
     * For every bowl/floor fragment: project its world position into each
     * calibrated camera (OpenCV pinhole or fisheye model), and blend the
     * cameras that see it, weighted by distance from their image border.
     * Generated per camera count because GLES2 can't index external samplers.
     */
    fun stitchFs(n: Int): String {
        val sb = StringBuilder()
        sb.append("#extension GL_OES_EGL_image_external : require\n")
        sb.append(
            """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec3 vWorld;
uniform float uFeather;
"""
        )
        for (i in 0 until n) {
            sb.append(
                """
uniform samplerExternalOES uTex$i;
uniform mat3 uR$i; uniform vec3 uT$i; uniform vec4 uK$i; uniform vec4 uD$i; uniform float uD5_$i;
uniform vec2 uSize$i; uniform float uFish$i; uniform float uMaxR$i; uniform mat4 uTexM$i; uniform vec3 uGain$i;
"""
            )
        }
        sb.append(
            """
// returns (u/width, v/height, weight); weight 0 = not visible
vec3 project(vec3 pw, mat3 R, vec3 T, vec4 K, vec4 D, float D5, vec2 S, float fish, float maxR) {
    vec3 pc = R * pw + T;
    if (pc.z <= 1e-4) return vec3(0.0);
    float x = pc.x / pc.z;
    float y = pc.y / pc.z;
    float r2 = x * x + y * y;
    float xd; float yd;
    if (fish > 0.5) {
        float r = sqrt(r2);
        float th = atan(r);
        if (th > maxR) return vec3(0.0);
        float t2 = th * th;
        float thd = th * (1.0 + t2 * (D.x + t2 * (D.y + t2 * (D.z + t2 * D.w))));
        float s = r > 1e-8 ? thd / r : 1.0;
        xd = x * s; yd = y * s;
    } else {
        if (r2 > maxR * maxR) return vec3(0.0);
        float rad = 1.0 + r2 * (D.x + r2 * (D.y + r2 * D5));
        xd = x * rad + 2.0 * D.z * x * y + D.w * (r2 + 2.0 * x * x);
        yd = y * rad + D.z * (r2 + 2.0 * y * y) + 2.0 * D.w * x * y;
    }
    float u = K.x * xd + K.z;
    float v = K.y * yd + K.w;
    if (u < 0.0 || v < 0.0 || u > S.x || v > S.y) return vec3(0.0);
    float edge = min(min(u, S.x - u), min(v, S.y - v));
    float w = clamp(edge / uFeather, 0.0, 1.0);
    return vec3(u / S.x, v / S.y, w * w + 1e-4);
}

void main() {
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    vec3 p;
    vec2 st;
"""
        )
        for (i in 0 until n) {
            sb.append(
                """
    p = project(vWorld, uR$i, uT$i, uK$i, uD$i, uD5_$i, uSize$i, uFish$i, uMaxR$i);
    if (p.z > 0.0) {
        st = (uTexM$i * vec4(p.x, 1.0 - p.y, 0.0, 1.0)).xy;
        acc += texture2D(uTex$i, st).rgb * uGain$i * p.z;
        wsum += p.z;
    }
"""
            )
        }
        sb.append(
            """
    if (wsum > 0.0) gl_FragColor = vec4(acc / wsum, 1.0);
    else gl_FragColor = vec4(0.16, 0.16, 0.18, 1.0);
}
"""
        )
        return sb.toString()
    }
}
