package app.screentotext

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Cloud text reading with the Claude API. More accurate on glare, small
 * text, tables and diagrams, but needs internet and your own API key.
 *
 * Call [run] from a background thread. It blocks until the answer arrives.
 */
object CloudOcr {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MAX_EDGE = 2000

    private const val PROMPT =
        "This is a webcam photo of a laptop screen or a whiteboard. " +
            "Extract everything written in it, exactly as shown.\n" +
            "Rules:\n" +
            "- Output only the extracted content. No introduction, no comments.\n" +
            "- Keep the original line breaks and reading order.\n" +
            "- Put code in fenced code blocks and keep the exact indentation and symbols.\n" +
            "- Write tables as Markdown tables.\n" +
            "- For a diagram, list each box or label on its own line, then each " +
            "connection as `A -> B` (add the arrow label if there is one).\n" +
            "- Do not fix spelling and do not guess. Write [unclear] where you cannot read something.\n" +
            "- Ignore anything outside the screen or whiteboard."

    fun run(bitmap: Bitmap, apiKey: String, model: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put(
                "messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content", JSONArray()
                                .put(
                                    JSONObject()
                                        .put("type", "image")
                                        .put(
                                            "source", JSONObject()
                                                .put("type", "base64")
                                                .put("media_type", "image/jpeg")
                                                .put("data", toBase64Jpeg(bitmap))
                                        )
                                )
                                .put(JSONObject().put("type", "text").put("text", PROMPT))
                        )
                )
            )

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val message = try {
                    JSONObject(response).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    response.take(300)
                }
                throw RuntimeException("Cloud AI error $code: $message")
            }

            val content = JSONObject(response).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.getJSONObject(i)
                if (part.optString("type") == "text") sb.append(part.optString("text"))
            }
            return sb.toString().trim()
        } finally {
            conn.disconnect()
        }
    }

    /** Shrinks very large photos (faster upload, lower cost) and encodes them. */
    private fun toBase64Jpeg(source: Bitmap): String {
        val longEdge = max(source.width, source.height)
        val bitmap = if (longEdge > MAX_EDGE) {
            val scale = MAX_EDGE.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt(),
                (source.height * scale).toInt(),
                true
            )
        } else {
            source
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
