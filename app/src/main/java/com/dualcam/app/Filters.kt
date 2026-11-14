package com.dualcam.app

import android.graphics.Color
import android.graphics.ColorMatrix

/**
 * Ek filter = ek 4x5 ColorMatrix (Android format).
 * Wahi matrix 2 jagah use hota hai, taaki preview aur export ka look match kare:
 *  1) Live preview: PreviewView pe ColorMatrixColorFilter
 *  2) Export: matrix se 3D LUT cube banta hai -> Media3 SingleColorLut
 */
class Filter(val name: String, val matrix: FloatArray) {
    val isIdentity: Boolean get() = name == "Original"
}

object Filters {

    /** Agar export me colors ulte (red<->blue) dikhein to isko true karke dekho. */
    const val SWAP_RB_AXES = false

    private fun sat(s: Float) = ColorMatrix().apply { setSaturation(s) }

    private fun contrast(c: Float): ColorMatrix {
        val o = 128f * (1f - c)
        return ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, o,
                0f, c, 0f, 0f, o,
                0f, 0f, c, 0f, o,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private fun bright(o: Float) = ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, o,
            0f, 1f, 0f, 0f, o,
            0f, 0f, 1f, 0f, o,
            0f, 0f, 0f, 1f, 0f
        )
    )

    private fun tint(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }

    /** Steps ko order me apply karta hai (pehla step pehle lagta hai). */
    private fun chain(vararg steps: ColorMatrix): FloatArray {
        val m = ColorMatrix()
        for (s in steps) m.postConcat(s)
        return m.array.copyOf()
    }

    val all: List<Filter> = listOf(
        Filter("Original", ColorMatrix().array.copyOf()),
        Filter("Golden Hour", chain(sat(1.15f), tint(1.08f, 1.0f, 0.88f), contrast(1.05f))),
        Filter("Moody", chain(sat(0.8f), contrast(1.18f), tint(0.94f, 1.0f, 1.08f), bright(-6f))),
        Filter("Film Fade", chain(sat(0.9f), contrast(0.88f), bright(14f), tint(1.04f, 1.0f, 0.96f))),
        Filter("Mono Noir", chain(sat(0f), contrast(1.25f), bright(-4f))),
        Filter("Pastel", chain(sat(0.9f), contrast(0.85f), bright(22f), tint(1.04f, 0.97f, 1.06f))),
        Filter("Cyber Teal", chain(sat(1.2f), contrast(1.1f), tint(0.88f, 1.04f, 1.12f))),
        Filter("Vintage", chain(sat(0.7f), tint(1.1f, 1.02f, 0.82f), contrast(0.9f), bright(8f)))
    )

    /** Matrix se N x N x N 3D LUT cube (ARGB ints) banata hai, Media3 ke liye. */
    fun buildCube(m: FloatArray, n: Int = 33): Array<Array<IntArray>> =
        Array(n) { i ->
            Array(n) { j ->
                IntArray(n) { k ->
                    val ri = if (SWAP_RB_AXES) k else i
                    val bi = if (SWAP_RB_AXES) i else k
                    val r = ri * 255f / (n - 1)
                    val g = j * 255f / (n - 1)
                    val b = bi * 255f / (n - 1)
                    val or = (m[0] * r + m[1] * g + m[2] * b + m[4]).coerceIn(0f, 255f)
                    val og = (m[5] * r + m[6] * g + m[7] * b + m[9]).coerceIn(0f, 255f)
                    val ob = (m[10] * r + m[11] * g + m[12] * b + m[14]).coerceIn(0f, 255f)
                    Color.argb(255, or.toInt(), og.toInt(), ob.toInt())
                }
            }
        }
}
