package br.com.centralcasa.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.SeekBar
import kotlin.math.abs

/** Observe before dispatch: ScrollView itself can own touches on non-clickable covers/text. */
class ReadingScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    var pinchColumns: ((Double) -> Boolean)? = null
    var canPinchAt: ((Float, Float) -> Boolean)? = null
    var isTouching=false
        private set
    var pinchStep=1.2
    private var multiTouch = false
    private var pinchScale = 1.0
    private var pinchStartSpan = 1f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector) = multiTouch && pinchColumns != null
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinchScale = detector.currentSpan.toDouble() / pinchStartSpan
            if ((pinchScale >= pinchStep || pinchScale <= 1.0 / pinchStep) && pinchColumns?.invoke(pinchScale) == true) {
                // Start the next density step here, so the same movement is not applied twice.
                pinchStartSpan = detector.currentSpan.coerceAtLeast(1f)
                pinchScale = 1.0
            }
            return true
        }
    }).apply { isQuickScaleEnabled = false }
    var moveBook: ((Int) -> Unit)? = null
    var canPullRefresh: (() -> Boolean)? = null
    var onPullProgress: ((Float, Boolean) -> Unit)? = null
    var onPullRefresh: (() -> Unit)? = null
    private var startX = 0f
    private var startY = 0f
    private var excluded = false
    private var horizontal = false
    private var vertical = false
    private var pulling = false
    private var pullReady = false
    private val threshold get() = 48 * resources.displayMetrics.density
    private val pullSlop get() = 14 * resources.displayMetrics.density
    private val pullTriggerPx get() = 72 * resources.displayMetrics.density
    private fun onSlider(view: View, event: MotionEvent): Boolean {
        if (view is SeekBar || view is RatingStarsView || view is android.widget.HorizontalScrollView) {
            val rect = Rect()
            return view.getGlobalVisibleRect(rect) && rect.contains(event.rawX.toInt(), event.rawY.toInt())
        }
        return view is ViewGroup && (0 until view.childCount).any { onSlider(view.getChildAt(it), event) }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) isTouching=true
        if(event.actionMasked in listOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL)) isTouching=false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            multiTouch = false; pinchScale = 1.0
            startX = event.x; startY = event.y
            horizontal = false; vertical = false; pulling = false; pullReady = false
            excluded = onSlider(this, event)
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !multiTouch &&
            pinchColumns != null && canPinchAt?.invoke(event.rawX, event.rawY) == true) {
            pinchStartSpan = kotlin.math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0)).coerceAtLeast(1f)
            multiTouch = true; horizontal = false; vertical = false
            if (pulling) { pulling = false; pullReady = false; onPullProgress?.invoke(0f, false) }
            MotionEvent.obtain(event).also { cancel ->
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel); cancel.recycle()
            }
        }
        scaleDetector.onTouchEvent(event)
        if (multiTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                multiTouch = false; pinchScale = 1.0
            }
            return true
        }
        val dx = event.x - startX
        val dy = event.y - startY

        // Pull-to-refresh when at top of scroll container (works across Classic & Gallery).
        if (!excluded && !horizontal && canPullRefresh?.invoke() == true) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (!pulling && scrollY == 0 && dy > pullSlop && dy > abs(dx) * 1.3f) {
                        pulling = true
                        MotionEvent.obtain(event).also { cancel ->
                            cancel.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(cancel); cancel.recycle()
                        }
                    }
                    if (pulling) {
                        val damped = (dy - pullSlop).coerceAtLeast(0f) * 0.48f
                        val progress = (damped / pullTriggerPx).coerceIn(0f, 1.45f)
                        if (!pullReady && progress >= 1f) {
                            pullReady = true
                            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        } else if (pullReady && progress < 0.85f) {
                            pullReady = false
                        }
                        onPullProgress?.invoke(progress, true)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pulling) {
                    val trigger = event.actionMasked == MotionEvent.ACTION_UP && pullReady
                    pulling = false; pullReady = false
                    onPullProgress?.invoke(0f, false)
                    if (trigger) onPullRefresh?.invoke()
                    return true
                }
            }
        }

        if (moveBook == null) return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!excluded && !horizontal && !vertical) {
                    if (abs(dy) > threshold / 2 && abs(dy) >= abs(dx)) vertical = true
                    else if (abs(dx) > threshold && abs(dx) > abs(dy) * 1.5f) {
                        horizontal = true
                        MotionEvent.obtain(event).also { cancel ->
                            cancel.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(cancel); cancel.recycle()
                        }
                    }
                }
                if (horizontal) return true
            }
            MotionEvent.ACTION_UP -> if (horizontal) {
                horizontal = false
                if (abs(dx) > threshold && abs(dx) > abs(dy) * 1.5f) moveBook?.invoke(if (dx < 0) 1 else -1)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { horizontal = false; vertical = false }
        }
        return super.dispatchTouchEvent(event)
    }
}
