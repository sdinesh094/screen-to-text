package app.screentotext

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows the captured photo. Drag a box on it to choose the part to read;
 * a single tap clears the box. Reports the box in photo pixels.
 */
class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    /** Called with the chosen area in photo pixels, or null when cleared. */
    var onSelection: ((Rect?) -> Unit)? = null

    private var startX = 0f
    private var startY = 0f
    private var box: RectF? = null
    private val density = resources.displayMetrics.density

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.parseColor("#FFEB3B")
    }
    private val shade = Paint().apply { color = Color.parseColor("#88000000") }

    fun clearSelection() {
        box = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startX = event.x
                startY = event.y
                box = null
            }
            MotionEvent.ACTION_MOVE -> {
                box = RectF(
                    min(startX, event.x), min(startY, event.y),
                    max(startX, event.x), max(startY, event.y)
                )
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val small = 24 * density
                if (abs(event.x - startX) < small || abs(event.y - startY) < small) {
                    box = null
                    invalidate()
                    onSelection?.invoke(null)
                } else {
                    onSelection?.invoke(toPhotoPixels(box!!))
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                box = null
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = box ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, b.top, shade)
        canvas.drawRect(0f, b.bottom, w, h, shade)
        canvas.drawRect(0f, b.top, b.left, b.bottom, shade)
        canvas.drawRect(b.right, b.top, w, b.bottom, shade)
        canvas.drawRect(b, border)
    }

    /** Maps a box on screen to the matching area of the photo. */
    private fun toPhotoPixels(onScreen: RectF): Rect? {
        val d = drawable ?: return null
        val inverse = Matrix()
        if (!imageMatrix.invert(inverse)) return null
        val r = RectF(onScreen)
        r.offset(-paddingLeft.toFloat(), -paddingTop.toFloat())
        inverse.mapRect(r)
        // The drawable may be density-scaled; convert to real photo pixels.
        val bitmap = (d as? BitmapDrawable)?.bitmap ?: return null
        val sx = bitmap.width.toFloat() / max(1, d.intrinsicWidth)
        val sy = bitmap.height.toFloat() / max(1, d.intrinsicHeight)
        val out = Rect(
            (r.left * sx).roundToInt().coerceIn(0, bitmap.width),
            (r.top * sy).roundToInt().coerceIn(0, bitmap.height),
            (r.right * sx).roundToInt().coerceIn(0, bitmap.width),
            (r.bottom * sy).roundToInt().coerceIn(0, bitmap.height),
        )
        return if (out.width() < 8 || out.height() < 8) null else out
    }
}
