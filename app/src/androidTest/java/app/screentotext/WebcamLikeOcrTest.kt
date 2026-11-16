package app.screentotext

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Imitates a webcam photo of a dark-mode coding page (problem text on the
 * left, code on the right): small letters, blur, noise, glare. Measures how
 * much text comes out right, before and after the improvements.
 */
@RunWith(AndroidJUnit4::class)
class WebcamLikeOcrTest {

    private val prose = listOf(
        "Given a weighted, directed graph, and a starting",
        "vertex, return the shortest distance from the",
        "starting vertex to every vertex in the graph.",
        "n is the number of vertices in the graph.",
        "edges is a list of tuples, each a directed edge.",
    )
    private val code = listOf(
        "class Solution:",
        "    def shortestPath(self, n, edges, src):",
        "        adj = {i: [] for i in range(n)}",
        "        for s, d, w in edges:",
        "            adj[s].append((d, w))",
        "        shortest = {}",
        "        heap = [(0, src)]",
        "        return shortest",
    )

    @Test
    fun webcamLikePhotoReadsBetterAfterImprovements() {
        val photo = webcamLikePhoto()
        val all = (prose + code).joinToString(" ")
        val codeOnly = code.joinToString(" ")
        val codePanel = Bitmap.createBitmap(photo, photo.width / 2, 0, photo.width / 2, photo.height)

        val fullBefore = ocr(photo, enhance = false)
        val fullAfter = ocr(photo, enhance = true)
        val cropBefore = ocr(codePanel, enhance = false)
        val cropAfter = ocr(codePanel, enhance = true)

        val sFullBefore = similarity(fullBefore, all)
        val sFullAfter = similarity(fullAfter, all)
        val sCropBefore = similarity(cropBefore, codeOnly)
        val sCropAfter = similarity(cropAfter, codeOnly)

        Log.i(
            "OCR_TEST",
            "\n===== WEBCAM-LIKE TEST =====" +
                "\nWhole photo, old reader : ${pct(sFullBefore)}" +
                "\nWhole photo, new reader : ${pct(sFullAfter)}" +
                "\nCode box,    old reader : ${pct(sCropBefore)}" +
                "\nCode box,    new reader : ${pct(sCropAfter)}" +
                "\n--- whole photo, old ---\n$fullBefore" +
                "\n--- whole photo, new ---\n$fullAfter" +
                "\n--- code box, new ---\n$cropAfter" +
                "\n============================"
        )

        assertTrue("New reader is worse on the whole photo", sFullAfter >= sFullBefore - 0.02)
        assertTrue("New reader is worse on the code box", sCropAfter >= sCropBefore - 0.02)
    }

    // ------------------------------------------------------------------ helpers

    private fun ocr(bitmap: Bitmap, enhance: Boolean): String {
        var out: String? = null
        var err: Exception? = null
        val done = CountDownLatch(1)
        LocalOcr.run(
            bitmap,
            onDone = { out = it; done.countDown() },
            onError = { err = it; done.countDown() },
            enhance = enhance,
        )
        assertTrue("OCR timed out", done.await(90, TimeUnit.SECONDS))
        return out ?: throw AssertionError("OCR failed", err)
    }

    /** Draws the page sharp, then degrades it the way a webcam does. */
    private fun webcamLikePhoto(): Bitmap {
        // Sharp page, as on the laptop
        val page = Bitmap.createBitmap(2560, 1440, Bitmap.Config.ARGB_8888)
        val c = Canvas(page)
        c.drawColor(Color.parseColor("#1A1A1A"))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f }
        text.color = Color.parseColor("#D0D0D0")
        text.typeface = Typeface.SANS_SERIF
        prose.forEachIndexed { i, s -> c.drawText(s, 60f, 200f + i * 48f, text) }
        text.typeface = Typeface.MONOSPACE
        text.color = Color.parseColor("#CFE3FF")
        code.forEachIndexed { i, s -> c.drawText(s, 1340f, 200f + i * 48f, text) }

        // Webcam: whole screen squeezed into 1280x720, slightly out of focus
        val soft = Bitmap.createScaledBitmap(page, 900, 506, true)
        val photo = Bitmap.createScaledBitmap(soft, 1280, 720, true)
            .copy(Bitmap.Config.ARGB_8888, true)

        // Glare from the top-left, and sensor noise
        val glare = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 1280f, 720f,
                Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
        }
        Canvas(photo).drawRect(0f, 0f, 1280f, 720f, glare)
        val px = IntArray(1280 * 720)
        photo.getPixels(px, 0, 1280, 0, 0, 1280, 720)
        val rnd = Random(7)
        for (i in px.indices) {
            val n = rnd.nextInt(25) - 12
            val p = px[i]
            val r = (((p shr 16) and 0xFF) + n).coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) + n).coerceIn(0, 255)
            val b = ((p and 0xFF) + n).coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        photo.setPixels(px, 0, 1280, 0, 0, 1280, 720)
        return photo
    }

    /** Share of characters right: 1 - edit distance / length, spaces ignored. */
    private fun similarity(got: String, want: String): Double {
        val a = got.filter { !it.isWhitespace() }
        val b = want.filter { !it.isWhitespace() }
        if (b.isEmpty()) return 0.0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            prev = cur
        }
        return max(0.0, 1.0 - prev[b.length].toDouble() / b.length)
    }

    private fun pct(v: Double) = "${(v * 100).toInt()}%"
}
