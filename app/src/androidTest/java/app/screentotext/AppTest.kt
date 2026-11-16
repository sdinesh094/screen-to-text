package app.screentotext

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AppTest {

    @get:Rule
    val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    /** The main screen opens and stays open with no webcam attached. */
    @Test
    fun appLaunchesWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(4000)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    /** On-phone reading gets the words AND keeps code indentation. */
    @Test
    fun localOcrReadsCodeAndKeepsIndentation() {
        val code = listOf(
            "def greet(name):",
            "    if name:",
            "        return name.upper()",
            "    return None",
        )
        val bitmap = render(code)

        var result: String? = null
        var error: Exception? = null
        val done = CountDownLatch(1)
        LocalOcr.run(
            bitmap,
            onDone = { result = it; done.countDown() },
            onError = { error = it; done.countDown() },
        )
        assertTrue("OCR timed out", done.await(60, TimeUnit.SECONDS))
        val text = result ?: throw AssertionError("OCR failed", error)
        Log.i("OCR_TEST", "\n----- OCR OUTPUT -----\n$text\n----------------------")

        val lines = text.lines()
        fun indentOf(fragment: String): Int {
            val line = lines.firstOrNull { it.contains(fragment) }
                ?: throw AssertionError("'$fragment' not found in:\n$text")
            return line.length - line.trimStart().length
        }
        val top = indentOf("greet")
        val ifLine = indentOf("if name")
        val inner = indentOf("upper")
        assertTrue("'if' should be indented under 'def':\n$text", ifLine > top)
        assertTrue("'return name.upper()' should be indented under 'if':\n$text", inner > ifLine)
    }

    /** Draws black monospace text on white, like code on a screen. */
    private fun render(lines: List<String>): Bitmap {
        val bitmap = Bitmap.createBitmap(1600, 700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 48f
            typeface = Typeface.MONOSPACE
        }
        lines.forEachIndexed { i, line -> canvas.drawText(line, 80f, 140f + i * 90f, paint) }
        return bitmap
    }
}
