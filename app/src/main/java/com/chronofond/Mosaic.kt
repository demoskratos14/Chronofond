package com.chronofond

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/** Forme d'une case (ou d'une photo). */
enum class Shape { PORTRAIT, LANDSCAPE, SQUARE }

/** ratio = largeur / hauteur */
fun shapeOf(ratio: Float): Shape = when {
    ratio < 0.85f -> Shape.PORTRAIT
    ratio > 1.18f -> Shape.LANDSCAPE
    else -> Shape.SQUARE
}

/** Case en fractions (0..1) de la zone de la mosaïque. */
data class Cell(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h
}

class MosaicLayout(val id: String, val label: String, val cells: List<Cell>) {
    val count get() = cells.size
}

object Layouts {

    private sealed class Node { abstract val wt: Float }
    private class Leaf(override val wt: Float) : Node()
    private class Split(override val wt: Float, val vertical: Boolean, val kids: List<Node>) : Node()

    private fun leaf(wt: Float = 1f): Node = Leaf(wt)

    /** Empile de haut en bas. */
    private fun v(vararg k: Node, wt: Float = 1f): Node = Split(wt, true, k.toList())

    /** Place de gauche à droite. */
    private fun h(vararg k: Node, wt: Float = 1f): Node = Split(wt, false, k.toList())

    private fun layout(id: String, label: String, root: Node): MosaicLayout {
        val out = mutableListOf<Cell>()
        fun walk(n: Node, x: Float, y: Float, w: Float, hh: Float) {
            when (n) {
                is Leaf -> out += Cell(x, y, w, hh)
                is Split -> {
                    val total = n.kids.sumOf { it.wt.toDouble() }.toFloat()
                    var acc = 0f
                    n.kids.forEach { k ->
                        val f = k.wt / total
                        if (n.vertical) walk(k, x, y + acc * hh, w, f * hh)
                        else walk(k, x + acc * w, y, f * w, hh)
                        acc += f
                    }
                }
            }
        }
        walk(root, 0f, 0f, 1f, 1f)
        return MosaicLayout(id, label, out)
    }

    val all: List<MosaicLayout> = listOf(
        layout("1", "Plein écran", leaf()),

        layout("2-stack", "2 l'une au-dessus de l'autre", v(leaf(), leaf())),
        layout("2-side", "2 côte à côte", h(leaf(), leaf())),

        layout("3-stack", "3 empilées", v(leaf(), leaf(), leaf())),
        layout("3-top", "1 en haut + 2 dessous", v(leaf(), h(leaf(), leaf()))),
        layout("3-bottom", "2 en haut + 1 dessous", v(h(leaf(), leaf()), leaf())),
        layout("3-left", "1 à gauche + 2 à droite", h(leaf(), v(leaf(), leaf()))),

        layout("4-grid", "2 × 2", v(h(leaf(), leaf()), h(leaf(), leaf()))),
        layout("4-stack", "4 empilées", v(leaf(), leaf(), leaf(), leaf())),
        layout("4-big", "1 grande + 3", v(leaf(1.5f), h(leaf(), leaf(), leaf()))),

        layout(
            "5-3l2p", "3 paysage + 2 portrait",
            v(v(leaf(), leaf(), leaf(), wt = 0.55f), h(leaf(), leaf(), wt = 0.45f))
        ),
        layout("5-side", "3 à gauche + 2 à droite", h(v(leaf(), leaf(), leaf()), v(leaf(), leaf()))),
        layout(
            "5-big", "1 grande + 4",
            v(leaf(1.2f), h(v(leaf(), leaf()), v(leaf(), leaf())))
        ),

        layout("6-grid", "2 × 3", v(h(leaf(), leaf()), h(leaf(), leaf()), h(leaf(), leaf()))),
        layout("6-mix", "1 haut + 4 + 1 bas", v(leaf(), h(leaf(), leaf()), h(leaf(), leaf()), leaf())),

        layout(
            "7-big", "1 grande + 6",
            v(leaf(1.4f), h(leaf(), leaf()), h(leaf(), leaf()), h(leaf(), leaf()))
        ),
        layout(
            "7-side", "4 à gauche + 3 à droite",
            h(v(leaf(), leaf(), leaf(), leaf()), v(leaf(), leaf(), leaf()))
        ),

        layout(
            "8-grid", "2 × 4",
            v(h(leaf(), leaf()), h(leaf(), leaf()), h(leaf(), leaf()), h(leaf(), leaf()))
        ),
        layout(
            "8-mix", "1 haut + 6 + 1 bas",
            v(leaf(), h(leaf(), leaf()), h(leaf(), leaf()), h(leaf(), leaf()), leaf())
        ),
    )

    val counts: List<Int> = all.map { it.count }.distinct()

    fun byId(id: String): MosaicLayout = all.firstOrNull { it.id == id } ?: all.first()

    fun forCount(n: Int): List<MosaicLayout> = all.filter { it.count == n }

    /** Ancien réglage « nombre de photos » -> disposition équivalente. */
    fun legacy(grid: Int): String = when (grid) {
        2 -> "2-stack"
        4 -> "4-grid"
        8 -> "8-grid"
        else -> "1"
    }

    /** Cases en pixels pour une image w x h (espacement `gap` entre cases et sur les bords). */
    fun rects(layout: MosaicLayout, w: Int, h: Int, top: Int, gap: Int): List<RectF> {
        val half = gap / 2f
        val ax = half
        val ay = top + half
        val aw = w - gap.toFloat()
        val ah = h - top - gap.toFloat()
        return layout.cells.map { c ->
            RectF(
                ax + c.x * aw + half, ay + c.y * ah + half,
                ax + c.right * aw - half, ay + c.bottom * ah - half
            )
        }
    }
}

/** Rapport largeur/hauteur des photos (EXIF pris en compte), mis en cache. */
object PhotoRatios {
    private val mem = ConcurrentHashMap<String, Float>()

    fun of(ctx: Context, uri: String): Float {
        mem[uri]?.let { return it }
        val prefs = ctx.getSharedPreferences("ratios", Context.MODE_PRIVATE)
        if (prefs.contains(uri)) {
            val r = prefs.getFloat(uri, 1f)
            mem[uri] = r
            return r
        }
        val r = compute(ctx, Uri.parse(uri)) ?: return 1f
        prefs.edit().putFloat(uri, r).apply()
        mem[uri] = r
        return r
    }

    private fun compute(ctx: Context, uri: Uri): Float? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var w = opts.outWidth
            var h = opts.outHeight
            if (w <= 0 || h <= 0) return null
            val orientation = try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            if (orientation in listOf(
                    ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE
                )
            ) {
                val t = w; w = h; h = t
            }
            w.toFloat() / h.toFloat()
        } catch (e: Exception) {
            null
        }
    }
}

object Mosaic {

    /** Cases en pixels pour cette config d'écran (mêmes règles que le dessin). */
    fun cellRects(sc: ScreenConfig, w: Int, h: Int): List<RectF> {
        val single = sc.layout.count == 1
        val gap = if (single) 0 else sc.gapPx
        val top = if (single) 0 else h * sc.topMarginPct / 100
        return Layouts.rects(sc.layout, w, h, top, gap)
    }

    /** Dessine `uris` (une photo par case de la disposition) dans une image w x h. */
    fun render(ctx: Context, uris: List<String>, sc: ScreenConfig, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val rects = cellRects(sc, w, h)
        val radius = if (sc.layout.count > 1 && sc.gapPx > 0) 24f else 0f

        uris.forEachIndexed { i, s ->
            val dst = rects.getOrNull(i) ?: return@forEachIndexed
            val bmp = decode(ctx, Uri.parse(s), dst.width().toInt(), dst.height().toInt())
                ?: return@forEachIndexed
            canvas.save()
            if (radius > 0f) {
                val p = Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) }
                canvas.clipPath(p)
            }
            drawCover(canvas, bmp, dst, paint)
            canvas.restore()
            bmp.recycle()
        }
        return out
    }

    private fun decode(ctx: Context, uri: Uri, w: Int, h: Int): Bitmap? = try {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sample = max(1, min(info.size.width / max(1, w), info.size.height / max(1, h)))
            decoder.setTargetSampleSize(sample)
        }
    } catch (e: Exception) {
        null
    }

    /** Recadrage centré (comme ContentScale.Crop). */
    private fun drawCover(canvas: Canvas, bmp: Bitmap, dst: RectF, paint: Paint) {
        val scale = max(dst.width() / bmp.width, dst.height() / bmp.height)
        val sw = dst.width() / scale
        val sh = dst.height() / scale
        val sx = (bmp.width - sw) / 2f
        val sy = (bmp.height - sh) / 2f
        val src = Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt())
        canvas.drawBitmap(bmp, src, dst, paint)
    }
}
