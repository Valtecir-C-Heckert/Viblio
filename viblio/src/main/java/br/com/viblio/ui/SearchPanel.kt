package br.com.viblio.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import br.com.centralcasa.books.catalog.*
import br.com.centralcasa.ui.DisplayUi

class SearchPanel(
    private val activity: Activity,
    private val ui: DisplayUi,
    private val catalog: BookCatalog,
    private val manual: ((String) -> Unit)? = null,
    private val scan: (() -> Unit)? = null,
    private val add: (CatalogBook?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val covers = SearchCoverLoader(activity)
    private var query = ""
    private var kind = "all"
    private var browseTab = "discover" // "discover" | "wishlist"
    private var recSeed = 0
    private var results = emptyList<CatalogBook>()
    private var resultsQuery = ""
    private var resultsKind = "all"
    private var resultsNextCursor: Int? = null
    private var resultsLoadingMore = false
    private var recsCache = mutableMapOf<String, CatalogRecommendations>()
    private var recsFingerprint = ""
    private var recsLoadingKey: String? = null
    private var wishlistItems = emptyList<CatalogBook>()
    private var wishlistLoaded = false
    private var wishlistLoading = false
    private var generation = 0
    private var recGeneration = 0
    private var active = false
    private var closed = false
    private var pending: Runnable? = null
    private var status: TextView? = null
    private var rows: LinearLayout? = null
    private var subtabBar: LinearLayout? = null
    private var galleryWishBtn: TextView? = null
    private var galleryScopeBtn: TextView? = null
    private var galleryClearBtn: TextView? = null
    private var infoDialog: android.app.AlertDialog? = null
    private var statusText = ""

    private fun isGallery(): Boolean =
        ui.rectilinear && br.com.centralcasa.data.LocalSettings(activity).galleryUiStyle()

    private fun gText(value: CharSequence, sp: Float, color: Int, serif: Boolean = false, medium: Boolean = false) = TextView(activity).apply {
        text = value; textSize = sp; setTextColor(color); includeFontPadding = false
        typeface = when {
            serif -> Typeface.create("serif", Typeface.NORMAL)
            medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }

    fun acceptIsbn(value: String) {
        query = value
        kind = "all"
        results = emptyList()
        resultsQuery = ""
        resultsNextCursor = null
    }

    fun save(state: Bundle) {
        state.putString("search.query", query)
        state.putString("search.kind", kind)
        state.putString("search.browseTab", browseTab)
    }

    fun restore(state: Bundle?) {
        query = state?.getString("search.query").orEmpty()
        kind = state?.getString("search.kind").takeIf { it in listOf("all", "book", "manga", "comic") } ?: "all"
        browseTab = state?.getString("search.browseTab").takeIf { it in listOf("discover", "wishlist") } ?: "discover"
    }

    fun hide() {
        infoDialog?.dismiss()
        infoDialog = null
        active = false
        generation++
        recGeneration++
        recsLoadingKey = null
        resultsLoadingMore = false
        pending?.let(handler::removeCallbacks)
        pending = null
        covers.detach()
        status = null
        rows = null
        subtabBar = null
        galleryWishBtn = null
        galleryScopeBtn = null
        galleryClearBtn = null
    }

    private fun cancelImages() {
        covers.reset()
    }

    fun close() {
        hide()
        closed = true
        handler.removeCallbacksAndMessages(null)
        covers.close()
    }

    private fun wishlistIds(): Set<String> = wishlistItems.mapTo(HashSet()) { it.id }

    private fun openManualCreate() {
        if (manual == null) add(null) else android.app.AlertDialog.Builder(activity).setTitle("Cadastrar manualmente")
            .setItems(arrayOf("Livro", "Mangá", "Quadrinho")) { _, index ->
                manual.invoke(listOf("book", "manga", "comic")[index])
            }.setNegativeButton("Cancelar", null).show()
    }

    fun render(parent: LinearLayout) {
        active = true
        cancelImages()
        galleryWishBtn = null
        galleryScopeBtn = null
        galleryClearBtn = null
        val labels = linkedMapOf("all" to "Todos", "book" to "Livros", "manga" to "Mangás", "comic" to "Quadrinhos")
        val gallery = isGallery()

        val field: EditText
        if (gallery) {
            // Compact 1-line header: Pesquisa + Todos ▾ + Comprar (N) + '+'
            val header = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
            parent.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })

            header.addView(
                gText("Pesquisa", 26f, ui.text, serif = true),
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(10) },
            )
            val scopeBtn = gText("${labels[kind] ?: "Todos"} ▾", 11.5f, ui.accent, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(11), ui.dp(6), ui.dp(11), ui.dp(6))
                background = ui.pillRipple(ui.surface, 18, ui.galleryHairline(145))
                isFocusable = true
                setOnClickListener {
                    val keys = labels.keys.toList()
                    android.app.AlertDialog.Builder(activity).setTitle("Filtrar pesquisa")
                        .setSingleChoiceItems(labels.values.toTypedArray(), keys.indexOf(kind).coerceAtLeast(0)) { dlg, idx ->
                            val next = keys[idx]
                            dlg.dismiss()
                            if (kind != next) {
                                kind = next
                                text = "${labels[kind] ?: "Todos"} ▾"
                                if (query.trim().length >= 3) schedule(0) else drawBrowse()
                            }
                        }.setNegativeButton("Cancelar", null).show()
                }
            }
            galleryScopeBtn = scopeBtn
            header.addView(scopeBtn, LinearLayout.LayoutParams(-2, -2))
            header.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))

            val wishToggle = gText("Comprar", 11.5f, ui.muted, medium = false).apply {
                tag = "search-subtab:wishlist"
                gravity = Gravity.CENTER
                setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
                isFocusable = true
            }
            galleryWishBtn = wishToggle
            header.addView(wishToggle, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6) })

            header.addView(
                gText("+", 18f, ui.accent, medium = true).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "Cadastrar manualmente"
                    background = ui.pillRipple(ui.surface, 17, ui.galleryHairline(145))
                    isFocusable = true
                    setOnClickListener { openManualCreate() }
                },
                LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)),
            )

            // Minimalist underlined editorial search line (zero chunky box!)
            val searchRow = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
            parent.addView(searchRow, LinearLayout.LayoutParams(-1, -2))
            searchRow.addView(
                ImageView(activity).apply {
                    setImageDrawable(ui.symbol("search", ui.accent, 18))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { marginEnd = ui.dp(10) },
            )
            field = EditText(activity).apply {
                hint = "Título, autor, saga ou ISBN"
                setText(query)
                textSize = 14.5f
                setTextColor(ui.text)
                setHintTextColor(ui.muted)
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                background = null
                setPadding(0, ui.dp(10), ui.dp(6), ui.dp(10))
            }
            searchRow.addView(field, LinearLayout.LayoutParams(0, -2, 1f))

            val clearBtn = gText("×", 18f, ui.muted).apply {
                gravity = Gravity.CENTER; contentDescription = "Limpar busca"; isFocusable = true
                background = ui.pillRipple(Color.TRANSPARENT, 16)
                visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                setOnClickListener {
                    field.setText("")
                    field.clearFocus()
                }
            }
            galleryClearBtn = clearBtn
            searchRow.addView(clearBtn, LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)))

            if (scan != null) {
                ui.icon(searchRow, "barcode", "Ler ISBN com a câmera", 36) { scan.invoke() }.apply {
                    tag = "search-scan-isbn"
                    setPadding(ui.dp(6), ui.dp(6), ui.dp(2), ui.dp(6))
                }
            }

            val underline = View(activity).apply { setBackgroundColor(ui.galleryHairline(140)) }
            parent.addView(underline, LinearLayout.LayoutParams(-1, ui.dp(1).coerceAtLeast(1)).apply { bottomMargin = ui.dp(12) })
            field.setOnFocusChangeListener { _, hasFocus ->
                underline.setBackgroundColor(if (hasFocus) ui.accent else ui.galleryHairline(140))
            }

            wishToggle.setOnClickListener {
                if (query.trim().length >= 3) {
                    browseTab = "wishlist"
                    field.setText("")
                } else {
                    browseTab = if (browseTab == "wishlist") "discover" else "wishlist"
                    repaintSubtabs()
                    drawBrowse()
                }
            }
            subtabBar = null
        } else {
            val header = ui.row()
            parent.addView(header)
            ui.label(header, if (ui.rectilinear) "PESQUISA" else "Pesquisa", if (ui.rectilinear) 30 else 36, bold = true).apply {
                typeface = Typeface.create("serif", if (ui.rectilinear) Typeface.NORMAL else Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            ui.action(header, "+ Cadastrar") { openManualCreate() }.apply {
                layoutParams = LinearLayout.LayoutParams(-2, ui.dp(44))
                minHeight = ui.dp(44)
                textSize = 12f
                background = ui.outlined()
            }
            ui.label(parent, "Encontre sua próxima leitura", 13, ui.muted)

            field = EditText(activity).apply {
                hint = "Título, autor, saga ou ISBN"
                setText(query)
                textSize = 15f
                setTextColor(ui.text)
                setHintTextColor(ui.muted)
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setCompoundDrawables(ui.symbol("search", ui.muted, 20), null, null, null)
                compoundDrawablePadding = ui.dp(10)
                setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                background = ui.outlined(ui.surface)
            }
            val queryRow = ui.row()
            parent.addView(queryRow, LinearLayout.LayoutParams(-1, ui.dp(56)).apply {
                topMargin = ui.dp(16)
                bottomMargin = ui.dp(10)
            })
            queryRow.addView(field, LinearLayout.LayoutParams(0, -1, 1f))
            if (scan != null) ui.icon(queryRow, "barcode", "Ler ISBN com a câmera", 52) { scan.invoke() }.apply {
                tag = "search-scan-isbn"
            }

            val horizontal = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
            val chips = ui.row()
            horizontal.addView(chips)
            parent.addView(horizontal, LinearLayout.LayoutParams(-1, -2))
            fun repaintChips() {
                chips.removeAllViews()
                labels.forEach { (key, label) ->
                    val change = {
                        if (kind != key) {
                            kind = key
                            repaintChips()
                            if (query.trim().length >= 3) schedule(0) else drawBrowse()
                        }
                    }
                    if (ui.rectilinear) {
                        val tab = ui.column().apply {
                            isSelected = key == kind
                            isFocusable = true
                            contentDescription = label
                            setOnClickListener { change() }
                        }
                        chips.addView(tab, LinearLayout.LayoutParams(ui.dp(if (key == "comic") 94 else 72), -2))
                        ui.label(tab, label, 11, if (key == kind) ui.accent else ui.muted).apply {
                            gravity = Gravity.CENTER
                            minHeight = ui.dp(38)
                            maxLines = 1
                            setOnClickListener { change() }
                            (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
                        }
                        tab.addView(
                            View(activity).apply { setBackgroundColor(if (key == kind) ui.accent else ui.lineColor) },
                            LinearLayout.LayoutParams(-1, ui.dp(if (key == kind) 2 else 1)),
                        )
                    } else ui.action(chips, label, key == kind) { change() }.apply {
                        layoutParams = LinearLayout.LayoutParams(-2, ui.dp(40)).apply { marginEnd = ui.dp(6) }
                        minHeight = ui.dp(40)
                        setPadding(ui.dp(15), 0, ui.dp(15), 0)
                        if (key != kind) background = ui.outlined(0x33151515)
                    }
                }
            }
            repaintChips()

            subtabBar = ui.row().also { bar ->
                parent.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
            }
        }

        status = ui.label(parent, "", 12, ui.muted).apply {
            setPadding(0, ui.dp(if (gallery) 4 else 10), 0, ui.dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        rows = ui.column().also {
            parent.addView(it)
            covers.attach(it)
        }

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s.toString()
                galleryClearBtn?.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                schedule()
            }
        })
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                schedule(0)
                true
            } else false
        }

        if (!wishlistLoaded && !wishlistLoading && catalog.configured) {
            loadWishlist()
        }
        if (query.trim().length >= 3 && query.trim() == resultsQuery && results.isNotEmpty()) {
            repaintSubtabs()
            drawResults()
        } else {
            schedule(0)
        }
    }

    private fun repaintSubtabs() {
        val gBtn = galleryWishBtn
        if (gBtn != null) {
            val searching = query.trim().length >= 3
            gBtn.visibility = if (!catalog.configured) View.GONE else View.VISIBLE
            val wishCount = wishlistItems.size
            val activeWish = !searching && browseTab == "wishlist"
            val label = if (wishCount > 0) "Comprar $wishCount" else "Comprar"
            gBtn.text = android.text.SpannableStringBuilder(label).apply {
                setSpan(android.text.style.ForegroundColorSpan(if (activeWish) ui.accent else ui.muted), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (wishCount > 0 && !activeWish) {
                    val idx = label.indexOf(' ')
                    if (idx >= 0) setSpan(android.text.style.ForegroundColorSpan(ui.accent), idx + 1, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            gBtn.typeface = Typeface.create(if (activeWish) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            gBtn.isSelected = activeWish
            return
        }
        val bar = subtabBar ?: return
        val searching = query.trim().length >= 3
        bar.visibility = if (searching || !catalog.configured) View.GONE else View.VISIBLE
        if (searching || !catalog.configured) return
        bar.removeAllViews()
        val wishCount = wishlistItems.size
        val wishLabel = if (wishCount > 0) "Quero comprar ($wishCount)" else "Quero comprar"
        listOf("discover" to "Descobrir", "wishlist" to wishLabel).forEach { (key, label) ->
            val selected = browseTab == key
            val cell = ui.column().apply {
                tag = "search-subtab:$key"
                isSelected = selected
                isFocusable = true
                setOnClickListener {
                    if (browseTab != key) {
                        browseTab = key
                        repaintSubtabs()
                        drawBrowse()
                    }
                }
            }
            bar.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            ui.label(cell, label, 12, if (selected) ui.accent else ui.muted).apply {
                gravity = Gravity.CENTER
                minHeight = ui.dp(38)
                maxLines = 1
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
            }
            cell.addView(
                View(activity).apply { setBackgroundColor(if (selected) ui.accent else ui.lineColor) },
                LinearLayout.LayoutParams(-1, ui.dp(if (selected) 2 else 1)),
            )
        }
    }

    private fun loadWishlist(refreshView: Boolean = true) {
        if (!catalog.configured || wishlistLoading) return
        wishlistLoading = true
        catalog.wishlist { response ->
            wishlistLoading = false
            if (!active || closed) return@wishlist
            response.onSuccess { items ->
                wishlistItems = items
                wishlistLoaded = true
                repaintSubtabs()
                if (refreshView && query.trim().length < 3) drawBrowse()
                else if (refreshView && query.trim().length >= 3 && results.isNotEmpty()) drawResults()
            }
        }
    }

    private val wishlistInFlight = mutableSetOf<String>()
    private fun toggleWishlist(book: CatalogBook) {
        if (!wishlistInFlight.add(book.id)) return
        val existing = wishlistIds().contains(book.id)
        if (existing) {
            wishlistItems = wishlistItems.filterNot { it.id == book.id }
            repaintSubtabs()
            if (query.trim().length < 3) drawBrowse() else drawResults()
            catalog.removeWishlist(book.id) { res ->
                wishlistInFlight.remove(book.id)
                if (!active || closed) return@removeWishlist
                res.onFailure { err ->
                    if (wishlistItems.none { it.id == book.id }) {
                        wishlistItems = listOf(book) + wishlistItems
                    }
                    repaintSubtabs()
                    if (query.trim().length < 3) drawBrowse() else drawResults()
                    Toast.makeText(activity, err.message ?: "Não foi possível remover da lista.", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            wishlistItems = listOf(book) + wishlistItems.filterNot { it.id == book.id }
            repaintSubtabs()
            if (query.trim().length < 3) drawBrowse() else drawResults()
            catalog.addWishlist(book) { res ->
                wishlistInFlight.remove(book.id)
                if (!active || closed) return@addWishlist
                res.fold({ saved ->
                    wishlistItems = listOf(saved) + wishlistItems.filterNot { it.id == saved.id }
                    repaintSubtabs()
                }, { err ->
                    wishlistItems = wishlistItems.filterNot { it.id == book.id }
                    repaintSubtabs()
                    if (query.trim().length < 3) drawBrowse() else drawResults()
                    Toast.makeText(activity, err.message ?: "Não foi possível salvar em Quero comprar.", Toast.LENGTH_LONG).show()
                })
            }
        }
    }

    private fun schedule(delay: Long = 350) {
        val epoch = ++generation
        pending?.let(handler::removeCallbacks)
        cancelImages()
        rows?.removeAllViews()
        repaintSubtabs()
        val text = query.trim()
        val reqKind = kind
        results = emptyList()
        resultsQuery = text
        resultsKind = reqKind
        resultsNextCursor = null
        resultsLoadingMore = false
        if (!catalog.configured) {
            statusText = "Entre na sua conta para pesquisar no catálogo."
            status?.visibility = View.VISIBLE
            status?.text = statusText
            return
        }
        if (text.length < 3) {
            results = emptyList()
            resultsQuery = ""
            statusText = ""
            drawBrowse()
            return
        }
        statusText = "Pesquisando…"
        status?.visibility = View.VISIBLE
        status?.text = statusText
        fun valid() = active && !closed && generation == epoch
        pending = Runnable {
            if (!valid()) return@Runnable
            val isbn = CatalogSearch.isbn(text)
            if (isbn != null) {
                catalog.searchIsbn(isbn) { response ->
                    if (!valid()) return@searchIsbn
                    response.fold({ found ->
                        results = found
                        resultsQuery = text
                        resultsKind = reqKind
                        resultsNextCursor = null
                        statusText = ""
                        drawResults()
                    }, {
                        statusText = "Não foi possível completar a pesquisa. Tente novamente."
                        status?.visibility = View.VISIBLE
                        status?.text = statusText
                        rows?.let { target ->
                            target.removeAllViews()
                            ui.action(target, "Tentar novamente") { schedule(0) }
                        }
                    })
                }
            } else {
                catalog.searchUnified(text, reqKind, null) { response ->
                    if (!valid()) return@searchUnified
                    response.fold({ page ->
                        results = page.items
                        resultsQuery = text
                        resultsKind = reqKind
                        resultsNextCursor = page.nextCursor
                        statusText = ""
                        drawResults()
                    }, {
                        statusText = "Não foi possível completar a pesquisa. Tente novamente."
                        status?.visibility = View.VISIBLE
                        status?.text = statusText
                        rows?.let { target ->
                            target.removeAllViews()
                            ui.action(target, "Tentar novamente") { schedule(0) }
                        }
                    })
                }
            }
        }.also { handler.postDelayed(it, delay) }
    }

    private fun loadMoreResults() {
        val next = resultsNextCursor ?: return
        if (resultsLoadingMore || resultsQuery.length < 3) return
        val epoch = generation
        val text = resultsQuery
        val reqKind = resultsKind
        resultsLoadingMore = true
        drawResults()
        catalog.searchUnified(text, reqKind, next) { response ->
            resultsLoadingMore = false
            if (!active || closed || generation != epoch) return@searchUnified
            response.onSuccess { page ->
                val seen = results.mapTo(HashSet()) { it.id }
                val fresh = page.items.filter { it.id !in seen }
                results = results + fresh
                resultsNextCursor = if (fresh.isEmpty()) null else page.nextCursor
            }
            drawResults()
        }
    }

    private fun drawBrowse() {
        val target = rows ?: return
        cancelImages()
        target.removeAllViews()
        repaintSubtabs()
        if (!catalog.configured) {
            status?.visibility = View.VISIBLE
            status?.text = "Entre na sua conta para explorar recomendações."
            return
        }
        if (browseTab == "wishlist") {
            drawWishlist(target)
            return
        }
        val fp = catalog.libraryFingerprint()
        if (fp != recsFingerprint) {
            recsFingerprint = fp
            recsCache.clear()
        }
        val cacheKey = "$kind:$recSeed"
        val cached = recsCache[cacheKey]
        if (cached != null) {
            drawRecommendations(target, cached)
            return
        }
        if (recsLoadingKey == cacheKey) {
            status?.visibility = View.VISIBLE
            status?.text = "Carregando recomendações…"
            return
        }
        val epoch = ++recGeneration
        recsLoadingKey = cacheKey
        status?.visibility = View.VISIBLE
        status?.text = "Carregando recomendações…"
        catalog.recommendations(kind, recSeed) { response ->
            if (recsLoadingKey == cacheKey) recsLoadingKey = null
            if (!active || closed || recGeneration != epoch || query.trim().length >= 3 || browseTab != "discover") return@recommendations
            response.fold({ data ->
                recsCache[cacheKey] = data
                drawRecommendations(target, data)
            }, {
                status?.visibility = View.VISIBLE
                status?.text = "Digite pelo menos três caracteres ou um ISBN."
            })
        }
    }

    private fun drawRecommendations(target: LinearLayout, data: CatalogRecommendations) {
        target.removeAllViews()
        if (data.sections.isEmpty()) {
            status?.visibility = View.VISIBLE
            status?.text = "Digite pelo menos três caracteres ou um ISBN."
            return
        }
        status?.visibility = View.GONE
        if (isGallery()) {
            drawRecommendationsGallery(target, data)
            return
        }

        // Subtle anchor banner showing which book drives the recommendations + refresh button.
        val banner = ui.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(10), ui.dp(8), ui.dp(6), ui.dp(8))
            background = ui.outlined(ui.surface, ui.lineColor, 0)
        }
        target.addView(banner, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = ui.dp(8)
            bottomMargin = ui.dp(10)
        })
        val anchorLabel = when {
            data.anchorTitle.isNotBlank() && data.anchorKind == "current" -> "Baseado no livro atual: ${data.anchorTitle}"
            data.anchorTitle.isNotBlank() && data.anchorKind == "last_finished" -> "Baseado na última leitura: ${data.anchorTitle}"
            data.anchorTitle.isNotBlank() -> "Baseado em: ${data.anchorTitle}"
            else -> "Sugestões do catálogo"
        }
        ui.label(banner, anchorLabel, 11, ui.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) }
        }
        ui.action(banner, "↻") {
            recSeed = (recSeed + 1) % 100
            drawBrowse()
        }.apply {
            tag = "search-refresh-recs"
            contentDescription = "Atualizar recomendações"
            textSize = 14f
            minHeight = ui.dp(32)
            setPadding(ui.dp(10), 0, ui.dp(10), 0)
            setTextColor(ui.accent)
            background = ui.ripple(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(-2, ui.dp(32))
        }

        // Fixed card width: exactly 3 full cards + ~15% peek of the 4th card signaling horizontal scroll!
        val screenW = activity.resources.displayMetrics.widthPixels
        val availW = (screenW - ui.dp(40)).coerceAtLeast(ui.dp(260))
        val gapPx = ui.dp(8)
        val cardW = ((availW - gapPx * 2) / 3.15f).toInt().coerceAtLeast(ui.dp(86))
        val covW = cardW - ui.dp(12)
        val covH = (covW * 1.44f).toInt()
        val cacheKey = "$kind:$recSeed"
        val epoch = recGeneration

        fun addRecCard(rail: LinearLayout, book: CatalogBook) {
            val isSaved = book.id in wishlistIds()
            val card = ui.column().apply {
                tag = "rec-book:${book.id}"
                setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
                background = ui.outlined(ui.surface, ui.lineColor, 0)
                isFocusable = true
                contentDescription = "Recomendação: ${book.title}"
                setOnClickListener { openBookDialog(book) }
            }
            rail.addView(card, LinearLayout.LayoutParams(cardW, -2).apply { marginEnd = gapPx })

            val cover = ImageView(activity).apply {
                setImageDrawable(ViblioBookMarkDrawable(ui.accent, Color.WHITE, scale = 0.48f, backgroundColor = ui.activeSurface))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.shape(ui.activeSurface, 0)
                contentDescription = "Capa de ${book.title}"
            }
            card.addView(cover, LinearLayout.LayoutParams(covW, covH).apply { bottomMargin = ui.dp(6) })
            covers.bind(cover, book.coverUrl)

            ui.label(card, book.title, 11, ui.text).apply {
                typeface = Typeface.create("serif", Typeface.NORMAL)
                maxLines = 2
                minLines = 2
                ellipsize = TextUtils.TruncateAt.END
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(2)
            }
            ui.label(card, book.author.ifBlank { " " }, 9, ui.muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(6)
            }

            val wishBtn = TextView(activity).apply {
                text = if (isSaved) "✓ Salvo" else "+ Comprar"
                gravity = Gravity.CENTER
                textSize = 10f
                maxLines = 1
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (isSaved) Color.rgb(126, 224, 164) else ui.accent)
                background = ui.outlined(
                    if (isSaved) Color.argb(38, 79, 166, 116) else Color.argb(26, Color.red(ui.accent), Color.green(ui.accent), Color.blue(ui.accent)),
                    if (isSaved) Color.argb(120, 79, 166, 116) else ui.lineColor,
                    0,
                )
                setPadding(ui.dp(4), ui.dp(5), ui.dp(4), ui.dp(5))
                setOnClickListener { toggleWishlist(book) }
            }
            card.addView(wishBtn, LinearLayout.LayoutParams(-1, -2))
        }

        data.sections.forEachIndexed { secIdx, sec ->
            val secHeader = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
            target.addView(secHeader, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = ui.dp(4)
                bottomMargin = ui.dp(6)
            })
            ui.label(secHeader, sec.title.uppercase(), 10, ui.muted).apply {
                letterSpacing = 0.08f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) }
            }
            if (sec.tag.isNotBlank()) {
                ui.label(secHeader, sec.tag, 10, ui.accent).apply {
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(-2, -2)
                }
            }

            var onRailScroll: (() -> Unit)? = null
            val railScroll = object : HorizontalScrollView(activity) {
                override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                    super.onScrollChanged(l, t, oldl, oldt)
                    onRailScroll?.invoke()
                }
            }.apply { isHorizontalScrollBarEnabled = false }
            val rail = ui.row()
            railScroll.addView(rail)
            target.addView(railScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(14) })

            val loadedItems = sec.items.toMutableList()
            val seenIds = loadedItems.mapTo(HashSet()) { it.id }
            var hasMore = sec.hasMore
            var nextCursor = sec.nextCursor
            var loadingMore = false
            var loaderTile: View? = null

            loadedItems.forEach { book -> addRecCard(rail, book) }

            fun fetchMore() {
                if (!hasMore || loadingMore || loadedItems.size >= 200 || !active || closed) return
                loadingMore = true
                val tile = TextView(activity).apply {
                    text = "…"
                    gravity = Gravity.CENTER
                    textSize = 16f
                    setTextColor(ui.muted)
                    background = ui.outlined(ui.surface, ui.lineColor, 0)
                }
                loaderTile = tile
                rail.addView(tile, LinearLayout.LayoutParams(ui.dp(48), covH + ui.dp(74)).apply { marginEnd = gapPx })

                catalog.recommendationsMore(
                    section = sec.key,
                    term = sec.term,
                    kind = sec.effectiveKind,
                    cursor = nextCursor,
                    seed = recSeed,
                    excludeIds = loadedItems.map { it.id },
                ) { res ->
                    loadingMore = false
                    loaderTile?.let { rail.removeView(it) }
                    loaderTile = null
                    if (!active || closed || recGeneration != epoch || rail.parent !== railScroll) return@recommendationsMore
                    res.fold({ page ->
                        val fresh = page.items.filter { seenIds.add(it.id) }
                        fresh.forEach { book ->
                            loadedItems.add(book)
                            addRecCard(rail, book)
                        }
                        hasMore = page.hasMore && fresh.isNotEmpty() && loadedItems.size < 200
                        nextCursor = page.nextCursor
                        recsCache[cacheKey]?.let { prev ->
                            val updatedSections = prev.sections.toMutableList()
                            if (secIdx in updatedSections.indices && updatedSections[secIdx].key == sec.key) {
                                updatedSections[secIdx] = updatedSections[secIdx].copy(
                                    items = loadedItems.toList(),
                                    hasMore = hasMore,
                                    nextCursor = nextCursor,
                                )
                                recsCache[cacheKey] = prev.copy(sections = updatedSections)
                            }
                        }
                    }, {
                        hasMore = false
                    })
                }
            }

            if (hasMore) {
                onRailScroll = {
                    if (hasMore && !loadingMore && active && !closed && railScroll.width > 0) {
                        val remaining = rail.width - (railScroll.scrollX + railScroll.width)
                        if (remaining <= cardW * 3) fetchMore()
                    }
                }
            }
        }
    }

    /** Galeria v2 Discover: Featured #1 Spotlight Hero + pure borderless cover rails (zero card boxes). */
    private fun drawRecommendationsGallery(target: LinearLayout, data: CatalogRecommendations) {
        val hairline = ui.galleryHairline(105)
        val cacheKey = "$kind:$recSeed"
        val epoch = recGeneration
        val savedIds = wishlistIds()

        // 1) Quiet borderless anchor header + refresh button
        val anchorRow = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        target.addView(anchorRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
        val anchorText = if (data.anchorTitle.isNotBlank()) {
            android.text.SpannableStringBuilder("POR CAUSA DE ").apply {
                setSpan(android.text.style.ForegroundColorSpan(ui.muted), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val s = length; append(data.anchorTitle.uppercase(java.util.Locale("pt", "BR")))
                setSpan(android.text.style.ForegroundColorSpan(ui.text), s, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            "SUGESTÕES DO CATÁLOGO"
        }
        anchorRow.addView(
            gText(anchorText, 10.5f, ui.muted).apply {
                letterSpacing = 0.05f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) },
        )
        anchorRow.addView(
            gText("↻", 16f, ui.accent).apply {
                tag = "search-refresh-recs"
                contentDescription = "Atualizar recomendações"
                gravity = Gravity.CENTER
                background = ui.pillRipple(Color.TRANSPARENT, 16)
                isFocusable = true
                setOnClickListener { recSeed = (recSeed + 1) % 100; drawBrowse() }
            },
            LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)),
        )

        // 2) Featured Spotlight Hero (#1 recommended book)
        val spotlight = data.sections.firstOrNull()?.items?.firstOrNull()
        if (spotlight != null) {
            val isSaved = spotlight.id in savedIds
            val heroRow = ui.row().apply {
                tag = "rec-book:${spotlight.id}"
                gravity = Gravity.TOP
                isFocusable = true
                contentDescription = "Destaque: ${spotlight.title}"
                setOnClickListener { openBookDialog(spotlight) }
            }
            target.addView(heroRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(18) })

            val cov = ImageView(activity).apply {
                setImageDrawable(ViblioBookMarkDrawable(ui.accent, Color.WHITE, scale = 0.46f, backgroundColor = ui.activeSurface))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.pillShape(ui.activeSurface, 6)
                clipToOutline = true
                elevation = ui.dp(12).toFloat()
                contentDescription = "Capa de ${spotlight.title}"
            }
            heroRow.addView(cov, LinearLayout.LayoutParams(ui.dp(100), ui.dp(148)).apply { marginEnd = ui.dp(16) })
            covers.bind(cov, spotlight.coverUrl)

            val info = ui.column()
            heroRow.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = ui.dp(2) })
            info.addView(
                gText("EM DESTAQUE", 10f, ui.accent, medium = true).apply { letterSpacing = 0.08f },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) },
            )
            info.addView(
                gText(spotlight.title, 20f, ui.text, serif = true).apply {
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.06f)
                },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(3) },
            )
            if (spotlight.author.isNotBlank()) {
                info.addView(
                    gText(spotlight.author, 12f, ui.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) },
                )
            }
            val meta = CatalogSearch.edition(spotlight)
            if (meta.isNotBlank()) {
                info.addView(
                    gText(meta, 10.5f, ui.tint(ui.muted, 195)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) },
                )
            } else {
                info.addView(View(activity), LinearLayout.LayoutParams(1, ui.dp(10)))
            }

            val btns = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
            info.addView(btns, LinearLayout.LayoutParams(-1, -2))
            btns.addView(
                gText("+ Estante", 11.5f, ui.background, medium = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
                    background = ui.pillRipple(ui.accent, 20, darkRipple = true)
                    isFocusable = true
                    setOnClickListener { add(spotlight) }
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) },
            )
            btns.addView(
                gText(if (isSaved) "✓ Salvo" else "Salvar", 11.5f, if (isSaved) Color.rgb(126, 224, 164) else ui.text, medium = isSaved).apply {
                    gravity = Gravity.CENTER
                    setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                    background = ui.pillRipple(
                        if (isSaved) Color.argb(34, 79, 166, 116) else Color.TRANSPARENT,
                        20,
                        if (isSaved) Color.argb(120, 126, 224, 164) else ui.galleryHairline(145),
                    )
                    isFocusable = true
                    setOnClickListener { toggleWishlist(spotlight) }
                },
                LinearLayout.LayoutParams(-2, -2),
            )

            target.addView(View(activity).apply { setBackgroundColor(hairline) }, LinearLayout.LayoutParams(-1, 1).apply { bottomMargin = ui.dp(18) })
        }

        // 3) Pure Borderless Cover Rails (ZERO outer box, ZERO buttons cluttering covers!)
        val covW = ui.dp(96)
        val covH = ui.dp(142)
        val gapPx = ui.dp(14)

        fun addPureCover(rail: LinearLayout, book: CatalogBook) {
            val col = ui.column().apply {
                tag = "rec-book:${book.id}"
                isFocusable = true
                contentDescription = "Recomendação: ${book.title}"
                setOnClickListener { openBookDialog(book) }
            }
            rail.addView(col, LinearLayout.LayoutParams(covW, -2).apply { marginEnd = gapPx })

            val cov = ImageView(activity).apply {
                setImageDrawable(ViblioBookMarkDrawable(ui.accent, Color.WHITE, scale = 0.46f, backgroundColor = ui.activeSurface))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.pillShape(ui.activeSurface, 6)
                clipToOutline = true
                elevation = ui.dp(8).toFloat()
                contentDescription = "Capa de ${book.title}"
            }
            col.addView(cov, LinearLayout.LayoutParams(covW, covH).apply { bottomMargin = ui.dp(8) })
            covers.bind(cov, book.coverUrl)

            col.addView(
                gText(book.title, 12f, ui.text, serif = true).apply {
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f)
                },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(2) },
            )
            if (book.author.isNotBlank()) {
                col.addView(
                    gText(book.author, 10f, ui.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(-1, -2),
                )
            }
        }

        data.sections.forEachIndexed { secIdx, sec ->
            val initialItems = if (secIdx == 0 && spotlight != null) sec.items.filterNot { it.id == spotlight.id } else sec.items
            if (initialItems.isEmpty() && !sec.hasMore) return@forEachIndexed

            val secHead = ui.row().apply { gravity = Gravity.BOTTOM }
            target.addView(secHead, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
            secHead.addView(
                gText(sec.title, 17.5f, ui.text, serif = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) },
            )
            if (sec.tag.isNotBlank()) {
                secHead.addView(
                    gText(sec.tag, 10.5f, ui.accent).apply { maxLines = 1 },
                    LinearLayout.LayoutParams(-2, -2),
                )
            }

            var onRailScroll: (() -> Unit)? = null
            val railScroll = object : HorizontalScrollView(activity) {
                override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                    super.onScrollChanged(l, t, oldl, oldt)
                    onRailScroll?.invoke()
                }
            }.apply { isHorizontalScrollBarEnabled = false; clipToPadding = false }
            val rail = ui.row()
            railScroll.addView(rail)
            target.addView(railScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(22) })

            val loadedItems = sec.items.toMutableList()
            val seenIds = loadedItems.mapTo(HashSet()) { it.id }
            var hasMore = sec.hasMore
            var nextCursor = sec.nextCursor
            var loadingMore = false
            var loaderTile: View? = null

            initialItems.forEach { book -> addPureCover(rail, book) }

            fun fetchMore() {
                if (!hasMore || loadingMore || loadedItems.size >= 200 || !active || closed) return
                loadingMore = true
                val tile = gText("…", 18f, ui.muted).apply { gravity = Gravity.CENTER }
                loaderTile = tile
                rail.addView(tile, LinearLayout.LayoutParams(ui.dp(42), covH).apply { marginEnd = gapPx })

                catalog.recommendationsMore(
                    section = sec.key,
                    term = sec.term,
                    kind = sec.effectiveKind,
                    cursor = nextCursor,
                    seed = recSeed,
                    excludeIds = loadedItems.map { it.id },
                ) { res ->
                    loadingMore = false
                    loaderTile?.let { rail.removeView(it) }
                    loaderTile = null
                    if (!active || closed || recGeneration != epoch || rail.parent !== railScroll) return@recommendationsMore
                    res.fold({ page ->
                        val fresh = page.items.filter { seenIds.add(it.id) }
                        fresh.forEach { book ->
                            loadedItems.add(book)
                            addPureCover(rail, book)
                        }
                        hasMore = page.hasMore && fresh.isNotEmpty() && loadedItems.size < 200
                        nextCursor = page.nextCursor
                        recsCache[cacheKey]?.let { prev ->
                            val updatedSections = prev.sections.toMutableList()
                            if (secIdx in updatedSections.indices && updatedSections[secIdx].key == sec.key) {
                                updatedSections[secIdx] = updatedSections[secIdx].copy(
                                    items = loadedItems.toList(),
                                    hasMore = hasMore,
                                    nextCursor = nextCursor,
                                )
                                recsCache[cacheKey] = prev.copy(sections = updatedSections)
                            }
                        }
                    }, {
                        hasMore = false
                    })
                }
            }

            if (hasMore) {
                onRailScroll = {
                    if (hasMore && !loadingMore && active && !closed && railScroll.width > 0) {
                        val remaining = rail.width - (railScroll.scrollX + railScroll.width)
                        if (remaining <= covW * 3) fetchMore()
                    }
                }
            }
        }
    }

    private fun drawWishlist(target: LinearLayout) {
        target.removeAllViews()
        val shown = CatalogSearch.filter(wishlistItems, kind)
        if (isGallery()) {
            val top = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
            target.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
            top.addView(gText("Quero comprar", 20f, ui.text, serif = true), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(
                gText("← Descobrir", 11.5f, ui.accent, medium = true).apply {
                    tag = "search-subtab:discover"
                    setPadding(ui.dp(8), ui.dp(4), 0, ui.dp(4)); isFocusable = true
                    setOnClickListener { browseTab = "discover"; repaintSubtabs(); drawBrowse() }
                },
                LinearLayout.LayoutParams(-2, -2),
            )
            if (!wishlistLoaded && wishlistLoading) {
                status?.visibility = View.VISIBLE; status?.text = "Carregando…"; return
            }
            if (shown.isEmpty()) {
                status?.visibility = View.VISIBLE
                status?.text = if (wishlistItems.isEmpty()) "Lista vazia" else "Nenhum item neste filtro"
                return
            }
            status?.visibility = View.GONE
            val hairline = ui.galleryHairline(105)
            shown.forEachIndexed { idx, book ->
                val row = ui.row().apply {
                    tag = "wishlist-book:${book.id}"
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, ui.dp(12), 0, ui.dp(12))
                    isFocusable = true
                    setOnClickListener { openBookDialog(book) }
                }
                target.addView(row, LinearLayout.LayoutParams(-1, -2))
                val cov = ImageView(activity).apply {
                    setImageDrawable(ViblioBookMarkDrawable(ui.accent, Color.WHITE, scale = 0.46f, backgroundColor = ui.activeSurface))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = ui.pillShape(ui.activeSurface, 5)
                    clipToOutline = true; elevation = ui.dp(6).toFloat()
                    contentDescription = "Capa de ${book.title}"
                }
                row.addView(cov, LinearLayout.LayoutParams(ui.dp(48), ui.dp(72)).apply { marginEnd = ui.dp(14) })
                covers.bind(cov, book.coverUrl)

                val col = ui.column()
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                col.addView(
                    gText(book.title, 15.5f, ui.text, serif = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(2) },
                )
                val sub = listOf(book.author, CatalogSearch.edition(book)).filter(String::isNotBlank).joinToString(" · ")
                if (sub.isNotBlank()) {
                    col.addView(
                        gText(sub, 11f, ui.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) },
                    )
                }
                val acts = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
                col.addView(acts, LinearLayout.LayoutParams(-1, -2))
                acts.addView(
                    gText("Comprei → Estante", 11f, ui.background, medium = true).apply {
                        setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
                        background = ui.pillRipple(ui.accent, 18, darkRipple = true)
                        isFocusable = true
                        setOnClickListener { toggleWishlist(book); add(book) }
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(12) },
                )
                acts.addView(
                    gText("Remover", 11f, ui.muted).apply {
                        setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6)); isFocusable = true
                        setOnClickListener { toggleWishlist(book) }
                    },
                    LinearLayout.LayoutParams(-2, -2),
                )
                if (idx < shown.lastIndex) {
                    target.addView(View(activity).apply { setBackgroundColor(hairline) }, LinearLayout.LayoutParams(-1, 1))
                }
            }
            return
        }

        if (!wishlistLoaded && wishlistLoading) {
            status?.visibility = View.VISIBLE
            status?.text = "Carregando lista Quero comprar…"
            return
        }
        if (shown.isEmpty()) {
            status?.visibility = View.VISIBLE
            status?.text = if (wishlistItems.isEmpty()) {
                "Sua lista Quero comprar está vazia. Salve sugestões em Descobrir ou na pesquisa."
            } else {
                "Nenhum item de Quero comprar neste filtro."
            }
            return
        }
        status?.visibility = View.GONE
        shown.forEach { book ->
            val card = ui.row().apply {
                tag = "wishlist-book:${book.id}"
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                background = ui.outlined(ui.surface, ui.lineColor, 0)
                isFocusable = true
                setOnClickListener { openBookDialog(book) }
            }
            target.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })

            val cover = ImageView(activity).apply {
                setImageDrawable(ViblioBookMarkDrawable(ui.accent, Color.WHITE, scale = 0.48f, backgroundColor = ui.activeSurface))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.shape(ui.activeSurface, 0)
                contentDescription = "Capa de ${book.title}"
            }
            card.addView(cover, LinearLayout.LayoutParams(ui.dp(46), ui.dp(69)).apply { marginEnd = ui.dp(12) })
            covers.bind(cover, book.coverUrl)

            val col = ui.column()
            card.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            ui.label(col, book.title, 15, ui.text).apply {
                typeface = Typeface.create("serif", Typeface.NORMAL)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            if (book.author.isNotBlank()) {
                ui.label(col, book.author, 11, ui.muted).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            }
            val meta = CatalogSearch.edition(book)
            if (meta.isNotBlank()) {
                ui.label(col, meta, 10, ui.muted).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            }
            val actions = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
            col.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
            ui.action(actions, "Comprei → Estante", true) {
                toggleWishlist(book)
                add(book)
            }.apply {
                textSize = 10.5f
                minHeight = ui.dp(34)
                setPadding(ui.dp(10), 0, ui.dp(10), 0)
                layoutParams = LinearLayout.LayoutParams(-2, ui.dp(34)).apply { marginEnd = ui.dp(8) }
            }
            ui.action(actions, "Remover") {
                toggleWishlist(book)
            }.apply {
                textSize = 10.5f
                minHeight = ui.dp(34)
                setPadding(ui.dp(10), 0, ui.dp(10), 0)
                background = ui.outlined(Color.TRANSPARENT, ui.lineColor, 0)
                layoutParams = LinearLayout.LayoutParams(-2, ui.dp(34))
            }
        }
    }

    private fun openBookDialog(book: CatalogBook) {
        if (infoDialog?.isShowing == true) return
        val dialogCovers = SearchCoverLoader(activity)
        var chosenBook = book
        infoDialog = BookInfoDialog.show(
            activity,
            ui,
            BookInfoDialog.Info(
                book.title,
                book.author,
                book.publisher,
                listOf(book.genre, book.subgenre).filter(String::isNotBlank).joinToString(" · "),
                book.pages,
                book.isbn,
            ),
            bind = { image, root ->
                dialogCovers.attach(root)
                dialogCovers.bind(image, chosenBook.coverUrl)
            },
            onClose = {
                dialogCovers.close()
                if (infoDialog?.isShowing != true) infoDialog = null
            },
            add = { add(chosenBook) },
            isWishlisted = chosenBook.id in wishlistIds(),
            onToggleWishlist = if (catalog.configured) {
                {
                    toggleWishlist(chosenBook)
                    chosenBook.id in wishlistIds()
                }
            } else null,
            onChooseCover = if (catalog.configured) {
                { applyUrl ->
                    catalog.chooseCover(chosenBook.id, chosenBook.source) { chosen ->
                        if (active && !closed) {
                            chosen.fold({ choice ->
                                chosenBook = chosenBook.copy(coverUrl = choice.url)
                                applyUrl(choice.url)
                            }, {
                                Toast.makeText(activity, it.message ?: "Não foi possível listar as capas.", Toast.LENGTH_LONG).show()
                            })
                        }
                    }
                }
            } else null,
            rebindCover = { image, url -> dialogCovers.bind(image, url) },
        )
    }

    private fun drawResults() {
        val target = rows ?: return
        cancelImages()
        target.removeAllViews()
        repaintSubtabs()
        val shown = CatalogSearch.filter(results, kind)
        if (statusText.isNotBlank()) {
            status?.visibility = View.VISIBLE
            status?.text = statusText
            return
        }
        status?.visibility = View.VISIBLE
        status?.text = if (shown.isEmpty()) "Nenhum resultado neste filtro." else "${shown.size} edições encontradas"
        val savedIds = wishlistIds()

        if (isGallery()) {
            val hairline = ui.galleryHairline(105)
            shown.forEachIndexed { idx, book ->
                val row = ui.row().apply {
                    tag = "search-book:${book.id}"
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, ui.dp(12), 0, ui.dp(12))
                    isFocusable = true
                    contentDescription = "Informações de ${book.title}"
                    setOnClickListener { openBookDialog(book) }
                }
                target.addView(row, LinearLayout.LayoutParams(-1, -2))

                val cov = ImageView(activity).apply {
                    setImageDrawable(ViblioBookMarkDrawable(ui.accent, Color.WHITE, scale = 0.46f, backgroundColor = ui.activeSurface))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = ui.pillShape(ui.activeSurface, 5)
                    clipToOutline = true; elevation = ui.dp(6).toFloat()
                    contentDescription = "Capa de ${book.title}"
                }
                row.addView(cov, LinearLayout.LayoutParams(ui.dp(48), ui.dp(72)).apply { marginEnd = ui.dp(14) })
                covers.bind(cov, book.coverUrl)

                val col = ui.column()
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(10) })
                col.addView(
                    gText(book.title, 16f, ui.text, serif = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(2) },
                )
                if (book.author.isNotBlank()) {
                    col.addView(
                        gText(book.author, 11.5f, ui.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(2) },
                    )
                }
                val meta = CatalogSearch.edition(book)
                if (meta.isNotBlank()) {
                    col.addView(
                        gText(meta, 10.5f, ui.tint(ui.muted, 190)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(-1, -2),
                    )
                }

                row.addView(
                    gText("+", 18f, ui.accent, medium = true).apply {
                        gravity = Gravity.CENTER
                        contentDescription = "Adicionar ${book.title} à Estante"
                        background = ui.pillRipple(ui.tint(ui.accent, 34), 17)
                        isFocusable = true
                        setOnClickListener { add(book) }
                    },
                    LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)),
                )

                if (idx < shown.lastIndex) {
                    target.addView(View(activity).apply { setBackgroundColor(hairline) }, LinearLayout.LayoutParams(-1, 1))
                }
            }
            if (resultsNextCursor != null) {
                target.addView(
                    gText(if (resultsLoadingMore) "Carregando…" else "Carregar mais", 12.5f, ui.accent, medium = true).apply {
                        gravity = Gravity.CENTER; isEnabled = !resultsLoadingMore
                        setPadding(0, ui.dp(14), 0, ui.dp(14)); isFocusable = true
                        setOnClickListener { loadMoreResults() }
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
            }
            return
        }

        shown.forEach { book ->
            val card = ui.row().apply {
                gravity = Gravity.TOP
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                background = ui.outlined(ui.surface)
            }
            target.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
            val cover = ImageView(activity).apply {
                setImageDrawable(ViblioBookMarkDrawable(ui.accent, Color.WHITE, scale = 0.45f, backgroundColor = ui.activeSurface))
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = ui.shape(ui.surface, 7)
                clipToOutline = true
                contentDescription = "Capa de ${book.title}"
            }
            card.addView(cover, LinearLayout.LayoutParams(ui.dp(74), ui.dp(110)).apply { marginEnd = ui.dp(12) })
            val text = ui.column()
            card.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            ui.label(text, book.title, 19, bold = true).apply {
                typeface = Typeface.create("serif", Typeface.BOLD)
                maxLines = 3
            }
            if (book.author.isNotBlank()) ui.label(text, book.author, 12, ui.muted)
            ui.label(text, CatalogSearch.edition(book), 11, ui.muted)
            var selected = book
            val isSaved = book.id in savedIds
            ui.action(text, "Adicionar à Estante", true) { add(selected) }.apply {
                textSize = 11f
                minHeight = ui.dp(40)
            }
            val secondaryRow = ui.row()
            text.addView(secondaryRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
            ui.action(secondaryRow, if (isSaved) "✓ Quero comprar" else "+ Quero comprar") {
                toggleWishlist(selected)
            }.apply {
                background = ui.ripple(Color.TRANSPARENT)
                minHeight = ui.dp(34)
                textSize = 11f
                setTextColor(if (isSaved) Color.rgb(126, 224, 164) else ui.accent)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            ui.action(secondaryRow, "Ver capas") {
                val requestQuery = resultsQuery
                catalog.chooseCover(book.id, book.source) { chosen ->
                    if (active && !closed && requestQuery == resultsQuery && card.parent === target) {
                        chosen.fold({ choice ->
                            selected = book.copy(coverUrl = choice.url)
                            covers.bind(cover, choice.url)
                        }, {
                            Toast.makeText(activity, it.message ?: "Não foi possível listar as capas.", Toast.LENGTH_LONG).show()
                        })
                    }
                }
            }.apply {
                background = ui.ripple(Color.TRANSPARENT)
                minHeight = ui.dp(34)
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(-2, -2)
            }
            card.tag = "search-book:${book.id}"
            card.isFocusable = true
            card.contentDescription = "Informações de ${book.title}"
            card.setOnClickListener { openBookDialog(selected) }
            covers.bind(cover, book.coverUrl)
        }
        if (resultsNextCursor != null) {
            ui.action(target, if (resultsLoadingMore) "Carregando…" else "Carregar mais") {
                loadMoreResults()
            }.apply {
                isEnabled = !resultsLoadingMore
                background = ui.outlined(ui.surface)
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(12)
            }
        }
    }
}
