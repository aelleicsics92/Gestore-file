package it.dalbrocante.gestorefile.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File

enum class SignPos { BOTTOM_RIGHT, BOTTOM_LEFT, CENTER }

object PdfTools {

    fun init(ctx: Context) = PDFBoxResourceLoader.init(ctx.applicationContext)

    fun merge(sources: List<File>, target: File) {
        val m = PDFMergerUtility()
        sources.forEach { m.addSource(it) }
        m.destinationFileName = target.absolutePath
        m.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
    }

    fun pageCount(src: File): Int = PDDocument.load(src).use { it.numberOfPages }

    /** Intervalli tipo "1-3,5,8-10" (base 1). */
    fun parseRanges(spec: String, max: Int): List<Int> =
        spec.split(",").flatMap { part ->
            val p = part.trim()
            if (p.isEmpty()) emptyList()
            else if ("-" in p) {
                val (a, b) = p.split("-", limit = 2).map { it.trim().toInt() }
                (a..b).toList()
            } else listOf(p.toInt())
        }.filter { it in 1..max }

    fun extractPages(src: File, pages: List<Int>, target: File) {
        PDDocument.load(src).use { doc ->
            PDDocument().use { out ->
                pages.forEach { out.importPage(doc.getPage(it - 1)) }
                out.save(target)
            }
        }
    }

    /** Divide in un file per pagina. */
    fun splitAll(src: File, outDir: File): List<File> {
        outDir.mkdirs()
        val files = ArrayList<File>()
        PDDocument.load(src).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                val f = File(outDir, "${src.nameWithoutExtension}_p${i + 1}.pdf")
                PDDocument().use { out -> out.importPage(doc.getPage(i)); out.save(f) }
                files += f
            }
        }
        return files
    }

    /** Applica l'immagine della firma (PNG con trasparenza) sulla pagina indicata (base 1). */
    fun sign(src: File, signature: Bitmap, page: Int, pos: SignPos, target: File) {
        PDDocument.load(src).use { doc ->
            val pg = doc.getPage(page - 1)
            val w = pg.mediaBox.width; val h = pg.mediaBox.height
            val sw = w * 0.3f; val sh = sw * signature.height / signature.width
            val x = when (pos) { SignPos.BOTTOM_RIGHT -> w - sw - 36f; SignPos.BOTTOM_LEFT -> 36f; SignPos.CENTER -> (w - sw) / 2 }
            val y = when (pos) { SignPos.CENTER -> (h - sh) / 2; else -> 36f }
            val img = LosslessFactory.createFromImage(doc, signature)
            PDPageContentStream(doc, pg, PDPageContentStream.AppendMode.APPEND, true, true).use {
                it.drawImage(img, x, y, sw, sh)
            }
            doc.save(target)
        }
    }

    /** Rende il tracciato della firma in un bitmap trasparente ritagliato. */
    fun renderSignature(strokes: List<List<Pair<Float, Float>>>): Bitmap? {
        val pts = strokes.flatten()
        if (pts.size < 2) return null
        val minX = pts.minOf { it.first } - 8; val maxX = pts.maxOf { it.first } + 8
        val minY = pts.minOf { it.second } - 8; val maxY = pts.maxOf { it.second } + 8
        val bmp = Bitmap.createBitmap((maxX - minX).toInt().coerceAtLeast(1), (maxY - minY).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        strokes.forEach { s ->
            if (s.size < 2) return@forEach
            val p = Path()
            p.moveTo(s[0].first - minX, s[0].second - minY)
            s.drop(1).forEach { p.lineTo(it.first - minX, it.second - minY) }
            c.drawPath(p, paint)
        }
        return bmp
    }
}
