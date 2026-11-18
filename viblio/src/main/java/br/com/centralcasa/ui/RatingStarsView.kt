package br.com.centralcasa.ui

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import br.com.centralcasa.books.halfStarRating
import java.util.Locale

/** A horizontal star slider. Preview during dragging, persist only when released. */
class RatingStarsView(context: Context, initial: Double?) : View(context) {
    private var value = initial
    private var beforeGesture = initial
    private var longPressed = false
    var onRatingChanged: (Double) -> Unit = {}
    var onPreviewRating: (Double?) -> Unit = {}
    init { isClickable = true; isFocusable = true; describe() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun describe() {
        contentDescription = (value?.let { "Nota ${String.format(Locale("pt", "BR"), "%.1f", it)} de 5" } ?: "Sem nota") + ", arraste para avaliar de meio em meio ponto; segure para remover"
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(160), widthMeasureSpec), resolveSize(dp(48), heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cell = width / 5f
        val size = minOf(dp(25).toFloat(), cell - dp(4), height.toFloat()).coerceAtLeast(1f)
        repeat(5) { index ->
            val left = (cell * index + (cell - size) / 2).toInt()
            val top = ((height - size) / 2).toInt()
            RatingStarDrawable(((value ?: 0.0) - index).toFloat()).apply {
                setBounds(left, top, left + size.toInt(), top + size.toInt()); draw(canvas)
            }
        }
    }
    private fun preview(next: Double) { value = next; describe(); invalidate(); onPreviewRating(next) }
    private fun choose(next: Double) { preview(halfStarRating(next / 5.0)); onRatingChanged(requireNotNull(value)) }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beforeGesture = value; longPressed = false; parent?.requestDisallowInterceptTouchEvent(true)
                preview(halfStarRating(event.x / width.coerceAtLeast(1).toDouble()))
            }
            MotionEvent.ACTION_MOVE -> if (!longPressed) preview(halfStarRating(event.x / width.coerceAtLeast(1).toDouble()))
            MotionEvent.ACTION_UP -> {
                super.onTouchEvent(event); parent?.requestDisallowInterceptTouchEvent(false)
                if (!longPressed) onRatingChanged(requireNotNull(value))
                return true
            }
            MotionEvent.ACTION_CANCEL -> { value = beforeGesture; describe(); invalidate(); onPreviewRating(value); parent?.requestDisallowInterceptTouchEvent(false) }
        }
        super.onTouchEvent(event)
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun performLongClick(): Boolean {
        val handled = super.performLongClick()
        if (handled) { longPressed = true; value = beforeGesture; describe(); invalidate() }
        return handled
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { choose((value ?: 0.0) - .5); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { choose((value ?: 0.0) + .5); true }
        else -> super.onKeyDown(keyCode, event)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 5f, (value ?: 0.0).toFloat())
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        if (Build.VERSION.SDK_INT >= 24) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { choose((value ?: 0.0) + .5); return true }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { choose((value ?: 0.0) - .5); return true }
        }
        if (Build.VERSION.SDK_INT >= 24 && action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id) {
            val progress = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.toDouble() ?: return false
            if (!progress.isFinite()) return false
            choose(progress); return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
