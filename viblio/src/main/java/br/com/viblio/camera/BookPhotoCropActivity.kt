@file:Suppress("DEPRECATION")
package br.com.viblio.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.Camera
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import br.com.centralcasa.books.BookImages
import br.com.centralcasa.ui.DisplayUi
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Integrated camera + 4-corner perspective cropper for book covers and spines.
 * Camera lifetime is limited to this foreground screen; images stay in private storage.
 */
class BookPhotoCropActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        const val EXTRA_MODE = "br.com.viblio.photo.mode"
        const val EXTRA_SOURCE = "br.com.viblio.photo.source"
        const val EXTRA_BOOK_ID = "br.com.viblio.photo.book"
        const val EXTRA_PROFILE = "br.com.viblio.photo.profile"
        const val EXTRA_SAVED_IMAGE = "br.com.viblio.photo.saved"
        const val SOURCE_CAMERA = "camera"
        const val SOURCE_GALLERY = "gallery"
        private const val PICK_IMAGE = 41
        private const val CAMERA_PERMISSION = 42
        private const val MAX_SOURCE_BYTES = 20L * 1024 * 1024
        private const val MAX_WORKING_EDGE = 2048
        private const val MAX_PICTURE_PIXELS = 12_000_000L
        private const val DETECT_EDGE = 320
    }

    private lateinit var ui: DisplayUi
    private lateinit var mode: PhotoCropMode
    private lateinit var root: FrameLayout
    private var preview: SurfaceView? = null
    private var stage: FrameLayout? = null
    private var status: TextView? = null
    private var flashButton: TextView? = null
    private var shutter: View? = null
    private var cropView: PerspectiveCropView? = null
    private var camera: Camera? = null
    private var resumed = false
    private var surfaceReady = false
    private var cropping = false
    private var flashMode = Camera.Parameters.FLASH_MODE_AUTO
    private var working: Bitmap? = null
    private val capturing = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val sourceFile by lazy { File(File(cacheDir, "photo-crop").apply { mkdirs() }, "source.jpg") }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ui = DisplayUi(this)
        mode = PhotoCropMode.of(intent.getStringExtra(EXTRA_MODE))
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, i -> v.setPadding(i.systemWindowInsetLeft, i.systemWindowInsetTop, i.systemWindowInsetRight, i.systemWindowInsetBottom); i.consumeSystemWindowInsets() }
        root.requestApplyInsets()
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        val restoring = state?.getBoolean("photo.cropping") == true && sourceFile.isFile
        when {
            restoring -> loadSource(null)
            // Gallery never opens (or asks permission for) the camera; a recreated picker result is still delivered.
            fromGallery() -> { showBusy("Abrindo galeria…"); if (state == null) pickFromGallery() }
            else -> showCamera()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("photo.cropping", cropping || processing.get())
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); resumed = true; openCamera() }
    override fun onPause() { resumed = false; releaseCamera(); super.onPause() }
    override fun onDestroy() {
        releaseCamera(); worker.shutdownNow(); handler.removeCallbacksAndMessages(null)
        if (isFinishing) runCatching { sourceFile.delete() }
        super.onDestroy()
    }

    @Deprecated("Framework callback")
    override fun onBackPressed() {
        if (cropping && !fromGallery()) showCamera() else { setResult(RESULT_CANCELED); super.onBackPressed() }
    }

    private fun fromGallery() = intent.getStringExtra(EXTRA_SOURCE) == SOURCE_GALLERY

    private fun title() = if (mode == PhotoCropMode.COVER) "Foto da capa" else "Foto da lombada"

    private fun header(parent: LinearLayout, text: String, back: () -> Unit): LinearLayout {
        val row = ui.row().apply { setPadding(ui.dp(8), ui.dp(6), ui.dp(12), ui.dp(6)) }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        ui.icon(row, "back", "Voltar", 48) { back() }
        ui.label(row, text, 20, Color.WHITE).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        return row
    }

    // ---------- Camera stage ----------

    private fun showCamera() {
        cropping = false; cropView = null
        working = null
        root.removeAllViews()
        val column = ui.column()
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        val head = header(column, title()) { setResult(RESULT_CANCELED); finish() }
        flashButton = ui.action(head, flashLabel()) { cycleFlash() }.apply { visibility = View.GONE }
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        column.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        stage = frame
        val surface = SurfaceView(this)
        preview = surface
        frame.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        surface.holder.addCallback(this)
        frame.addView(GuideOverlay(), FrameLayout.LayoutParams(-1, -1))
        status = ui.label(column, if (mode == PhotoCropMode.COVER) "Enquadre a capa inteira" else "Enquadre a lombada na vertical", 14, 0xffb8b8b8.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(4)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val bar = ui.row().apply { gravity = Gravity.CENTER; setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16)) }
        column.addView(bar, LinearLayout.LayoutParams(-1, -2))
        ui.action(bar, "Galeria") { pickFromGallery() }.layoutParams = LinearLayout.LayoutParams(0, ui.dp(48), 1f)
        val button = View(this).apply {
            background = ShutterDrawable(); contentDescription = "Tirar foto"; isFocusable = true
            setOnClickListener { capture() }
        }
        shutter = button
        bar.addView(button, LinearLayout.LayoutParams(ui.dp(76), ui.dp(76)).apply { marginStart = ui.dp(12); marginEnd = ui.dp(12) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        }
        openCamera()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != CAMERA_PERMISSION) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
        else status?.text = "Permita a câmera ou escolha uma imagem da galeria."
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; openCamera() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; releaseCamera() }

    private fun displayOrientation(info: Camera.CameraInfo): Int {
        val degrees = when (windowManager.defaultDisplay.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (360 - (info.orientation + degrees) % 360) % 360 else (info.orientation - degrees + 360) % 360
    }

    private fun openCamera() {
        val surface = preview ?: return
        if (cropping || !resumed || !surfaceReady || camera != null) return
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            val id = (0 until Camera.getNumberOfCameras()).firstOrNull { Camera.CameraInfo().let { info -> Camera.getCameraInfo(it, info); info.facing == Camera.CameraInfo.CAMERA_FACING_BACK } } ?: 0
            val info = Camera.CameraInfo().also { Camera.getCameraInfo(id, it) }
            val device = Camera.open(id); camera = device
            val params = device.parameters
            val picture = params.supportedPictureSizes.filter { it.width.toLong() * it.height <= MAX_PICTURE_PIXELS }.maxByOrNull { it.width.toLong() * it.height }
                ?: params.supportedPictureSizes.minByOrNull { it.width.toLong() * it.height } ?: error("Picture unavailable")
            params.setPictureSize(picture.width, picture.height)
            val ratio = picture.width.toFloat() / picture.height
            val size = params.supportedPreviewSizes.filter { it.width.toLong() * it.height <= 1920L * 1080 }
                .minByOrNull { kotlin.math.abs(it.width.toFloat() / it.height - ratio) * 10_000 - it.width * it.height / 1000f } ?: error("Preview unavailable")
            params.setPreviewSize(size.width, size.height)
            params.pictureFormat = android.graphics.ImageFormat.JPEG
            params.jpegQuality = 92
            if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            else if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_AUTO) == true) params.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            val flashes = params.supportedFlashModes.orEmpty()
            if (flashMode !in flashes) flashMode = if (Camera.Parameters.FLASH_MODE_AUTO in flashes) Camera.Parameters.FLASH_MODE_AUTO else Camera.Parameters.FLASH_MODE_OFF
            if (flashMode in flashes) params.flashMode = flashMode
            flashButton?.visibility = if (flashes.any { it == Camera.Parameters.FLASH_MODE_ON || it == Camera.Parameters.FLASH_MODE_AUTO }) View.VISIBLE else View.GONE
            flashButton?.text = flashLabel()
            val orientation = displayOrientation(info)
            params.setRotation(if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (360 - orientation) % 360 else orientation)
            device.parameters = params
            device.setDisplayOrientation(orientation)
            stage?.post {
                val frame = stage ?: return@post
                if (camera === device && frame.width > 0 && frame.height > 0) {
                    val w = if (orientation % 180 == 0) size.width else size.height
                    val h = if (orientation % 180 == 0) size.height else size.width
                    val scale = minOf(frame.width.toFloat() / w, frame.height.toFloat() / h)
                    surface.layoutParams = FrameLayout.LayoutParams((w * scale).toInt(), (h * scale).toInt(), Gravity.CENTER)
                }
            }
            device.setPreviewDisplay(surface.holder)
            device.startPreview()
        } catch (_: Exception) {
            releaseCamera(); status?.text = "Não foi possível abrir a câmera. Escolha uma imagem da galeria."
        }
    }

    private fun flashLabel() = when (flashMode) { Camera.Parameters.FLASH_MODE_ON -> "Flash: ligado"; Camera.Parameters.FLASH_MODE_OFF -> "Flash: desligado"; else -> "Flash: auto" }

    private fun cycleFlash() {
        val device = camera ?: return
        runCatching {
            val params = device.parameters
            val supported = params.supportedFlashModes.orEmpty()
            val order = listOf(Camera.Parameters.FLASH_MODE_AUTO, Camera.Parameters.FLASH_MODE_ON, Camera.Parameters.FLASH_MODE_OFF).filter { it in supported }
            if (order.isEmpty()) return
            flashMode = order[(order.indexOf(flashMode) + 1).mod(order.size)]
            params.flashMode = flashMode; device.parameters = params
            flashButton?.text = flashLabel()
        }
    }

    private fun capture() {
        val device = camera ?: run { status?.text = "Câmera indisponível. Escolha uma imagem da galeria."; return }
        if (!capturing.compareAndSet(false, true)) return
        shutter?.isEnabled = false
        status?.text = "Capturando…"
        val fired = AtomicBoolean(false)
        val shoot = Runnable {
            if (!fired.compareAndSet(false, true) || camera !== device) return@Runnable
            try {
                device.takePicture(null, null) { data, _ ->
                    if (data == null || data.isEmpty() || data.size > MAX_SOURCE_BYTES) { captureFailed(); return@takePicture }
                    releaseCamera()
                    worker.execute {
                        val ok = runCatching { writeSource(data.inputStream()) }.isSuccess
                        handler.post { if (ok) loadSource(null) else captureFailed() }
                    }
                }
            } catch (_: Exception) { captureFailed() }
        }
        val focusMode = runCatching { device.parameters.focusMode }.getOrNull()
        if (focusMode == Camera.Parameters.FOCUS_MODE_AUTO) {
            runCatching { device.autoFocus { _, _ -> handler.post(shoot) } }.onFailure { handler.post(shoot) }
            handler.postDelayed(shoot, 2000)
        } else shoot.run()
    }

    private fun captureFailed() {
        capturing.set(false); shutter?.isEnabled = true
        status?.text = "Não foi possível capturar. Tente novamente."
        if (camera == null && !cropping) openCamera()
    }

    private fun releaseCamera() {
        val device = camera; camera = null
        runCatching { device?.stopPreview() }; runCatching { device?.release() }
    }

    private fun pickFromGallery() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
        try { startActivityForResult(pick, PICK_IMAGE) } catch (_: Exception) {
            Toast.makeText(this, "Nenhum aplicativo disponível para escolher imagens.", Toast.LENGTH_LONG).show()
            if (fromGallery() && !cropping) { setResult(RESULT_CANCELED); finish() }
        }
    }

    @Deprecated("Document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGE) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            if (fromGallery() && !cropping) { setResult(RESULT_CANCELED); finish() }
            return
        }
        loadSource(uri)
    }

    // ---------- Loading & crop stage ----------

    private fun writeSource(input: InputStream) {
        val tmp = File(sourceFile.parentFile, "source.tmp")
        try {
            input.use { stream ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(16 * 1024); var total = 0L; var count: Int
                    while (stream.read(buffer).also { count = it } != -1) {
                        total += count
                        require(total <= MAX_SOURCE_BYTES) { "A imagem precisa ter até 20 MB." }
                        out.write(buffer, 0, count)
                    }
                }
            }
            if (!tmp.renameTo(sourceFile)) { tmp.copyTo(sourceFile, overwrite = true) }
        } finally { if (tmp.exists()) tmp.delete() }
    }

    private fun loadSource(uri: Uri?) {
        releaseCamera()
        if (!processing.compareAndSet(false, true)) return
        showBusy("Preparando imagem…")
        worker.execute {
            val result = runCatching {
                if (uri != null) writeSource(requireNotNull(contentResolver.openInputStream(uri)) { "Não foi possível abrir a imagem." })
                val image = decodeWorking(sourceFile)
                image to (detect(image) ?: PerspectiveGeometry.defaultQuad(image.width, image.height, mode))
            }
            handler.post {
                processing.set(false); capturing.set(false)
                if (isFinishing || isDestroyed) return@post
                result.fold({ (image, quad) -> showCrop(image, quad) }, { err ->
                    Toast.makeText(this, if (err is OutOfMemoryError) "Imagem grande demais para este aparelho." else err.message ?: "Imagem inválida.", Toast.LENGTH_LONG).show()
                    if (fromGallery()) { setResult(RESULT_CANCELED); finish() } else showCamera()
                })
            }
        }
    }

    private fun decodeWorking(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Arquivo de imagem inválido." }
        require(bounds.outWidth.toLong() * bounds.outHeight <= 200_000_000L) { "Imagem grande demais." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_WORKING_EDGE) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })) { "Não foi possível abrir a imagem." }
        val rotation = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
            }
        }.getOrDefault(0)
        val scale = minOf(1f, MAX_WORKING_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
        if (rotation == 0 && scale >= 1f) return decoded
        val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }

    private fun detect(image: Bitmap): List<QuadPoint>? = runCatching {
        val s = minOf(1f, DETECT_EDGE.toFloat() / maxOf(image.width, image.height))
        val w = (image.width * s).toInt().coerceAtLeast(1); val h = (image.height * s).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(image, w, h, true)
        val pixels = IntArray(w * h)
        try { small.getPixels(pixels, 0, w, 0, 0, w, h) } finally { if (small !== image) small.recycle() }
        val luma = IntArray(w * h) { val c = pixels[it]; (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 }
        PerspectiveGeometry.detectQuad(luma, w, h, mode)?.map { QuadPoint(it.x / s, it.y / s) }
    }.getOrNull()

    private fun showBusy(message: String) {
        root.removeAllViews()
        val column = ui.column().apply { gravity = Gravity.CENTER }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        column.addView(android.widget.ProgressBar(this), LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        ui.label(column, message, 14, 0xffb8b8b8.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(12), 0, 0) }
    }

    private fun showCrop(image: Bitmap, quad: List<QuadPoint>) {
        cropping = true; preview = null; stage = null; status = null; flashButton = null; shutter = null
        working = image
        root.removeAllViews()
        val column = ui.column()
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        header(column, "Ajustar margens") { onBackPressedCompat() }
        val view = PerspectiveCropView(this, 0xff8b5cf6.toInt())
        cropView = view
        column.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        view.setImage(image, quad)
        val bar = ui.row().apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(14)); gravity = Gravity.CENTER_VERTICAL }
        column.addView(bar, LinearLayout.LayoutParams(-1, -2))
        fun tool(label: String, action: () -> Unit) = ui.action(bar, label) { if (!processing.get()) action() }.apply {
            textSize = 13f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { marginEnd = ui.dp(6) }
        }
        tool("Margem inteira") { view.fullMargin() }
        tool("Detectar margem") { redetect() }
        tool("Girar 90°") { rotate() }
        ui.action(bar, "✓", primary = true) { if (!processing.get()) confirm() }.apply {
            contentDescription = "Usar foto"; textSize = 20f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(48))
        }
    }

    private fun onBackPressedCompat() {
        if (!fromGallery()) showCamera() else { setResult(RESULT_CANCELED); finish() }
    }

    private fun redetect() {
        val image = working ?: return
        if (!processing.compareAndSet(false, true)) return
        worker.execute {
            val quad = detect(image)
            handler.post {
                processing.set(false)
                if (working !== image) return@post
                if (quad == null) Toast.makeText(this, "Margem não detectada. Ajuste os cantos manualmente.", Toast.LENGTH_SHORT).show()
                else cropView?.setCorners(quad)
            }
        }
    }

    private fun rotate() {
        val image = working ?: return
        val view = cropView ?: return
        val corners = view.corners()
        if (!processing.compareAndSet(false, true)) return
        worker.execute {
            val rotated = runCatching { Bitmap.createBitmap(image, 0, 0, image.width, image.height, Matrix().apply { postRotate(90f) }, true) }.getOrNull()
            handler.post {
                processing.set(false)
                if (rotated == null || working !== image) { Toast.makeText(this, "Não foi possível girar a imagem.", Toast.LENGTH_SHORT).show(); return@post }
                working = rotated
                cropView?.setImage(rotated, PerspectiveGeometry.rotateQuad90(corners, image.height))
            }
        }
    }

    private fun confirm() {
        val image = working ?: return
        val quad = cropView?.corners() ?: return
        if (!PerspectiveGeometry.isConvex(quad, minArea = 64f)) { Toast.makeText(this, "Ajuste os quatro cantos.", Toast.LENGTH_SHORT).show(); return }
        if (!processing.compareAndSet(false, true)) return
        showBusy("Salvando foto…")
        worker.execute {
            val saved = runCatching {
                val output = warp(image, quad)
                val bytes = try {
                    java.io.ByteArrayOutputStream().use { out -> require(output.compress(Bitmap.CompressFormat.JPEG, 90, out)); out.toByteArray() }
                } finally { output.recycle() }
                BookImages(this).saveStoredJpeg(bytes)
            }
            handler.post {
                processing.set(false)
                if (isFinishing || isDestroyed) return@post
                saved.fold({ name ->
                    runCatching { sourceFile.delete() }
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_SAVED_IMAGE, name).putExtra(EXTRA_MODE, mode.key)
                        .putExtra(EXTRA_BOOK_ID, intent.getStringExtra(EXTRA_BOOK_ID)).putExtra(EXTRA_PROFILE, intent.getStringExtra(EXTRA_PROFILE)))
                    finish()
                }, { err ->
                    Toast.makeText(this, if (err is OutOfMemoryError) "Memória insuficiente para salvar a foto." else err.message ?: "Não foi possível salvar a foto.", Toast.LENGTH_LONG).show()
                    showCrop(image, quad)
                })
            }
        }
    }

    /** Native perspective correction: maps the 4 chosen corners onto an upright rectangle. */
    private fun warp(source: Bitmap, quad: List<QuadPoint>): Bitmap {
        val (w, h) = PerspectiveGeometry.outputSize(quad)
        val src = FloatArray(8).also { a -> quad.forEachIndexed { i, p -> a[i * 2] = p.x; a[i * 2 + 1] = p.y } }
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val matrix = Matrix()
        require(matrix.setPolyToPoly(src, 0, dst, 0, 4)) { "Não foi possível corrigir a perspectiva." }
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(output).apply { drawColor(Color.WHITE); drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
        return output
    }

    // ---------- Drawing helpers ----------

    /** Dims everything outside the cover (2:3) or spine (narrow vertical) frame. */
    private inner class GuideOverlay : View(this@BookPhotoCropActivity) {
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f * resources.displayMetrics.density }
        private val path = Path()
        private val frame = RectF()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val q = PerspectiveGeometry.defaultQuad(width, height, mode)
            frame.set(q[0].x, q[0].y, q[2].x, q[2].y)
            path.reset(); path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW); path.addRect(frame, Path.Direction.CW)
            canvas.drawPath(path, dim)
            val corner = minOf(frame.width(), frame.height()) * .18f
            fun l(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, line)
            l(frame.left, frame.top, frame.left + corner, frame.top); l(frame.left, frame.top, frame.left, frame.top + corner)
            l(frame.right, frame.top, frame.right - corner, frame.top); l(frame.right, frame.top, frame.right, frame.top + corner)
            l(frame.left, frame.bottom, frame.left + corner, frame.bottom); l(frame.left, frame.bottom, frame.left, frame.bottom - corner)
            l(frame.right, frame.bottom, frame.right - corner, frame.bottom); l(frame.right, frame.bottom, frame.right, frame.bottom - corner)
        }
    }

    private class ShutterDrawable : android.graphics.drawable.Drawable() {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun draw(canvas: Canvas) {
            val r = minOf(bounds.width(), bounds.height()) / 2f
            ring.strokeWidth = r * .1f
            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), r - ring.strokeWidth, ring)
            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), r * .72f, fill)
        }
        override fun setAlpha(alpha: Int) { fill.alpha = alpha; ring.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { fill.colorFilter = colorFilter; ring.colorFilter = colorFilter }
        @Deprecated("Framework drawable contract")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
