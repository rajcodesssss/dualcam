package com.dualcam.app

import android.graphics.Color
import android.graphics.ColorMatrix
import kotlin.math.abs
import kotlin.math.pow

/**
 * Ek look ke parameters (lift/gamma/contrast/tints/sat/temp).
 */
class LookParams(
    val name: String,
    val tagline: String,
    val lift: Float,
    val gamma: Float,
    val k: Float,
    val sh: FloatArray,
    val hi: FloatArray,
    val sat: Float,
    val temp: Float
)

/**
 * Ek filter:
 *  - export: look se exact 33^3 3D LUT cube -> Media3 SingleColorLut
 *  - preview: TextureView sirf 4x5 ColorMatrix leta hai, isliye look ka best-fit matrix (least squares)
 */
class Filter(val name: String, val matrix: FloatArray, val look: LookParams? = null) {
    val isIdentity: Boolean get() = name == "Original"
    val tagline: String get() = look?.tagline ?: "as shot"
}

object Grade {
    private fun lum(x: FloatArray) = 0.2126f * x[0] + 0.7152f * x[1] + 0.0722f * x[2]

    /** rgb 0..1 -> graded rgb 0..1 */
    fun apply(p: LookParams, rgb: FloatArray): FloatArray {
        val x = FloatArray(3)
        for (c in 0..2) {
            var v = rgb[c].coerceIn(0f, 1f)
            v = v * (1f - p.lift) + p.lift
            v = v.pow(1f / p.gamma)
            val s = v * v * (3f - 2f * v)
            x[c] = v * (1f - p.k) + s * p.k
        }
        var l = lum(x)
        for (c in 0..2) x[c] = l + (x[c] - l) * p.sat
        x[0] *= 1f + p.temp
        x[2] *= 1f - p.temp
        l = lum(x)
        val ws = (1f - l) * (1f - l)
        val wh = l * l
        for (c in 0..2) x[c] = (x[c] + ws * p.sh[c] + wh * p.hi[c]).coerceIn(0f, 1f)
        return x
    }
}

object Filters {

    /** Agar export me colors ulte (red<->blue) dikhein to isko true karke dekho. */
    const val SWAP_RB_AXES = false

    private val LOOKS: List<LookParams> = listOf(
        LookParams("Chai Hour", "golden, warm, a little nostalgic", 0.02f, 1.05f, 0.25f, floatArrayOf(0.03f, 0f, -0.02f), floatArrayOf(0.07f, 0.035f, -0.05f), 1.1f, 0.06f),
        LookParams("Gulabi Dusk", "pink shadows, peach skies", 0.05f, 1.1f, 0.12f, floatArrayOf(0.06f, -0.01f, 0.05f), floatArrayOf(0.07f, 0.02f, -0.02f), 1.05f, 0.02f),
        LookParams("Monsoon Teal", "cool, rainy, quietly green", 0.03f, 1f, 0.3f, floatArrayOf(-0.04f, 0.02f, 0.04f), floatArrayOf(-0.01f, 0.02f, 0.02f), 0.85f, -0.05f),
        LookParams("Dilli 90s", "faded film, yellow-green cast", 0.07f, 1f, 0.18f, floatArrayOf(0f, 0.03f, 0f), floatArrayOf(0.04f, 0.03f, -0.03f), 0.8f, 0.03f),
        LookParams("Moonlit Mumbai", "blue nights, warm lights", 0.02f, 0.95f, 0.4f, floatArrayOf(-0.03f, 0f, 0.07f), floatArrayOf(0.04f, 0.01f, -0.01f), 1f, -0.04f),
        LookParams("Sage Cafe", "muted greens, creamy light", 0.04f, 1.05f, 0.15f, floatArrayOf(-0.01f, 0.025f, 0f), floatArrayOf(0.04f, 0.035f, 0f), 0.72f, 0.01f),
        LookParams("Neon Chowk", "cyan shadows, magenta glow", 0f, 0.95f, 0.5f, floatArrayOf(-0.03f, 0.03f, 0.07f), floatArrayOf(0.08f, -0.02f, 0.05f), 1.3f, 0f),
        LookParams("Kaajal B&W", "deep blacks, soft grain-free mono", 0.02f, 1f, 0.45f, floatArrayOf(0f, 0f, 0f), floatArrayOf(0.03f, 0.02f, 0f), 0f, 0f),
        LookParams("Dreamy Haze", "bright, lifted, pastel", 0.09f, 1.2f, 0.05f, floatArrayOf(0.03f, 0f, 0.04f), floatArrayOf(0.04f, 0.03f, 0.04f), 0.9f, 0.01f)
    )

    val all: List<Filter> =
        listOf(Filter("Original", ColorMatrix().array.copyOf())) +
            LOOKS.map { Filter(it.name, fitMatrix(it), it) }

    /** Filter se N x N x N 3D LUT cube (ARGB ints) banata hai, Media3 ke liye. */
    fun buildCube(f: Filter, n: Int = 33): Array<Array<IntArray>> {
        val look = f.look
        return Array(n) { i ->
            Array(n) { j ->
                IntArray(n) { k ->
                    val ri = if (SWAP_RB_AXES) k else i
                    val bi = if (SWAP_RB_AXES) i else k
                    val rgb = floatArrayOf(ri / (n - 1f), j / (n - 1f), bi / (n - 1f))
                    val o = if (look != null) Grade.apply(look, rgb) else rgb
                    Color.argb(
                        255,
                        (o[0] * 255f + 0.5f).toInt().coerceIn(0, 255),
                        (o[1] * 255f + 0.5f).toInt().coerceIn(0, 255),
                        (o[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    )
                }
            }
        }
    }

    // --- preview ke liye least-squares 4x5 ColorMatrix fit ---

    private fun fitMatrix(p: LookParams): FloatArray {
        val n = 9
        val a = Array(4) { DoubleArray(4) }
        val bs = Array(3) { DoubleArray(4) }
        for (i in 0 until n) for (j in 0 until n) for (k in 0 until n) {
            val rgb = floatArrayOf(i / (n - 1f), j / (n - 1f), k / (n - 1f))
            val y = Grade.apply(p, rgb)
            val x = doubleArrayOf(rgb[0].toDouble(), rgb[1].toDouble(), rgb[2].toDouble(), 1.0)
            for (r in 0..3) {
                for (c in 0..3) a[r][c] += x[r] * x[c]
                for (ch in 0..2) bs[ch][r] += x[r] * y[ch]
            }
        }
        val m = FloatArray(20)
        m[18] = 1f
        for (ch in 0..2) {
            val s = solve4(a, bs[ch])
            m[ch * 5 + 0] = s[0].toFloat()
            m[ch * 5 + 1] = s[1].toFloat()
            m[ch * 5 + 2] = s[2].toFloat()
            m[ch * 5 + 4] = (s[3] * 255.0).toFloat()
        }
        return m
    }

    private fun solve4(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = 4
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            for (r in 0 until n) {
                if (r == col) continue
                val fct = m[r][col] / m[col][col]
                for (c in col..n) m[r][c] -= fct * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }
}
