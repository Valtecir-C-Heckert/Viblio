package br.com.viblio.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import br.com.centralcasa.data.LocalSettings
import br.com.centralcasa.ui.DisplayUi

/** A consultation view only: no repository, timer, credentials or implicit library writes. */
object BookInfoDialog {
    data class Info(
        val title: String,
        val author: String = "",
        val publisher: String = "",
        val genre: String = "",
        val pages: Int? = null,
        val isbn: String = "",
        val page: Int? = null,
        val owner: String = "",
    )

    fun show(
        activity: Activity,
        ui: DisplayUi,
        info: Info,
        bind: (ImageView, View) -> Unit,
        onClose: () -> Unit,
        add: (() -> Unit)? = null,
        isWishlisted: Boolean = false,
        onToggleWishlist: (() -> Boolean)? = null,
        onChooseCover: (((String) -> Unit) -> Unit)? = null,
        rebindCover: ((ImageView, String) -> Unit)? = null,
    ): AlertDialog {
        val gallery = ui.rectilinear && LocalSettings(activity).galleryUiStyle()
        if (!gallery) {
            return showClassic(activity, ui, info, bind, onClose, add)
        }
        return showGalleryModal(activity, ui, info, bind, onClose, add, isWishlisted, onToggleWishlist, onChooseCover, rebindCover)
    }

    private fun showClassic(
        activity: Activity,
        ui: DisplayUi,
        info: Info,
        bind: (ImageView, View) -> Unit,
        onClose: () -> Unit,
        add: (() -> Unit)?,
    ): AlertDialog {
        val body = ui.column().apply { tag = "book-info"; setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(20)) }
        if (info.owner.isNotBlank()) ui.label(body, "@${info.owner.take(32)}", 13, ui.accent).gravity = Gravity.CENTER_HORIZONTAL
        val image = ImageView(activity).apply {
            setImageDrawable(ViblioBookMarkDrawable(ui.accent, Color.WHITE, scale = 0.45f, backgroundColor = ui.activeSurface))
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ui.shape(ui.activeSurface, 0)
            contentDescription = "Capa de ${info.title.take(500)}"
        }
        body.addView(image, LinearLayout.LayoutParams(ui.dp(140), ui.dp(210)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(16) })
        ui.label(body, info.title.take(500), 26).apply { typeface = Typeface.create("serif", Typeface.NORMAL); gravity = Gravity.CENTER_HORIZONTAL }
        if (info.author.isNotBlank()) ui.label(body, info.author.take(200), 14, ui.muted).gravity = Gravity.CENTER_HORIZONTAL
        val facts = ui.panel(body, 12)
        if (info.page != null) {
            val total = info.pages?.takeIf { it > 0 }; val page = info.page.coerceAtLeast(0)
            val label = if (total != null) "$page / $total páginas · ${(page.toLong() * 100 / total).coerceIn(0, 100)}%" else "$page páginas"
            ui.label(facts, label, 18, ui.accent, true).tag = "book-info-progress"
            if (total != null) ui.progress(facts, (page.toLong() * 100 / total).coerceIn(0, 100).toInt())
        } else info.pages?.let { ui.label(facts, "$it páginas", 14) }
        if (info.publisher.isNotBlank()) ui.label(facts, info.publisher.take(200), 13, ui.muted)
        if (info.genre.isNotBlank()) ui.label(facts, info.genre.take(200), 13, ui.muted)
        if (info.isbn.isNotBlank()) ui.label(facts, "ISBN ${info.isbn.take(32)}", 13, ui.muted)
        val builder = AlertDialog.Builder(activity).setView(ScrollView(activity).apply { addView(body) }).setNegativeButton("Fechar", null)
        if (add != null) builder.setPositiveButton("Adicionar à estante") { _, _ -> add() }
        val dialog = builder.create(); dialog.setOnDismissListener { onClose() }; dialog.show(); dialog.window?.setBackgroundDrawable(ui.shape(ui.surface, 0)); bind(image, body)
        return dialog
    }

    /** Centered editorial Galeria modal with radial cover-tinted top wash, protagonist cover, 3-column typographic colophon, and thumb-friendly actions. */
    private fun showGalleryModal(
        activity: Activity,
        ui: DisplayUi,
        info: Info,
        bind: (ImageView, View) -> Unit,
        onClose: () -> Unit,
        add: (() -> Unit)?,
        initialWishlisted: Boolean,
        onToggleWishlist: (() -> Boolean)?,
        onChooseCover: (((String) -> Unit) -> Unit)?,
        rebindCover: ((ImageView, String) -> Unit)?,
    ): AlertDialog {
        val hairline = ui.galleryHairline(105)
        val radiusPx = ui.dp(24).toFloat()
        val strokePx = ui.dp(1).coerceAtLeast(1).toFloat()

        fun mText(value: CharSequence, sp: Float, color: Int, serif: Boolean = false, medium: Boolean = false) = TextView(activity).apply {
            text = value; textSize = sp; setTextColor(color); includeFontPadding = false
            typeface = when {
                serif -> Typeface.create("serif", Typeface.NORMAL)
                medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                else -> Typeface.create("sans-serif", Typeface.NORMAL)
            }
        }

        val cardBg = object : Drawable() {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.surface }
            private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokePx
                color = ui.galleryHairline(145)
            }
            private val clipPath = Path()
            private val rect = RectF()
            private var lastW = 0; private var lastH = 0
            override fun draw(canvas: Canvas) {
                val b = bounds; val w = b.width(); val h = b.height()
                if (w <= 0 || h <= 0) return
                rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                if (w != lastW || h != lastH) {
                    lastW = w; lastH = h
                    clipPath.reset()
                    clipPath.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
                    val cx = b.left + w * 0.5f; val top = b.top.toFloat()
                    val r = (w * 0.72f).coerceAtLeast(ui.dp(180).toFloat())
                    glowPaint.shader = RadialGradient(
                        cx, top, r,
                        intArrayOf(ui.tint(ui.accent, 52), ui.tint(ui.accent, 18), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.52f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.save()
                canvas.clipPath(clipPath)
                canvas.drawRect(rect, fillPaint)
                canvas.drawRect(rect.left, rect.top, rect.right, (rect.top + ui.dp(220)).coerceAtMost(rect.bottom), glowPaint)
                canvas.restore()
                val half = strokePx * 0.5f
                rect.inset(half, half)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, borderPaint)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }

        val outerWrap = FrameLayout(activity).apply {
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        }
        val body = ui.column().apply {
            tag = "book-info"
            background = cardBg
            setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(20))
        }
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(body, FrameLayout.LayoutParams(-1, -2))
        }
        outerWrap.addView(scroll, FrameLayout.LayoutParams(-1, -2))

        var dialogRef: AlertDialog? = null

        // Top bar: optional @owner on left + dismiss × on right
        val topRow = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        body.addView(topRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) })
        if (info.owner.isNotBlank()) {
            topRow.addView(
                mText("@${info.owner.take(32)}", 12f, ui.accent, medium = true),
                LinearLayout.LayoutParams(0, -2, 1f),
            )
        } else {
            topRow.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        }
        topRow.addView(
            mText("×", 22f, ui.muted).apply {
                gravity = Gravity.CENTER; contentDescription = "Fechar"; isFocusable = true
                background = ui.pillRipple(Color.TRANSPARENT, 18)
                setOnClickListener { dialogRef?.dismiss() }
            },
            LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)),
        )

        // Centered Protagonist Cover + Serif Title & Author
        val hero = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        body.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(16) })

        val image = ImageView(activity).apply {
            setImageDrawable(ViblioBookMarkDrawable(ui.accent, Color.WHITE, scale = 0.45f, backgroundColor = ui.activeSurface))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.pillShape(ui.activeSurface, 7)
            clipToOutline = true
            elevation = ui.dp(14).toFloat()
            contentDescription = "Capa de ${info.title.take(500)}"
        }
        hero.addView(image, LinearLayout.LayoutParams(ui.dp(116), ui.dp(174)).apply { bottomMargin = ui.dp(14) })

        hero.addView(
            mText(info.title.take(500), 22f, ui.text, serif = true).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.08f)
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) },
        )
        if (info.author.isNotBlank()) {
            hero.addView(
                mText(info.author.take(200), 13f, ui.muted).apply {
                    gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }

        // Reading progress when consulting a friend's book
        if (info.page != null) {
            val total = info.pages?.takeIf { it > 0 }
            val page = info.page.coerceAtLeast(0)
            val pct = if (total != null) (page.toLong() * 100 / total).coerceIn(0, 100).toInt() else 0
            val label = if (total != null) "$page / $total páginas · $pct%" else "$page páginas"
            val progWrap = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
            body.addView(progWrap, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(14) })
            progWrap.addView(
                mText(label, 14f, ui.accent, medium = true).apply {
                    tag = "book-info-progress"; fontFeatureSettings = "tnum"
                },
                LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.dp(6) },
            )
            if (total != null) {
                val track = FrameLayout(activity).apply {
                    background = ui.pillShape(ui.galleryHairline(85), 4); clipToOutline = true
                }
                val tRow = ui.row()
                if (pct > 0) tRow.addView(View(activity).apply { setBackgroundColor(ui.accent) }, LinearLayout.LayoutParams(0, -1, pct.toFloat()))
                if (pct < 100) tRow.addView(View(activity), LinearLayout.LayoutParams(0, -1, (100 - pct).toFloat()))
                track.addView(tRow, FrameLayout.LayoutParams(-1, -1))
                progWrap.addView(track, LinearLayout.LayoutParams(-1, ui.dp(3).coerceAtLeast(3)))
            }
        }

        // 3-Column Typographic Colophon (NO grey info box!)
        val cols = mutableListOf<Triple<String, String, Boolean>>()
        info.pages?.takeIf { it > 0 }?.let { cols.add(Triple(it.toString(), "páginas", false)) }
        if (info.publisher.isNotBlank()) cols.add(Triple(info.publisher.take(80), "editora", false))
        if (info.genre.isNotBlank()) cols.add(Triple(info.genre.take(80), "gênero", true))

        if (cols.isNotEmpty()) {
            body.addView(View(activity).apply { setBackgroundColor(hairline) }, LinearLayout.LayoutParams(-1, 1))
            val strip = ui.row().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(12), 0, ui.dp(12))
            }
            body.addView(strip, LinearLayout.LayoutParams(-1, -2))
            cols.forEachIndexed { idx, (value, caption, isAccent) ->
                if (idx > 0) strip.addView(View(activity).apply { setBackgroundColor(hairline) }, LinearLayout.LayoutParams(1, ui.dp(26)))
                val col = ui.column().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(ui.dp(6), 0, ui.dp(6), 0)
                }
                val isNum = caption == "páginas"
                col.addView(
                    mText(value, if (isNum) 16f else 12.5f, if (isAccent) ui.accent else ui.text, serif = isNum, medium = !isNum).apply {
                        gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; fontFeatureSettings = "tnum"
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.dp(2) },
                )
                col.addView(mText(caption, 10f, ui.muted).apply { maxLines = 1 }, LinearLayout.LayoutParams(-2, -2))
                strip.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            }
            body.addView(View(activity).apply { setBackgroundColor(hairline) }, LinearLayout.LayoutParams(-1, 1).apply { bottomMargin = ui.dp(if (info.isbn.isNotBlank()) 8 else 16) })
        }

        if (info.isbn.isNotBlank()) {
            body.addView(
                mText("ISBN ${info.isbn.take(32)}", 11f, ui.muted).apply {
                    gravity = Gravity.CENTER; fontFeatureSettings = "tnum"
                },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(16) },
            )
        }

        // Thumb-zone Action Bar
        val actions = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        body.addView(actions, LinearLayout.LayoutParams(-1, -2))

        if (add != null) {
            val addBtn = Button(activity).apply {
                text = "Adicionar à Estante"
                isAllCaps = false; textSize = 12.5f; gravity = Gravity.CENTER
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setTextColor(ui.background)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = ui.pillRipple(ui.accent, 24, darkRipple = true)
                minHeight = ui.dp(44); minimumHeight = ui.dp(44)
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                setOnClickListener { dialogRef?.dismiss(); add() }
            }
            actions.addView(addBtn, LinearLayout.LayoutParams(0, ui.dp(44), 1f))

            if (onToggleWishlist != null) {
                var saved = initialWishlisted
                val wishBtn = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    isFocusable = true
                    setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
                    fun sync() {
                        val iconColor = if (saved) Color.rgb(126, 224, 164) else ui.text
                        setImageDrawable(HeartIconDrawable(filled = saved, color = iconColor))
                        contentDescription = if (saved) "Remover de Quero comprar" else "Salvar em Quero comprar"
                        val fill = if (saved) Color.argb(38, 79, 166, 116) else ui.activeSurface
                        val stroke = if (saved) Color.argb(135, 126, 224, 164) else ui.galleryHairline(140)
                        val circle = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(fill)
                            setStroke(ui.dp(1).coerceAtLeast(1), stroke)
                        }
                        val mask = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(Color.WHITE)
                        }
                        background = android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf(0x28FFFFFF),
                            circle,
                            mask,
                        )
                    }
                    sync()
                    setOnClickListener {
                        saved = onToggleWishlist.invoke()
                        sync()
                    }
                }
                actions.addView(wishBtn, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { marginStart = ui.dp(8) })
            }

            if (onChooseCover != null) {
                val coverBtn = TextView(activity).apply {
                    text = "Capas"; gravity = Gravity.CENTER; textSize = 12f; isFocusable = true
                    maxLines = 1
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(ui.muted)
                    background = ui.pillRipple(ui.activeSurface, 22, ui.galleryHairline(140))
                    minHeight = ui.dp(44)
                    setPadding(ui.dp(13), ui.dp(10), ui.dp(13), ui.dp(10))
                    setOnClickListener {
                        onChooseCover.invoke { newUrl ->
                            rebindCover?.invoke(image, newUrl)
                        }
                    }
                }
                actions.addView(coverBtn, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginStart = ui.dp(8) })
            }
        } else {
            val closeBtn = Button(activity).apply {
                text = "Fechar"
                isAllCaps = false; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(ui.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = ui.pillRipple(ui.activeSurface, 24, ui.galleryHairline(145))
                minHeight = ui.dp(44); minimumHeight = ui.dp(44)
                setPadding(ui.dp(24), ui.dp(10), ui.dp(24), ui.dp(10))
                setOnClickListener { dialogRef?.dismiss() }
            }
            actions.addView(closeBtn, LinearLayout.LayoutParams(-1, -2))
        }

        val dialog = AlertDialog.Builder(activity).setView(outerWrap).create()
        dialogRef = dialog
        dialog.setOnDismissListener { onClose() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setDimAmount(0.64f)
        }
        bind(image, body)
        return dialog
    }
}

/** Crisp symmetric vector heart (24x24 viewBox) so it never depends on font glyphs or stretches. */
private class HeartIconDrawable(
    private val filled: Boolean,
    private val color: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@HeartIconDrawable.color
        style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val size = minOf(w, h)
        val ox = b.left + (w - size) * 0.5f
        val oy = b.top + (h - size) * 0.5f
        val s = size / 24f
        paint.strokeWidth = (if (filled) 1.2f else 1.9f) * s
        path.reset()
        path.moveTo(ox + 12f * s, oy + 20.6f * s)
        path.cubicTo(ox + 11.4f * s, oy + 20.1f * s, ox + 3.2f * s, oy + 14.5f * s, ox + 3.2f * s, oy + 8.8f * s)
        path.cubicTo(ox + 3.2f * s, oy + 5.7f * s, ox + 5.6f * s, oy + 3.6f * s, ox + 8.3f * s, oy + 3.6f * s)
        path.cubicTo(ox + 10.1f * s, oy + 3.6f * s, ox + 11.4f * s, oy + 4.6f * s, ox + 12f * s, oy + 5.9f * s)
        path.cubicTo(ox + 12.6f * s, oy + 4.6f * s, ox + 13.9f * s, oy + 3.6f * s, ox + 15.7f * s, oy + 3.6f * s)
        path.cubicTo(ox + 18.4f * s, oy + 3.6f * s, ox + 20.8f * s, oy + 5.7f * s, ox + 20.8f * s, oy + 8.8f * s)
        path.cubicTo(ox + 20.8f * s, oy + 14.5f * s, ox + 12.6f * s, oy + 20.1f * s, ox + 12f * s, oy + 20.6f * s)
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
