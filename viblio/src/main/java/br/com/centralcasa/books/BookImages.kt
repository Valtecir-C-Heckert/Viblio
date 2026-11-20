package br.com.centralcasa.books

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Small shelf thumbnails and separately bounded full-size backgrounds; no per-frame filtering. */
class BookImages(private val context: Context, private val profileSuffix:String=LibraryProfiles.suffix(context)) {
    private val cache = object : LinkedHashMap<String, Bitmap>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 12
    }
    private val backgrounds = object : LinkedHashMap<String, Bitmap>(2, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 2
    }
    private val colors = object : LinkedHashMap<String, Int?>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int?>?) = size > 36
    }
    @Synchronized fun coverColor(name: String): Int? {
        if (name.isBlank()) return null
        if (colors.containsKey(name)) return colors[name]
        val bitmap = thumbnail(name) ?: return null
        val step = ((maxOf(bitmap.width, bitmap.height) + 47) / 48).coerceAtLeast(1)
        val pixels = ArrayList<Int>()
        for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) pixels.add(bitmap.getPixel(x, y))
        return CoverColors.dominant(pixels.toIntArray()).also { colors[name] = it }
    }
    private val directory get() = File(context.filesDir, "book-covers"+profileSuffix).apply { mkdirs() }
    fun file(name: String): File? = if (name.matches(Regex("[a-zA-Z0-9-]+\\.jpg"))) File(directory, name).takeIf { it.isFile } else null
    private fun writeAtomically(target: File, writer: (java.io.OutputStream) -> Unit) {
        val dir = directory
        val tmp = File.createTempFile("cover-", ".tmp", dir)
        try {
            tmp.outputStream().use(writer)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
    fun saveStoredJpeg(bytes:ByteArray):String {
        require(bytes.size<=12*1024*1024 && bytes.size>2 && bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte()) {"Capa JPEG inválida."}
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048) {"Dimensões da capa inválidas."}
        requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size)) {"Capa inválida."}.recycle()
        val name=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}+".jpg"
        writeAtomically(File(directory,name)) { it.write(bytes) }
        return name
    }
    fun save(input: InputStream): String {
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var count: Int
            while (stream.read(buffer).also { count = it } != -1) {
                require(output.size() + count <= 12 * 1024 * 1024) { "A capa precisa ter até 12 MB." }; output.write(buffer, 0, count)
            }; output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Arquivo de capa inválido." }
        var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })) { "Não foi possível abrir a capa." }
        val scale = minOf(1f, 2048f / maxOf(bitmap.width, bitmap.height))
        val stored = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        val name = "${UUID.randomUUID()}.jpg"
        try { writeAtomically(File(directory, name)) { require(stored.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
        finally { if (stored !== bitmap) stored.recycle(); bitmap.recycle() }
        return name
    }
    @Synchronized fun thumbnail(name: String): Bitmap? {
        if (name.isBlank()) return null
        cache[name]?.let { return it }
        val f = file(name) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 320) sample *= 2
        val bitmap = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache[name] = bitmap; return bitmap
    }
    @Synchronized fun background(book: LibraryBook): Drawable {
        val color = when (book.background) {
            "graphite" -> Color.rgb(34, 34, 34); "slate" -> Color.rgb(25, 30, 35)
            "warm" -> Color.rgb(35, 29, 24); "blue" -> Color.rgb(16, 29, 42)
            "wine" -> Color.rgb(39, 20, 29); else -> Color.rgb(21, 21, 21)
        }
        if (book.background != "cover") return GradientDrawable().apply { setColor(color); cornerRadius = 14 * context.resources.displayMetrics.density }
        val result = backgrounds[book.cover] ?: run {
            val file = file(book.cover) ?: return GradientDrawable().apply { setColor(color) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= br.com.centralcasa.ui.CoverBlur.MAX_EDGE) sample *= 2
            val source = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return GradientDrawable().apply { setColor(color) }
            try { br.com.centralcasa.ui.CoverBlur.render(source).also { backgrounds[book.cover] = it } }
            finally { source.recycle() }
        }
        return CenteredCoverBackdrop(result)
    }
}

/** Fit the entire cover width, centred horizontally, over a black reading background. */
private class CenteredCoverBackdrop(private val bitmap: Bitmap) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val scale = bounds.width().toFloat() / bitmap.width
        val halfWidth = bitmap.width * scale / 2
        val halfHeight = bitmap.height * scale / 2
        val target = RectF(bounds.exactCenterX() - halfWidth, bounds.top.toFloat(), bounds.exactCenterX() + halfWidth, bounds.top + halfHeight * 2)
        val save = canvas.save(); canvas.clipRect(bounds)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, null, target, paint)
        canvas.drawRect(bounds, Paint().apply { shader = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            intArrayOf(0x3D000000, 0x99000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt()), floatArrayOf(0f, .42f, .64f, 1f), Shader.TileMode.CLAMP) })
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Framework drawable contract")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
