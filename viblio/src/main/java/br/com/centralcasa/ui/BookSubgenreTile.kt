package br.com.centralcasa.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/** The border belongs to the frame, independent of TextView's internal text scrolling. */
class BookSubgenreTile(context: Context, name: String, color: Int? = null, mainGenre: Boolean = color != null) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    init {
        val border = GradientDrawable().apply {
            setColor(color?.let { (it and 0x00ffffff) or if (mainGenre) 0x40000000 else 0x20000000 } ?: 0x40151515); cornerRadius = dp(28).toFloat()
            setStroke(dp(if (mainGenre) 2 else 1), color?.let { (it and 0x00ffffff) or if (mainGenre) 0xCC000000.toInt() else 0x88000000.toInt() } ?: 0xff96969b.toInt())
        }
        // Keep all four strokes inside the allocated bounds, including the top edge.
        background = InsetDrawable(border, dp(2))
        setPadding(dp(3), dp(3), dp(3), dp(3))
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(TextView(context).apply {
            text = name; textSize = if (mainGenre) 12f else 11f
            if (mainGenre) setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; includeFontPadding = true; minHeight = dp(28)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = null; contentDescription = if (mainGenre) "Gênero $name" else "Subgênero $name"
        }, LayoutParams(-1, -2, Gravity.CENTER))
    }
}
