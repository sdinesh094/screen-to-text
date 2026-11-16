package app.screentotext

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * On-phone text reading with Google ML Kit. Free, offline, private.
 *
 * ML Kit returns loose pieces of text with their position in the photo.
 * [layout] puts them back on a character grid, so code keeps its
 * indentation and table columns stay lined up.
 */
object LocalOcr {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun run(bitmap: Bitmap, onDone: (String) -> Unit, onError: (Exception) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val out = try {
                    layout(text)
                } catch (e: Exception) {
                    text.text
                }
                onDone(out)
            }
            .addOnFailureListener { e -> onError(e) }
    }

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

        val lineHeight = median(pieces.map { it.box.height().toFloat() })
        val charWidth = max(
            1f,
            median(pieces.map { it.box.width().toFloat() / max(1, it.text.length) })
        )
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
