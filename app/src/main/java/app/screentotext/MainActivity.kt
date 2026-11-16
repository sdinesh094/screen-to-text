package app.screentotext

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.SurfaceHolder
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.IImageCapture
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import com.serenegiant.widget.AspectRatioSurfaceView
import java.io.File
import java.util.concurrent.Executors

/**
 * One screen, three steps:
 *   1. show the live feed from the USB webcam
 *   2. Capture freezes one frame as a photo
 *   3. the photo is read into text (on the phone, or with Cloud AI)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var cameraView: AspectRatioSurfaceView
    private lateinit var capturedView: ImageView
    private lateinit var statusText: TextView
    private lateinit var captureButton: Button
    private lateinit var resolutionButton: Button
    private lateinit var cloudSwitch: SwitchMaterial
    private lateinit var resultText: EditText
    private lateinit var progress: ProgressBar
    private lateinit var rerunButton: Button
    private lateinit var copyButton: Button

    private var cameraHelper: ICameraHelper? = null
    private var cameraOpen = false

    /** The resolution we last asked the webcam for. */
    private var activeSize: Size? = null
    private var started = false

    /** The photo currently shown; null while the live feed is showing. */
    private var captured: Bitmap? = null

    /** Goes up by one for every read, so a slow old answer is ignored. */
    private var ocrRun = 0

    private val background = Executors.newSingleThreadExecutor()

    private val askCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (started) initCamera()
            } else {
                setStatus("Camera permission is needed to open the USB webcam")
            }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) loadPickedImage(uri)
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        cameraView = findViewById(R.id.cameraView)
        capturedView = findViewById(R.id.capturedView)
        statusText = findViewById(R.id.statusText)
        captureButton = findViewById(R.id.captureButton)
        resolutionButton = findViewById(R.id.resolutionButton)
        cloudSwitch = findViewById(R.id.cloudSwitch)
        resultText = findViewById(R.id.resultText)
        progress = findViewById(R.id.progress)
        rerunButton = findViewById(R.id.rerunButton)
        copyButton = findViewById(R.id.copyButton)

        cameraView.setAspectRatio(16, 9)
        cameraView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (cameraOpen) cameraHelper?.addSurface(holder.surface, false)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                cameraHelper?.removeSurface(holder.surface)
            }
        })

        captureButton.setOnClickListener {
            if (captured != null) showLive() else capture()
        }
        findViewById<Button>(R.id.pickButton).setOnClickListener { pickImage.launch("image/*") }
        resolutionButton.setOnClickListener { showResolutionDialog() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettingsDialog() }
        rerunButton.setOnClickListener { captured?.let { runOcr(it) } }
        copyButton.setOnClickListener { copyText() }

        cloudSwitch.isChecked = prefs.useCloud
        cloudSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.useCloud = checked
            if (checked && prefs.apiKey.isEmpty()) showSettingsDialog()
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (hasCameraPermission()) {
            initCamera()
        } else {
            askCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStop() {
        super.onStop()
        started = false
        releaseCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        background.shutdownNow()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------- camera

    private fun initCamera() {
        if (cameraHelper != null) return
        val helper = CameraHelper()
        helper.setStateCallback(cameraCallback)
        cameraHelper = helper
        setStatus(getString(R.string.waiting_for_camera))
    }

    private fun releaseCamera() {
        cameraOpen = false
        cameraHelper?.release()
        cameraHelper = null
        updateButtons()
    }

    // The library delivers all of these on the main thread.
    private val cameraCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice?) {
            // Asks Android for USB permission the first time, then opens the device.
            setStatus("Webcam found, connecting…")
            if (device != null) cameraHelper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
            cameraHelper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice?) {
            val helper = cameraHelper ?: return
            // Camera calls are queued in order, so: set size, then start.
            val wanted = chooseSize(helper.supportedSizeList)
            if (wanted != null) helper.setPreviewSize(wanted)
            helper.startPreview()
            cameraOpen = true

            val size = wanted ?: helper.previewSize
            activeSize = size
            showLiveStatus()
            if (size != null) cameraView.setAspectRatio(size.width, size.height)

            val surface = cameraView.holder.surface
            if (surface != null && surface.isValid) helper.addSurface(surface, false)
            updateButtons()
        }

        override fun onCameraClose(device: UsbDevice?) {
            cameraOpen = false
            cameraHelper?.removeSurface(cameraView.holder.surface)
            updateButtons()
        }

        override fun onDeviceClose(device: UsbDevice?) {}

        override fun onDetach(device: UsbDevice?) {
            cameraOpen = false
            if (captured == null) setStatus(getString(R.string.waiting_for_camera))
            updateButtons()
        }

        override fun onCancel(device: UsbDevice?) {
            setStatus("USB permission was denied. Re-plug the webcam and allow it.")
        }

        override fun onError(device: UsbDevice?, e: CameraException?) {
            cameraOpen = false
            // A resolution the webcam could not deliver must not stick around.
            prefs.cameraSize = ""
            setStatus("Could not open the webcam. Unplug it and plug it in again.")
            updateButtons()
        }
    }

    private fun showLiveStatus() {
        if (captured != null) return
        val size = activeSize
        setStatus(
            when {
                !cameraOpen -> getString(R.string.waiting_for_camera)
                size != null -> "Live  ${size.width}×${size.height}"
                else -> "Live"
            }
        )
    }

    /**
     * Saved choice if the webcam still offers it. Otherwise the sharpest size
     * up to 1920×1080, which every phone can handle. Higher sizes can be
     * picked by hand with the Resolution button.
     */
    private fun chooseSize(sizes: List<Size>?): Size? {
        if (sizes.isNullOrEmpty()) return null
        val saved = prefs.cameraSize
        if (saved.isNotEmpty()) {
            sizes.firstOrNull { key(it) == saved }?.let { return it }
        }
        val safe = sizes.filter { it.width * it.height <= 1920 * 1080 }
        val pool = safe.ifEmpty { sizes }
        return if (safe.isEmpty()) {
            pool.minByOrNull { it.width * it.height }
        } else {
            // Same pixel count: prefer MJPEG, it keeps the frame rate up over USB.
            pool.sortedWith(
                compareByDescending<Size> { it.width * it.height }
                    .thenByDescending { it.type == UVCCamera.UVC_VS_FRAME_MJPEG }
            ).first()
        }
    }

    private fun key(size: Size) = "${size.type}:${size.width}:${size.height}"

    private fun label(size: Size): String {
        val format = if (size.type == UVCCamera.UVC_VS_FRAME_MJPEG) "MJPEG" else "YUV"
        return "${size.width} × ${size.height}   $format"
    }

    private fun showResolutionDialog() {
        val helper = cameraHelper ?: return
        val sizes = (helper.supportedSizeList ?: emptyList())
            .distinctBy { key(it) }
            .sortedByDescending { it.width * it.height }
        if (sizes.isEmpty()) return
        val current = activeSize
        val checked = sizes.indexOfFirst {
            current != null && it.width == current.width &&
                it.height == current.height && it.type == current.type
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.resolution)
            .setSingleChoiceItems(sizes.map { label(it) }.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                applySize(sizes[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applySize(size: Size) {
        val helper = cameraHelper ?: return
        if (!cameraOpen) return
        prefs.cameraSize = key(size)
        helper.stopPreview()
        helper.setPreviewSize(size)
        helper.startPreview()
        activeSize = size
        cameraView.setAspectRatio(size.width, size.height)
        showLiveStatus()
    }

    // ------------------------------------------------------------------ capture

    private fun capture() {
        val helper = cameraHelper
        if (helper == null || !cameraOpen) {
            toast(getString(R.string.waiting_for_camera))
            return
        }
        captureButton.isEnabled = false
        val file = File(cacheDir, "capture.jpg")
        val options = IImageCapture.OutputFileOptions.Builder(file).build()
        helper.takePicture(options, object : IImageCapture.OnImageCaptureCallback {
            override fun onImageSaved(outputFileResults: IImageCapture.OutputFileResults) {
                runOnUiThread {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap == null) {
                        toast("Could not read the captured photo")
                        updateButtons()
                    } else {
                        showCaptured(bitmap)
                    }
                }
            }

            override fun onError(imageCaptureError: Int, message: String, cause: Throwable?) {
                runOnUiThread {
                    toast("Capture failed: $message")
                    updateButtons()
                }
            }
        })
    }

    private fun loadPickedImage(uri: Uri) {
        background.execute {
            val bitmap = try {
                decodeScaled(uri)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (bitmap == null) toast("Could not open that image") else showCaptured(bitmap)
            }
        }
    }

    /** Opens a gallery image, shrunk if it is huge so it fits in memory. */
    private fun decodeScaled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4000) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun showCaptured(bitmap: Bitmap) {
        captured = bitmap
        capturedView.setImageBitmap(bitmap)
        capturedView.visibility = View.VISIBLE
        setStatus("Photo  ${bitmap.width}×${bitmap.height}")
        updateButtons()
        runOcr(bitmap)
    }

    private fun showLive() {
        ocrRun++ // drop any answer still on its way
        captured = null
        capturedView.visibility = View.GONE
        capturedView.setImageDrawable(null)
        progress.visibility = View.GONE
        showLiveStatus()
        updateButtons()
    }

    // ---------------------------------------------------------------------- OCR

    private fun runOcr(bitmap: Bitmap) {
        val run = ++ocrRun
        val useCloud = cloudSwitch.isChecked
        if (useCloud && prefs.apiKey.isEmpty()) {
            toast("Add your API key in Settings to use Cloud AI")
            showSettingsDialog()
            return
        }

        progress.visibility = View.VISIBLE
        rerunButton.isEnabled = false
        resultText.setText("")

        if (useCloud) {
            val apiKey = prefs.apiKey
            val model = prefs.model
            background.execute {
                try {
                    val text = CloudOcr.run(bitmap, apiKey, model)
                    runOnUiThread { showResult(run, text) }
                } catch (e: Exception) {
                    runOnUiThread { showFailure(run, e.message ?: e.toString()) }
                }
            }
        } else {
            LocalOcr.run(
                bitmap,
                onDone = { text -> showResult(run, text) },
                onError = { e -> showFailure(run, e.message ?: e.toString()) }
            )
        }
    }

    private fun showResult(run: Int, text: String) {
        if (run != ocrRun) return
        progress.visibility = View.GONE
        if (text.isBlank()) {
            toast("No text found. Try a sharper photo, less glare, or Cloud AI.")
        }
        resultText.setText(text)
        updateButtons()
    }

    private fun showFailure(run: Int, message: String) {
        if (run != ocrRun) return
        progress.visibility = View.GONE
        updateButtons()
        AlertDialog.Builder(this)
            .setTitle("Could not read the photo")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ----------------------------------------------------------------- UI bits

    private fun updateButtons() {
        val hasPhoto = captured != null
        captureButton.setText(if (hasPhoto) R.string.retake else R.string.capture)
        captureButton.isEnabled = hasPhoto || cameraOpen
        resolutionButton.isEnabled = cameraOpen && !hasPhoto
        rerunButton.isEnabled = hasPhoto && progress.visibility != View.VISIBLE
        copyButton.isEnabled = resultText.text.isNotEmpty()
    }

    private fun copyText() {
        val text = resultText.text.toString()
        if (text.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Screen to Text", text))
        toast("Copied")
    }

    private fun showSettingsDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val keyField = EditText(this).apply {
            hint = getString(R.string.api_key_label)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.apiKey)
        }
        val modelField = EditText(this).apply {
            hint = getString(R.string.model_label)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(prefs.model)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply { text = getString(R.string.api_key_label) })
            addView(keyField)
            addView(TextView(context).apply {
                text = getString(R.string.model_label)
                setPadding(0, pad / 2, 0, 0)
            })
            addView(modelField)
        }
        AlertDialog.Builder(this)
            .setTitle("Cloud AI settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.apiKey = keyField.text.toString()
                prefs.model = modelField.text.toString()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setStatus(message: String) {
        statusText.text = message
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
