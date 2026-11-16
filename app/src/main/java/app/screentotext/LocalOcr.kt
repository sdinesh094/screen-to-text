package app.screentotext

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-phone text reading with Google ML Kit. Free, offline, private.
 *
 * Webcam photos of screens are hard: letters are small and soft, dark-mode
 * screens are light-on-dark, and glare washes out contrast. So each photo is
 * read twice, as it is and after [enhance] cleans it up, and the reading the
 * recognizer is more confident about wins.
 *
 * ML Kit returns loose pieces of text with their position in the photo.
 * [layout] puts them back in order: side-by-side panels one after the other,
 * code with its indentation, table columns lined up.
 */
object LocalOcr {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Reads [bitmap]. Callbacks arrive on the main thread. */
    fun run(
        bitmap: Bitmap,
        onDone: (String) -> Unit,
        onError: (Exception) -> Unit,
        enhance: Boolean = true,
    ) {
        worker.execute {
            try {
                val text = if (enhance) bestOf(bitmap) else read(bitmap).first
                main.post { onDone(text) }
            } catch (e: Exception) {
                main.post { onError(e) }
            }
        }
    }

    // ------------------------------------------------------------- recognition

    /** Which version won the last read, and the scores. For tests and tuning. */
    @Volatile
    var lastChoice = ""

    private fun bestOf(bitmap: Bitmap): String {
        val plain = read(bitmap)
        val cleaned = read(enhance(bitmap))
        val useCleaned = cleaned.second >= plain.second
        lastChoice = "plain=%.1f cleaned=%.1f -> %s".format(
            plain.second, cleaned.second, if (useCleaned) "cleaned" else "plain"
        )
        return if (useCleaned) cleaned.first else plain.first
    }

    /** Reads one exact image on the calling (background) thread. For tests. */
    fun readNow(bitmap: Bitmap): String = read(bitmap).first

    /** Text plus a score: confidence-weighted count of letters and digits found. */
    private fun read(bitmap: Bitmap): Pair<String, Float> {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        var score = 0f
        for (block in result.textBlocks) for (line in block.lines) for (el in line.elements) {
            score += el.confidence * el.text.count { it.isLetterOrDigit() }
        }
        val text = try {
            layout(result)
        } catch (e: Exception) {
            result.text
        }
        return text to score
    }

    // ---------------------------------------------------------------- clean-up

    /**
     * Makes small, soft text easier to read: enlarge, turn grey, and flip
     * light-on-dark screens to dark-on-light.
     */
    fun enhance(
        src: Bitmap,
        enlarge: Boolean = true,
        flipDark: Boolean = true,
        // Tested on webcam-like photos: these two amplify camera noise and
        // make reading worse, so they are off by default.
        stretch: Boolean = false,
        sharpen: Boolean = false,
    ): Bitmap {
        // 1. Enlarge. The recognizer misses letters only a few pixels tall.
        val longEdge = max(src.width, src.height)
        val scale = when {
            longEdge >= 3000 -> 1f
            else -> min(2.5f, 3000f / longEdge)
        }
        val big = if (enlarge && scale > 1.05f) {
            Bitmap.createScaledBitmap(
                src, (src.width * scale).roundToInt(), (src.height * scale).roundToInt(), true
            )
        } else {
            src
        }

        val w = big.width
        val h = big.height
        val px = IntArray(w * h)
        big.getPixels(px, 0, w, 0, 0, w, h)

        // 2. Grey levels.
        val y = IntArray(w * h)
        val histogram = IntArray(256)
        var sum = 0L
        for (i in px.indices) {
            val c = px[i]
            val v = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            y[i] = v
            histogram[v]++
            sum += v
        }

        // 3. Dark-mode screen: flip to dark text on a light page.
        val dark = flipDark && sum / y.size < 110
        if (dark) {
            for (i in y.indices) y[i] = 255 - y[i]
            histogram.reverse()
        }

        // 4. Stretch contrast between the 1st and 99th percentile.
        val lo = percentile(histogram, y.size, 0.01)
        val hi = percentile(histogram, y.size, 0.99)
        val range = max(1, hi - lo)
        if (stretch) for (i in y.indices) y[i] = ((y[i] - lo) * 255 / range).coerceIn(0, 255)

        // 5. Mild sharpening: push each pixel away from its neighbours' average.
        val out = IntArray(w * h)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val i = row * w + col
                val v = if (!sharpen || row == 0 || col == 0 || row == h - 1 || col == w - 1) {
                    y[i]
                } else {
                    val avg = (y[i - 1] + y[i + 1] + y[i - w] + y[i + w]) / 4
                    (y[i] + (y[i] - avg)).coerceIn(0, 255)
                }
                out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Double): Int {
        val target = (total * fraction).toLong()
        var seen = 0L
        for (v in 0..255) {
            seen += histogram[v]
            if (seen > target) return v
        }
        return 255
    }

    // ------------------------------------------------------------------ layout

    private class Piece(val text: String, val box: Rect)

    private fun layout(result: Text): String {
        val pieces = ArrayList<Piece>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (line.text.isNotBlank()) pieces.add(Piece(line.text, box))
            }
        }
        if (pieces.isEmpty()) return ""

        val charWidth = max(
            1f,
            median(pieces.map { it.box.width().toFloat() / max(1, it.text.length) })
        )
        return splitIntoPanels(pieces, charWidth)
            .joinToString("\n\n") { layoutPanel(it, charWidth) }
            .trimEnd()
    }

    /**
     * Finds side-by-side panels (problem text | code) by looking for a wide
     * vertical strip that no line of text crosses, and returns them left to right.
     */
    private fun splitIntoPanels(pieces: List<Piece>, charWidth: Float): List<List<Piece>> {
        val left = pieces.minOf { it.box.left }
        val right = pieces.maxOf { it.box.right }
        val width = right - left
        if (width <= 0) return listOf(pieces)

        val covered = BooleanArray(width + 1)
        for (p in pieces) {
            for (x in (p.box.left - left).coerceAtLeast(0)..(p.box.right - left).coerceAtMost(width)) {
                covered[x] = true
            }
        }

        val minGap = max(charWidth * 6, width * 0.04f)
        val cuts = ArrayList<Int>()
        var gapStart = -1
        for (x in 0..width) {
            if (!covered[x]) {
                if (gapStart < 0) gapStart = x
            } else if (gapStart >= 0) {
                if (x - gapStart >= minGap) cuts.add(left + (gapStart + x) / 2)
                gapStart = -1
            }
        }
        if (cuts.isEmpty()) return listOf(pieces)

        val panels = List(cuts.size + 1) { ArrayList<Piece>() }
        for (p in pieces) {
            val index = cuts.count { p.box.centerX() > it }
            panels[index].add(p)
        }
        return panels.filter { it.isNotEmpty() }
    }

    private fun layoutPanel(pieces: List<Piece>, charWidth: Float): String {
        val lineHeight = median(pieces.map { it.box.height().toFloat() })
        val leftEdge = pieces.minOf { it.box.left }

        // Group pieces that sit at the same height into one row.
        val rows = ArrayList<MutableList<Piece>>()
        var rowCenter = 0f
        for (p in pieces.sortedBy { it.box.centerY() }) {
            val cy = p.box.centerY().toFloat()
            val row = rows.lastOrNull()
            if (row != null && cy - rowCenter < lineHeight * 0.6f) {
                row.add(p)
                rowCenter = row.map { it.box.centerY() }.average().toFloat()
            } else {
                rows.add(mutableListOf(p))
                rowCenter = cy
            }
        }

        val sb = StringBuilder()
        var previousBottom = -1
        for (row in rows) {
            // A tall empty gap in the photo becomes one blank line.
            val top = row.minOf { it.box.top }
            if (previousBottom >= 0 && top - previousBottom > lineHeight * 1.2f) sb.append('\n')
            previousBottom = row.maxOf { it.box.bottom }

            val line = StringBuilder()
            for (p in row.sortedBy { it.box.left }) {
                val column = ((p.box.left - leftEdge) / charWidth).roundToInt()
                if (line.isNotEmpty() && column <= line.length) {
                    line.append(' ')
                } else {
                    while (line.length < column) line.append(' ')
                }
                line.append(p.text)
            }
            sb.append(line.toString().trimEnd()).append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
