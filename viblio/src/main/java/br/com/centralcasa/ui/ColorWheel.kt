package br.com.centralcasa.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import br.com.centralcasa.services.Rgb
import kotlin.math.atan2
import kotlin.math.hypot

/** Hue ring commits one HA command at gesture end; presets and RGB dialog offer accessible alternatives. */
class ColorWheel(context: Context, color: Rgb?, private val gesture: (Boolean) -> Unit, private val preview: (Rgb) -> Unit = {}, private val commit: (Rgb) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hue = FloatArray(3).also { Color.RGBToHSV(color?.red ?: 255, color?.green ?: 180, color?.blue ?: 70, it) }[0]
    init { contentDescription = "Roda de cores; arraste para escolher a cor. Use os botões de cores para acesso por toque ou leitor de tela."; isFocusable = true }
    fun setColor(color: Rgb) { hue = FloatArray(3).also { Color.RGBToHSV(color.red, color.green, color.blue, it) }[0]; invalidate() }
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val radius = minOf(width, height) * .44f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = radius * .55f
        paint.shader = SweepGradient(cx, cy, intArrayOf(Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED), null)
        canvas.drawCircle(cx, cy, radius * .7f, paint); paint.shader = null
        val angle = Math.toRadians(-hue.toDouble())
        paint.strokeWidth = 2 * resources.displayMetrics.density; paint.color = Color.WHITE
        canvas.drawCircle(cx + kotlin.math.cos(angle).toFloat() * radius * .7f, cy + kotlin.math.sin(angle).toFloat() * radius * .7f, radius * .12f, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val distance = hypot(event.x - width / 2f, event.y - height / 2f)
                if (distance < minOf(width, height) * .15f || distance > minOf(width, height) * .5f) return false
                parent.requestDisallowInterceptTouchEvent(true); gesture(true)
            }
            MotionEvent.ACTION_CANCEL -> { gesture(false); parent.requestDisallowInterceptTouchEvent(false); return true }
        }
        hue = ((360 - Math.toDegrees(atan2((event.y - height / 2f).toDouble(), (event.x - width / 2f).toDouble()))) % 360).toFloat(); invalidate()
        val value = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        val color = Rgb(Color.red(value), Color.green(value), Color.blue(value))
        preview(color)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            gesture(false); parent.requestDisallowInterceptTouchEvent(false); performClick(); commit(color)
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
