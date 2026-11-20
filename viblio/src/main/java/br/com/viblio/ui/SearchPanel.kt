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
    private var recsCache = mutableMapOf<String, CatalogRecommendations>()
    private var recsLoading = false
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
    private var infoDialog: android.app.AlertDialog? = null
    private var statusText = ""

    fun acceptIsbn(value: String) {
        query = value
        kind = "all"
        results = emptyList()
        resultsQuery = ""
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
        pending?.let(handler::removeCallbacks)
        pending = null
        covers.detach()
        status = null
        rows = null
        subtabBar = null
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

    fun render(parent: LinearLayout) {
        active = true
        cancelImages()
        val header = ui.row()
        parent.addView(header)
        ui.label(header, if (ui.rectilinear) "PESQUISA" else "Pesquisa", if (ui.rectilinear) 30 else 36, bold = true).apply {
            typeface = Typeface.create("serif", if (ui.rectilinear) Typeface.NORMAL else Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        ui.action(header, "+ Cadastrar") {
            if (manual == null) add(null) else android.app.AlertDialog.Builder(activity).setTitle("Cadastrar manualmente")
                .setItems(arrayOf("Livro", "Mangá", "Quadrinho")) { _, index ->
                    manual.invoke(listOf("book", "manga", "comic")[index])
                }.setNegativeButton("Cancelar", null).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, ui.dp(44))
            minHeight = ui.dp(44)
            textSize = 12f
            background = ui.outlined()
        }
        ui.label(parent, "Encontre sua próxima leitura", 13, ui.muted)

        val field = EditText(activity).apply {
            hint = "Título, autor ou ISBN"
            setText(query)
            textSize = 15f
            setTextColor(ui.text)
            setHintTextColor(ui.muted)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setCompoundDrawables(ui.symbol("search", ui.muted, 20), null, null, null)
            compoundDrawablePadding = ui.dp(10)
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            background = ui.outlined(0x66151515)
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
        val labels = linkedMapOf("all" to "Todos", "book" to "Livros", "manga" to "Mangás", "comic" to "Quadrinhos")
        fun repaintChips() {
            chips.removeAllViews()
            labels.forEach { (key, label) ->
                val change = {
                    if (kind != key) {
                        kind = key
                        repaintChips()
                        if (query.trim().length >= 3) drawResults() else drawBrowse()
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

        status = ui.label(parent, "", 12, ui.muted).apply {
            setPadding(0, ui.dp(10), 0, ui.dp(8))
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

    private fun toggleWishlist(book: CatalogBook) {
        val existing = wishlistIds().contains(book.id)
        if (existing) {
            val previous = wishlistItems
            wishlistItems = wishlistItems.filterNot { it.id == book.id }
            repaintSubtabs()
            if (query.trim().length < 3) drawBrowse() else drawResults()
            catalog.removeWishlist(book.id) { res ->
                if (!active || closed) return@removeWishlist
                res.onFailure { err ->
                    wishlistItems = previous
                    repaintSubtabs()
                    if (query.trim().length < 3) drawBrowse() else drawResults()
                    Toast.makeText(activity, err.message ?: "Não foi possível remover da lista.", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            val previous = wishlistItems
            wishlistItems = listOf(book) + wishlistItems.filterNot { it.id == book.id }
            repaintSubtabs()
            if (query.trim().length < 3) drawBrowse() else drawResults()
            catalog.addWishlist(book) { res ->
                if (!active || closed) return@addWishlist
                res.fold({ saved ->
                    wishlistItems = listOf(saved) + wishlistItems.filterNot { it.id == saved.id }
                    repaintSubtabs()
                }, { err ->
                    wishlistItems = previous
                    repaintSubtabs()
                    if (query.trim().length < 3) drawBrowse() else drawResults()
                    Toast.makeText(activity, err.message ?: "Não foi possível salvar em Quero comprar.", Toast.LENGTH_LONG).show()
                })
            }
        }
    }

    private fun schedule(delay: Long = 400) {
        val epoch = ++generation
        pending?.let(handler::removeCallbacks)
        cancelImages()
        rows?.removeAllViews()
        repaintSubtabs()
        val text = query.trim()
        results = emptyList()
        resultsQuery = text
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
        var titles = emptyList<CatalogBook>()
        var authors = emptyList<CatalogBook>()
        var received = 0
        var failures = 0
        fun finish(response: Result<List<CatalogBook>>, byAuthor: Boolean = false) {
            if (!valid()) return
            received++
            response.fold({ if (byAuthor) authors = it else titles = it }, { failures++ })
            val merged = CatalogSearch.merge(titles, authors)
            if (merged.isNotEmpty() || received == 2) {
                val changed = merged != results
                results = merged
                resultsQuery = text
                if (merged.isEmpty() && failures > 0) {
                    statusText = "Não foi possível completar a pesquisa. Tente novamente."
                    status?.visibility = View.VISIBLE
                    status?.text = statusText
                    rows?.let { target ->
                        target.removeAllViews()
                        ui.action(target, "Tentar novamente") { schedule(0) }
                    }
                } else {
                    statusText = ""
                    if (changed || merged.isEmpty()) drawResults()
                }
            }
        }
        pending = Runnable {
            if (!valid()) return@Runnable
            val isbn = CatalogSearch.isbn(text)
            if (isbn != null) {
                received = 1
                catalog.searchIsbn(isbn) { finish(it) }
            } else {
                catalog.search(text) { finish(it) }
                if (valid()) catalog.search("", author = text) { finish(it, true) }
            }
        }.also { handler.postDelayed(it, delay) }
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
        val cacheKey = "$kind:$recSeed"
        val cached = recsCache[cacheKey]
        if (cached != null) {
            drawRecommendations(target, cached)
            return
        }
        if (recsLoading) {
            status?.visibility = View.VISIBLE
            status?.text = "Carregando recomendações…"
            return
        }
        val epoch = ++recGeneration
        recsLoading = true
        status?.visibility = View.VISIBLE
        status?.text = "Carregando recomendações…"
        catalog.recommendations(kind, recSeed) { response ->
            recsLoading = false
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
        val savedIds = wishlistIds()

        data.sections.forEach { sec ->
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

            val railScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
            val rail = ui.row()
            railScroll.addView(rail)
            target.addView(railScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(14) })

            sec.items.forEachIndexed { idx, book ->
                val isSaved = book.id in savedIds
                val card = ui.column().apply {
                    tag = "rec-book:${book.id}"
                    setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
                    background = ui.outlined(ui.surface, ui.lineColor, 0)
                    isFocusable = true
                    contentDescription = "Recomendação: ${book.title}"
                    setOnClickListener { openBookDialog(book) }
                }
                rail.addView(card, LinearLayout.LayoutParams(cardW, -2).apply {
                    if (idx < sec.items.lastIndex) marginEnd = gapPx
                })

                val cover = ImageView(activity).apply {
                    setImageDrawable(ui.symbol("book_nav", ui.muted, 30))
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
        }
    }

    private fun drawWishlist(target: LinearLayout) {
        target.removeAllViews()
        val shown = CatalogSearch.filter(wishlistItems, kind)
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
                setImageDrawable(ui.symbol("book_nav", ui.muted, 28))
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
                dialogCovers.bind(image, book.coverUrl)
            },
            onClose = {
                dialogCovers.close()
                if (infoDialog?.isShowing != true) infoDialog = null
            },
            add = { add(book) },
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
        status?.text = if (shown.isEmpty()) "Nenhum resultado neste filtro. Você pode cadastrar manualmente." else "${shown.size} edições encontradas · “$resultsQuery”"
        val savedIds = wishlistIds()
        shown.forEach { book ->
            val card = ui.row().apply {
                gravity = Gravity.TOP
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                background = ui.outlined(0x99151515.toInt())
            }
            target.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
            val cover = ImageView(activity).apply {
                setImageDrawable(ui.symbol("book_nav", ui.muted, 40))
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
    }
}
