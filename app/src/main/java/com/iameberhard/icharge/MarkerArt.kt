package com.iameberhard.icharge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The map pins both editions draw: a teardrop with a white glyph, the tip at the bottom centre.
 * Amber = DC fast (bolt), blue = AC (plug), grey = reported out of service, faded = skipped on
 * this trip; the selected pin is larger with a bright ring. Trip marks reuse the shape: next stop
 * (bolt), run-out point (!), destination (dot).
 */
object MarkerArt {
    enum class Glyph { BOLT, PLUG, DOT, BANG }

    /** Pin width in dp at scale 1; the height follows the shape (20/14 of the width). */
    const val W_DP = 26f
    private const val PAD_DP = 5f

    /** The pin tip sits this many px above the bitmap's bottom edge (shadow padding). */
    fun tipInset(density: Float, scale: Float = 1f) = PAD_DP * density * scale

    fun pin(density: Float, fill: Int, glyph: Glyph, scale: Float = 1f, alpha: Int = 255, ring: Int? = null): Bitmap {
        val pad = PAD_DP * density * scale
        val w = W_DP * density * scale
        val k = w / 14f                         // the path spans x 5..19, y 2..22 in a 24-unit viewport
        val h = 20f * k
        val bmp = Bitmap.createBitmap((w + 2 * pad).toInt() + 1, (h + 2 * pad).toInt() + 1, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (alpha < 255) c.saveLayerAlpha(null, alpha)
        val m = Matrix().apply { postTranslate(-5f, -2f); postScale(k, k); postTranslate(pad, pad) }

        val body = Path().apply {
            moveTo(12f, 2f)
            cubicTo(8.13f, 2f, 5f, 5.13f, 5f, 9f)
            cubicTo(5f, 14.25f, 12f, 22f, 12f, 22f)
            cubicTo(12f, 22f, 19f, 14.25f, 19f, 9f)
            cubicTo(19f, 5.13f, 15.87f, 2f, 12f, 2f)
            close()
            transform(m)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (ring != null) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f * density * scale
            paint.color = ring
            c.drawPath(body, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = fill
        paint.setShadowLayer(2.5f * density, 0f, 1.5f * density, 0x55000000)
        c.drawPath(body, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * density * scale
        paint.color = 0xFFFFFFFF.toInt()
        c.drawPath(body, paint)

        // Glyph in a 10×10 box centred on the pin's head (viewport 12,9).
        val g = Matrix().apply { postTranslate(7f, 4f); postConcat(m) }
        val glyphPath = Path().apply {
            when (glyph) {
                Glyph.BOLT -> { moveTo(5.9f, 0f); lineTo(2.1f, 5.7f); lineTo(4.8f, 5.7f); lineTo(4.1f, 10f); lineTo(7.9f, 4.3f); lineTo(5.2f, 4.3f); lineTo(5.9f, 0f); close() }
                Glyph.PLUG -> {
                    addRoundRect(RectF(2f, 3.2f, 8f, 8.1f), 1.1f, 1.1f, Path.Direction.CW)
                    addRect(3.4f, 0.6f, 4.6f, 3.2f, Path.Direction.CW)
                    addRect(5.4f, 0.6f, 6.6f, 3.2f, Path.Direction.CW)
                    addRect(4.5f, 8.1f, 5.5f, 10f, Path.Direction.CW)
                }
                Glyph.DOT -> addCircle(5f, 5f, 2.6f, Path.Direction.CW)
                Glyph.BANG -> { addRoundRect(RectF(4.1f, 0.8f, 5.9f, 6.3f), 0.9f, 0.9f, Path.Direction.CW); addCircle(5f, 8.6f, 1.2f, Path.Direction.CW) }
            }
            transform(g)
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        c.drawPath(glyphPath, paint)
        if (alpha < 255) c.restore()
        return bmp
    }

    /** Colour and glyph for a charger pin. */
    fun fillFor(fast: Boolean, outOfService: Boolean): Int = when {
        outOfService -> 0xFF8C96A0.toInt()
        fast -> 0xFFFFC43D.toInt()
        else -> 0xFF4DA3FF.toInt()
    }
    fun glyphFor(fast: Boolean) = if (fast) Glyph.BOLT else Glyph.PLUG

    const val DEST_FILL = 0xFFFF5A5F.toInt()
    const val RUNOUT_FILL = 0xFFFF4D4D.toInt()
    const val SELECT_RING = 0xFFFFFFFF.toInt()
    const val SKIP_ALPHA = 110
}
