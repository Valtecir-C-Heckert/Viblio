package br.com.centralcasa.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.graphics.Rect
import android.widget.*
import br.com.centralcasa.books.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Books is part of the Central, with its own local persistence and document pickers. */
class BooksPanel(private val activity: Activity, private val ui: DisplayUi, private val changed: () -> Unit) {
    var catalog:br.com.centralcasa.books.catalog.BookCatalog?=null
    private var catalogGeneration=0
    private var catalogCover=""
    private var pendingScannedIsbn:String?=null
    val editingId get()=editor?.id.takeIf {screen=="editor"}
    fun acceptScannedIsbn(value:String){
        val isbn=Isbn.normalize(value) ?: return
        if(screen!="editor" || editor==null)return
        captureEditor();pendingScannedIsbn=isbn;fields.clear();changed()
    }
    val repository = LibraryRepository(activity)
    private val datesEditor = ReadingDatesEditor(activity, ui, repository, changed)
    private val images = BookImages(activity)
    var useRedesignedStatistics=false
    var useAsyncShelfImages=false
    var useCoverProgressColor=false
    var pinchStep=1.2
    var separateShelfStatistics=false
    var onStatisticsBack:(()->Unit)?=null
    var planCatalogProvider:()->br.com.centralcasa.books.catalog.BookCatalog?={null}
    var onOpenPlannedBook:((String)->Unit)?=null
    var onAddCatalogToShelf:((br.com.centralcasa.books.catalog.CatalogBook)->Unit)?=null
    var onRequestShelfBookForPlan:((Int,Int)->Unit)?=null
    var planSelectionTitle:String?=null
    var onPlanBookSelected:((LibraryBook)->Unit)?=null
    var onCancelPlanSelection:(()->Unit)?=null
    fun clearPlanSelection(){ planSelectionTitle=null; onPlanBookSelected=null; onCancelPlanSelection=null }
    fun openPlanTab(year:Int,month:Int){ statisticsTab="plan"; annualPlanPanel?.selectPeriod(year,month) }
    fun completePlanBookSelection(year:Int,month:Int,book:LibraryBook){
        statisticsTab="plan"
        val panel=annualPlanPanel ?: AnnualPlanPanel(
            activity=activity,ui=ui,repo=repository,catalogProvider={planCatalogProvider()},
            onOpenBook=onOpenPlannedBook,onAddCatalogBook=onAddCatalogToShelf,onPickBookFromShelf=onRequestShelfBookForPlan,
        ){ if(!destroyed) changed() }.also { annualPlanPanel=it }
        panel.onShelfBookPicked(year,month,book)
    }
    var onAccount:(()->Unit)?=null
    var jsonAllowed:()->Boolean={true}
    var useGroupedReadingHistory=false
    var useContrastCoverBorders=false
    var useSpineLayout=false
    var useCollectionInventory=false
    var usePersonalArchive=false
    var useManualShelfOrder=false
        set(value) { field=value;if(value) shelfFilters=loadShelfFilters() }
    private var manualDraft:List<String>?=null
    private var manualView=false
    private fun spineMode()=useSpineLayout && settings.get("SHELF_SPINE_VIEW:$shelfType")=="true"
    private var spineZoom=1f
    private var refreshSpines:(()->Unit)?=null
    private val statisticsExpanded=mutableSetOf<String>()
    private var goalsPanel:ReadingGoalsPanel?=null
    private var annualPlanPanel:AnnualPlanPanel?=null
    private var retrospectiveDialog:RetrospectiveDialog?=null
    private var heatmapYear=java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    private var heatmapMonth=java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)+1
    private var heatmapSelectedDay:String?=null
    private var progressDialog:AlertDialog?=null
    private var manualScrollDirection=0
    private val manualAutoScroll=object:Runnable {
        override fun run() {
            if(manualDraft==null || destroyed || manualScrollDirection==0 || !isShelf) return
            activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll)?.scrollBy(0,manualScrollDirection*ui.dp(6))
            handler.postDelayed(this,16)
        }
    }
    private fun manualIsDefault()=useManualShelfOrder && settings.get("SHELF_MANUAL_DEFAULT:$shelfType")=="true"
    private fun savedManualOrder():List<String> = runCatching {
        org.json.JSONArray(settings.get("SHELF_MANUAL_ORDER:$shelfType").ifBlank { "[]" }).let { a->(0 until a.length()).map { a.getString(it) } }
    }.getOrDefault(emptyList())
    private fun stopManualScroll() { manualScrollDirection=0;handler.removeCallbacks(manualAutoScroll) }
    private fun manualScroll(view:View,y:Float) {
        val scroll=activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll) ?: return
        val rect=Rect();if(!scroll.getGlobalVisibleRect(rect)) return
        val point=IntArray(2);view.getLocationOnScreen(point)
        val position=point[1]+y
        val direction=when { position<rect.top+ui.dp(56)->-1;position>rect.bottom-ui.dp(56)->1;else->0 }
        if(direction!=manualScrollDirection) { stopManualScroll();manualScrollDirection=direction;if(direction!=0) handler.post(manualAutoScroll) }
    }
    fun prepareStatistics() { screen="stats" }
    fun addItem(kind:String="book") { if(kind in listOf("manga","comic")) editCollection(null,kind) else addFromCatalog() }
    private val shelfMaterials by lazy {ShelfMaterials.load(activity)}
    private val shelfThumbnails by lazy { ShelfThumbnailLoader(images) }
    private val emptyCoverIcon by lazy {
        val source = android.graphics.BitmapFactory.decodeResource(activity.resources, br.com.centralcasa.R.drawable.ic_books_nav,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
        android.graphics.Bitmap.createBitmap(source.width, source.height, android.graphics.Bitmap.Config.ARGB_8888).also { target ->
            android.graphics.Canvas(target).drawBitmap(source, 0f, 0f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            })
            source.recycle()
        }
    }
    private fun emptyCoverDrawable() = android.graphics.drawable.BitmapDrawable(activity.resources, emptyCoverIcon)

    private val settings = br.com.centralcasa.data.LocalSettings(activity)
    private val json = LibraryJson(images)
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var screen = "library"
    private var selected: String? = null
    private var selectedCollection: String? = null
    private var statisticsType = settings.get("STATISTICS_TYPE").takeIf { it in CollectionKinds.labels } ?: "all"
    private var shelfType = settings.get("SHELF_TYPE").takeIf { it in CollectionKinds.labels } ?: "book"
    private var editor: LibraryBook? = null
    private var newBook = false
    private var collectionEditor: CollectionTitle? = null
    private var collectionEditorNew = false
    private var collectionOwned = "0"
    private val collectionFields = linkedMapOf<String, EditText>()
    fun currentAppearanceKey():String=repository.current()?.let {it.id+":"+it.cover}.orEmpty()
    fun currentReadingColor():Int?=repository.current()?.let {images.coverColor(it.cover)}
    /** Book on screen (detail) or, otherwise, the current reading; used by DynamicAppIcon when leaving the app. */
    fun lastViewedBookId():String?=(if(isDetail)selected else null) ?: repository.currentId()
    fun lastViewedBookCover():String?=lastViewedBookId()?.let { repository.book(it)?.takeUnless { b -> b.archived }?.cover }?.takeIf { it.isNotBlank() }
    fun bookCoverColor(id:String):Int?=repository.book(id)?.takeUnless {it.archived}?.let {images.coverColor(it.cover)}
    fun currentReadingThumbnail(): android.graphics.Bitmap? = repository.current()?.let { images.thumbnail(it.cover) }
    val isCurrentReading get()=isDetail && selected!=null && selected==repository.current()?.id
    val isEditor get() = screen in listOf("editor", "collection_editor")
    val canReplaceLibrary get() = !isEditor && manualDraft==null && !busy && (progressDialog?.isShowing!=true) && (goalsPanel?.isEditing!=true) && (annualPlanPanel?.isEditing!=true) && !datesEditor.isEditing
    private var editingGenre = ""
    private val editingSubgenres = mutableListOf<String>()
    private var subgenreInput: AutoCompleteTextView? = null
    private var subgenreInputGenre = ""
    private val fields = linkedMapOf<String, EditText>()
    val isShelf get() = screen == "library"
    val isDetail get() = screen == "detail"
    val isCollection get() = screen == "collection"
    private var historyExpanded = false
    private var timerBookId: String? = null
    private var shelfFilters = loadShelfFilters()
    private var shelfScroll = 0
    val isStatistics get() = screen == "stats"
    private var busy = false
    private var destroyed = false
    private var visible = false
    private var timerLabel: TextView? = null
    private var shownSessions = 4
    private val tick = object : Runnable {
        private var beats = 0
        override fun run() {
            if (!visible || destroyed) return
            val d = repository.draft()
            timerLabel?.text = readingTime(d?.takeIf { it.bookId == timerBookId }?.let(repository::elapsed) ?: 0)
            if (++beats % 5 == 0 && d?.runningSince != null) repository.checkpoint()
            if (d?.runningSince != null) handler.postDelayed(this, 1000)
        }
    }
    init { repository.recover(); settings.migrateGenreColors() }
    fun onStart() { visible = true; handler.removeCallbacks(tick); handler.post(tick) }
    fun onStop() { stopManualScroll();captureEditor(); captureCollectionEditor(); visible = false; handler.removeCallbacks(tick); repository.checkpoint() }
    fun onDestroy() { datesEditor.close();goalsPanel?.close();goalsPanel=null;annualPlanPanel?.close();annualPlanPanel=null;retrospectiveDialog?.destroy();retrospectiveDialog=null;progressDialog?.dismiss();progressDialog=null;destroyed = true;refreshSpines=null; if(useAsyncShelfImages || useSpineLayout) shelfThumbnails.close(); handler.removeCallbacksAndMessages(null); worker.execute { repository.close() }; worker.shutdown() }
    fun saveState(out: Bundle) {
        out.putBoolean("books.manualView",manualView)
        manualDraft?.let { out.putStringArrayList("books.manualDraft",ArrayList(it)) }
        out.putString("books.catalogCover",catalogCover)
        captureEditor(); captureCollectionEditor(); persistShelfFilters(); out.putString("books.type", shelfType); out.putString("books.screen", screen); out.putString("books.selected", selected); out.putString("books.collection", selectedCollection)
        out.putString("books.query", shelfFilters.query); out.putString("books.status", shelfFilters.status.name)
        out.putString("books.subgenre", shelfFilters.subgenre); out.putString("books.genre", shelfFilters.genre); out.putString("books.author", shelfFilters.author)
        out.putString("books.ratings", shelfFilters.ratings.name); shelfFilters.minimumRating?.let { out.putDouble("books.minimumRating", it) }
        out.putString("books.completion",shelfFilters.collectionCompletion.name)
        out.putString("books.covers", shelfFilters.covers.name); out.putString("books.sort", shelfFilters.sort.name)
        out.putString("books.saga", shelfFilters.saga); out.putString("books.order", ShelfOrder.encode(shelfFilters.order)); out.putInt("books.scroll", shelfScroll)
        collectionEditor?.let { c ->
            out.putString("collection.edit.id", c.id); out.putString("collection.edit.kind", c.kind)
            out.putString("collection.edit.title", c.title); out.putString("collection.edit.author", c.author)
            out.putStringArrayList("collection.edit.subgenres", ArrayList(c.subgenres)); out.putString("collection.edit.publisher", c.publisher); out.putString("collection.edit.genre", c.genre)
            out.putString("collection.edit.expected",c.expectedVolumes?.toString())
            out.putString("collection.edit.missing", c.missingVolumes?.toString()); out.putString("collection.edit.cover", c.cover)
            out.putBoolean("collection.edit.new", collectionEditorNew); out.putString("collection.edit.owned", collectionOwned)
            out.putInt("collection.edit.sourceRead", c.sourceReadCount); out.putLong("collection.edit.updated", c.updatedAt)
            c.rating?.let { out.putDouble("collection.edit.rating", it) }; c.sourceRating?.let { out.putDouble("collection.edit.sourceRating", it) }
        }
        editor?.let { b ->
            out.putString("books.edit.id", b.id); out.putBoolean("books.edit.new", newBook)
            out.putString("books.edit.title", fields["title"]?.text?.toString() ?: b.title)
            out.putString("books.edit.author", b.author); out.putString("books.edit.genre", b.genre); out.putString("books.edit.subgenre", b.subgenre); out.putStringArrayList("books.edit.subgenres", ArrayList(b.selectedSubgenres))
            out.putString("books.edit.total", fields["total"]?.text?.toString() ?: b.totalPages.toString())
            out.putString("books.edit.page", fields["page"]?.text?.toString() ?: b.currentPage.toString())
            out.putString("books.edit.saga", b.saga)
            out.putString("books.edit.sequence", b.sequenceNumber?.toString())
            out.putString("books.edit.kind", b.kind); out.putString("books.edit.collectionId", b.collectionId); out.putString("books.edit.publisher", b.publisher)
            out.putBoolean("books.edit.pagesKnown", b.pagesKnown); out.putBoolean("books.edit.readFinished", b.readFinished)
            if (b.rating != null) out.putDouble("books.edit.rating", b.rating)
            out.putString("books.edit.catalogId",b.catalogId);out.putString("books.edit.catalogSource",b.catalogSource)
            out.putString("books.edit.cover", b.cover); out.putString("books.edit.spine", b.spineImage); out.putString("books.edit.background", b.background)
        }
    }
    fun restoreState(state: Bundle?) {
        if (state == null) return
        manualView=state.getBoolean("books.manualView")
        manualDraft=state.getStringArrayList("books.manualDraft")
        catalogCover=state.getString("books.catalogCover").orEmpty()
        shelfType = state.getString("books.type")?.takeIf { it in CollectionKinds.labels } ?: shelfType
        shelfFilters = ShelfFilters(
            collectionCompletion=runCatching {CollectionCompletion.valueOf(state.getString("books.completion","ALL"))}.getOrDefault(CollectionCompletion.ALL),
            query = state.getString("books.query").orEmpty(),
            status = runCatching { ShelfStatus.valueOf(state.getString("books.status").orEmpty()) }.getOrDefault(ShelfStatus.ALL),
            subgenre = state.getString("books.subgenre"), genre = state.getString("books.genre"), author = state.getString("books.author"),
            covers = runCatching { ShelfCovers.valueOf(state.getString("books.covers").orEmpty()) }.getOrDefault(ShelfCovers.ALL),
            sort = runCatching { ShelfSort.valueOf(state.getString("books.sort").orEmpty()) }.getOrDefault(ShelfSort.TITLE),
            ratings = runCatching { ShelfRatings.valueOf(state.getString("books.ratings").orEmpty()) }.getOrDefault(ShelfRatings.ALL),
            minimumRating = if (state.containsKey("books.minimumRating")) state.getDouble("books.minimumRating") else null,
            saga = state.getString("books.saga"), order = ShelfOrder.decode(state.getString("books.order").orEmpty()).ifEmpty { if(manualIsDefault() || manualDraft!=null || useManualShelfOrder && manualView) emptyList() else shelfOrderDefaults() }
        )
        shelfFilters = migrateShelfFilter(shelfFilters)
        shelfScroll = state.getInt("books.scroll")
        screen = state.getString("books.screen") ?: "library"; selected = state.getString("books.selected"); selectedCollection = state.getString("books.collection")
        state.getString("collection.edit.id")?.let { id ->
            collectionEditor = CollectionTitle(id = id, kind = state.getString("collection.edit.kind") ?: "manga", title = state.getString("collection.edit.title").orEmpty(),
                subgenres = state.getStringArrayList("collection.edit.subgenres").orEmpty(), author = state.getString("collection.edit.author").orEmpty(), publisher = state.getString("collection.edit.publisher").orEmpty(), genre = state.getString("collection.edit.genre").orEmpty(),
                missingVolumes = state.getString("collection.edit.missing")?.toIntOrNull(), expectedVolumes=state.getString("collection.edit.expected")?.toIntOrNull(), cover = state.getString("collection.edit.cover").orEmpty(), sourceReadCount = state.getInt("collection.edit.sourceRead"), updatedAt = state.getLong("collection.edit.updated"),
                rating = if (state.containsKey("collection.edit.rating")) state.getDouble("collection.edit.rating") else null,
                sourceRating = if (state.containsKey("collection.edit.sourceRating")) state.getDouble("collection.edit.sourceRating") else null)
            collectionEditorNew = state.getBoolean("collection.edit.new"); collectionOwned = state.getString("collection.edit.owned") ?: "0"
        }
        state.getString("books.edit.id")?.let { id ->
            editor = LibraryBook(id, state.getString("books.edit.title").orEmpty(), state.getString("books.edit.author").orEmpty(), state.getString("books.edit.genre").orEmpty(), state.getString("books.edit.total")?.toIntOrNull() ?: 0, state.getString("books.edit.page")?.toIntOrNull() ?: 0, state.getString("books.edit.cover").orEmpty(), state.getString("books.edit.background") ?: "black", sequenceNumber = state.getString("books.edit.sequence")?.toIntOrNull(), saga = state.getString("books.edit.saga").orEmpty(), subgenre = state.getString("books.edit.subgenre").orEmpty(), subgenres = state.getStringArrayList("books.edit.subgenres").orEmpty(), kind = state.getString("books.edit.kind") ?: "book",
                collectionId = state.getString("books.edit.collectionId").orEmpty(), publisher = state.getString("books.edit.publisher").orEmpty(),
                rating = if (state.containsKey("books.edit.rating")) state.getDouble("books.edit.rating") else null,
                pagesKnown = state.getBoolean("books.edit.pagesKnown", true), readFinished = state.getBoolean("books.edit.readFinished"),catalogId=state.getString("books.edit.catalogId"),catalogSource=state.getString("books.edit.catalogSource").orEmpty(),spineImage=state.getString("books.edit.spine").orEmpty())
            newBook = state.getBoolean("books.edit.new")
        }
    }
    fun back(): Boolean {
        if(screen=="library" && manualDraft!=null) {
            AlertDialog.Builder(activity).setTitle("Cancelar organização?").setMessage("A ordem salva será mantida.")
                .setPositiveButton("Cancelar mudanças") { _,_->cancelManualOrder() }.setNegativeButton("Continuar organizando",null).show()
            return true
        }
        if (screen == "library" && planSelectionTitle != null) {
            val cancel = onCancelPlanSelection
            clearPlanSelection()
            cancel?.invoke()
            return true
        }
        if (screen == "library") return false
        if (screen == "collection_editor") {
            AlertDialog.Builder(activity).setTitle("Sair da edição?").setMessage("As alterações não salvas serão descartadas.")
                .setPositiveButton("Descartar alterações") { _, _ -> collectionFields.clear(); collectionEditor = null; show(if (selectedCollection == null) "library" else "collection") }.setNegativeButton("Continuar editando", null).show()
        } else if (screen == "editor") {
            AlertDialog.Builder(activity).setTitle("Sair da edição?").setMessage("As alterações não salvas serão descartadas.")
                .setPositiveButton("Descartar alterações") { _, _ -> fields.clear(); editor = null; show(if (selected == null) "library" else "detail") }.setNegativeButton("Continuar editando", null).show()
        } else show(if (screen == "detail" && selectedCollection != null) "collection" else "library")
        return true
    }
    fun canPinchColumnsAt(x: Float, y: Float): Boolean {
        if ((!isShelf && !isCollection) || manualDraft!=null) return false
        val grid = activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll)
            .findViewWithTag<View>("shelf-pinch-grid") ?: return false
        val bounds = Rect()
        return grid.getGlobalVisibleRect(bounds) && bounds.contains(x.toInt(), y.toInt())
    }
    fun pinchColumns(scale: Double): Boolean {
        if ((!isShelf && !isCollection) || manualDraft!=null) return false
        val scroll = activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll)
        val grid = scroll.findViewWithTag<ViewGroup>("shelf-pinch-grid") ?: return false
        val collection = if (isCollection) repository.collections().firstOrNull { it.id == selectedCollection } else null
        if(isShelf && spineMode()){
            if(scale<1/pinchStep || scale>pinchStep){
                val next=(spineZoom*if(scale>1)1.15f else 1/1.15f).coerceIn(.75f,1.5f)
                if(next==spineZoom)return false
                spineZoom=next;settings.set("SHELF_SPINE_ZOOM",next.toString());refreshSpines?.invoke();return true
            }
            return false
        }
        val requested = collection?.let { settings.collectionVolumesPerRow(it.id, it.kind) } ?: settings.shelfColumns(shelfType)
        val width = grid.width / activity.resources.displayMetrics.density
        val current = ShelfLayout.columns(width, requested, activity.resources.configuration.fontScale)
        val next = ShelfPinch.columns(current, scale, width, activity.resources.configuration.fontScale,pinchStep,if(pinchStep>1.2) 1 else 5)
        if (next == current) return false
        fun firstVisible(view: View): View? {
            if (view.tag?.toString()?.startsWith("shelf-cover:") == true && view.getGlobalVisibleRect(Rect())) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) firstVisible(view.getChildAt(index))?.let { return it }
            return null
        }
        val anchor = firstVisible(grid) ?: return false
        val tag = anchor.tag
        val location = IntArray(2); anchor.getLocationOnScreen(location)
        val oldTop = location[1]; val oldScroll = scroll.scrollY
        if (collection == null) { shelfScroll = oldScroll; settings.setShelfColumns(shelfType, next) }
        else settings.setCollectionVolumesPerRow(collection.id, next)
        reflowCovers(grid, next, collection != null)
        grid.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (grid.viewTreeObserver.isAlive) grid.viewTreeObserver.removeOnGlobalLayoutListener(this)
            val replacement = tag?.let { scroll.findViewWithTag<View>(it) }
            if (replacement != null) {
                replacement.getLocationOnScreen(location)
                scroll.scrollBy(0, location[1] - oldTop)
            } else scroll.scrollTo(0, oldScroll)
            if (isShelf) shelfScroll = scroll.scrollY
            }
        })
        return true
    }
    /** Reparent existing cover cards: no database reload, image decode or full page rebuild. */
    private fun reflowCovers(grid: ViewGroup, columns: Int, volumes: Boolean) {
        val cells = mutableListOf<View>()
        for (rowIndex in 0 until grid.childCount) {
            val oldRow = grid.getChildAt(rowIndex) as? ViewGroup ?: continue
            for (index in oldRow.childCount - 1 downTo 0) {
                val cell = oldRow.getChildAt(index)
                if (cell.tag?.toString()?.startsWith("shelf-cover:") == true) {
                    oldRow.removeViewAt(index); cells.add(cell)
                }
            }
        }
        // Build the ordered list using the row/column positions captured before removal.
        cells.sortBy { it.getTag(br.com.centralcasa.R.id.shelf_cover_order) as Int }
        grid.removeAllViews()
        val compact = columns >= 5
        val gap = if (volumes) 6 else if (compact) 4 else 8
        cells.chunked(columns).forEach { group ->
            val line = ui.row().apply {
                gravity = Gravity.TOP
                if (volumes) { clipChildren = false; clipToPadding = false; setPadding(0, ui.dp(4), 0, ui.dp(4)) }
            }
            grid.addView(line, LinearLayout.LayoutParams(-1, -2))
            repeat(columns) { index ->
                val cell = group.getOrNull(index) ?: if (volumes) FrameLayout(activity) else ui.column()
                line.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginEnd = ui.dp(if (index == columns - 1) 0 else gap)
                    if (volumes) { topMargin = ui.dp(3); bottomMargin = ui.dp(7) } else bottomMargin = ui.dp(10)
                })
                val labels = if (volumes) (cell as? ViewGroup)?.getChildAt(0) as? ViewGroup else cell as? ViewGroup
                if (labels != null) for (label in 1 until labels.childCount) {
                    labels.getChildAt(label).visibility = if (compact || ui.rectilinear && !volumes) View.GONE else View.VISIBLE
                }
            }
        }
    }
    fun moveBook(direction: Int) {
        if (isCollection) {
            val current = repository.collections().firstOrNull { it.id == selectedCollection } ?: return
            val volumes = repository.books()
            val titles = repository.collections().filter { it.kind == current.kind }.map { it.shelfBook(volumes) }
            val filtered = shelfFilters.select(titles, null)
            val matches=if(filtered.any { it.id==current.id }) filtered else ShelfFilters(order=shelfFilters.order).select(titles,null)
            val ordered=if(useManualShelfOrder && (manualView || manualIsDefault() && shelfFilters.order.isEmpty())) ManualShelfOrder.select(matches,savedManualOrder()) else matches
            val index = ordered.indexOfFirst { it.id == current.id }
            ordered.getOrNull(index + direction)?.let {
                selectedCollection = it.id; selected = null; historyExpanded = false; shownSessions = 4; show("collection")
            } ?: toast(if (direction > 0) "Último título desta lista" else "Primeiro título desta lista")
            return
        }
        if (!isDetail) return
        val books = repository.books().filter { if (selectedCollection != null) it.collectionId == selectedCollection else it.collectionId.isBlank() }
        val filtered = shelfFilters.select(books, repository.currentId())
        val matches=if(filtered.size>1 && filtered.any { it.id==selected }) filtered else books
        val ordered=if(selectedCollection==null && useManualShelfOrder && (manualView || manualIsDefault() && shelfFilters.order.isEmpty())) ManualShelfOrder.select(matches,savedManualOrder()) else matches
        val index = ordered.indexOfFirst { it.id == selected }
        ordered.getOrNull(index + direction)?.let { open(it.id) } ?: toast(if (direction > 0) "Último livro desta lista" else "Primeiro livro desta lista")
    }
    /** Opens the existing editor; nothing is added until the user saves it. */
    fun addFromCatalog(record:br.com.centralcasa.books.catalog.CatalogBook?=null) {
        fields.clear();collectionFields.clear();selected=null;selectedCollection=null
        catalogCover=""
        if(record!=null && record.kind!="book") {
            editor=null
            collectionEditor=CollectionTitle(kind=record.kind,title=record.title,author=record.author,publisher=record.publisher,genre=record.genre,
                subgenres=record.subgenre.split(';').map(String::trim).filter(String::isNotBlank).distinct().take(3))
            collectionEditorNew=true;collectionOwned="0"
            show("collection_editor")
        } else {
            collectionEditor=null;newBook=true
            editor=record?.fill(LibraryBook(title="",totalPages=0)) ?: LibraryBook(title="",totalPages=0)
            show("editor")
        }
        val id=editor?.id ?: collectionEditor?.id
        val generation=catalogGeneration
        if(record!=null && record.coverUrl.isNotBlank()) catalog?.loadCover(record) { loaded ->
            if(!destroyed && catalogGeneration==generation && (editor?.id==id || collectionEditor?.id==id)) {
                loaded.fold({ name ->
                    if(screen=="editor") { captureEditor();if(editor?.cover.isNullOrBlank()) { catalogCover=name;editor=editor?.copy(cover=name);fields.clear();changed() } }
                    else if(screen=="collection_editor") { captureCollectionEditor();if(collectionEditor?.cover.isNullOrBlank()) { collectionEditor=collectionEditor?.copy(cover=name);collectionFields.clear();changed() } }
                },{ toast("Dados preenchidos. Escolha uma capa se o carregamento não estiver disponível.") })
            }
        }
    }
    fun openShelf() { fields.clear();editor=null;collectionFields.clear();collectionEditor=null;selected=null;selectedCollection=null;show("library") }
    fun openCurrent() { selectedCollection = repository.current()?.collectionId?.takeIf { it.isNotBlank() }; selected = repository.currentId(); show(if (selected == null) "library" else "detail") }
    fun openBook(id: String) { clearPlanSelection(); selectedCollection = repository.book(id)?.collectionId?.takeIf { it.isNotBlank() }; open(id) }
    private fun show(destination: String) {
        if (screen == "library") shelfScroll = activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll).scrollY
        catalogGeneration++
        screen = destination; timerLabel = null; changed()
        activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll).post {
            activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll).scrollTo(0, if (destination == "library") shelfScroll else 0)
        }
    }
    private fun cancelManualOrder() {
        manualDraft=null;manualView=false;shelfFilters=loadShelfFilters();stopManualScroll();changed()
    }
    private fun startShelfDrag(view:View,id:String) {
        if(!view.isAttachedToWindow || id !in manualDraft.orEmpty()) return
        val data=android.content.ClipData.newPlainText("Estante","")
        if(android.os.Build.VERSION.SDK_INT>=24) view.startDragAndDrop(data,View.DragShadowBuilder(view),id,0)
        else @Suppress("DEPRECATION") view.startDrag(data,View.DragShadowBuilder(view),id,0)
    }
    private fun startShelfDragAfterLayout(id:String) {
        val scroll=activity.findViewById<ReadingScrollView>(br.com.centralcasa.R.id.content_scroll) ?: return
        val target=scroll.findViewWithTag<View>("shelf-cover:$id") ?: return
        if(target.isLaidOut) { if(scroll.isTouching) startShelfDrag(target,id);return }
        target.addOnLayoutChangeListener(object:View.OnLayoutChangeListener {
            override fun onLayoutChange(view:View,left:Int,top:Int,right:Int,bottom:Int,oldLeft:Int,oldTop:Int,oldRight:Int,oldBottom:Int) {
                view.removeOnLayoutChangeListener(this)
                if(scroll.isTouching && manualDraft!=null) startShelfDrag(view,id)
            }
        })
    }
    private fun beginManualOrder(items:List<LibraryBook>) {
        val base=if(manualIsDefault() || manualView) ManualShelfOrder.select(items,savedManualOrder()) else shelfFilters.copy(collectionCompletion=CollectionCompletion.ALL,query="",status=ShelfStatus.ALL,genre=null,subgenre=null,saga=null,author=null,covers=ShelfCovers.ALL,ratings=ShelfRatings.ALL,minimumRating=null).select(items,null)
        persistShelfFilters();manualDraft=base.map { it.id };shelfFilters=ShelfFilters();shelfScroll=0;changed()
    }
    private fun shelfOrderDefaults() = if (shelfType in listOf("manga", "comic")) listOf(ShelfOrder(ShelfField.GENRE), ShelfOrder(ShelfField.TITLE), ShelfOrder(ShelfField.AUTHOR)) else ShelfOrder.defaults
    private fun loadShelfFilters(): ShelfFilters = runCatching {
        val key = "BOOK_SHELF_FILTERS:$shelfType"
        if (settings.get("SHELF_FILTERS_BY_TYPE_MIGRATED").isBlank()) {
            settings.set(key, settings.get("BOOK_SHELF_FILTERS"))
            settings.set("SHELF_FILTERS_BY_TYPE_MIGRATED", "true")
        }
        val stored = settings.get(key)
        if (stored.isBlank()) return@runCatching ShelfFilters(order = if(manualIsDefault())emptyList() else shelfOrderDefaults())
        val data = org.json.JSONObject(stored)
        ShelfFilters(collectionCompletion=CollectionCompletion.valueOf(data.optString("completion","ALL")), query = data.optString("query"), status = ShelfStatus.valueOf(data.optString("status", "ALL")),
            subgenre = data.optString("subgenre").takeIf { it.isNotBlank() }, genre = data.optString("genre").takeIf { it.isNotBlank() }, saga = data.optString("saga").takeIf { it.isNotBlank() },
            author = data.optString("author").takeIf { it.isNotBlank() }, covers = ShelfCovers.valueOf(data.optString("covers", "ALL")),
            ratings = ShelfRatings.valueOf(data.optString("ratings", "ALL")), minimumRating = if (data.isNull("minimumRating")) null else data.getDouble("minimumRating"),
            order = ShelfOrder.decode(data.optString("order")).ifEmpty { if(manualIsDefault())emptyList() else shelfOrderDefaults() })
    }.getOrDefault(ShelfFilters(order = shelfOrderDefaults())).let(::migrateShelfFilter)
    private fun migrateShelfFilter(value: ShelfFilters): ShelfFilters {
        val category = BookGenres.split(value.genre.orEmpty())
        return value.copy(collectionCompletion=if(shelfType=="book")CollectionCompletion.ALL else value.collectionCompletion, genre = category.genre.takeIf(String::isNotBlank), subgenre = value.subgenre ?: category.subgenre.takeIf(String::isNotBlank),
            saga = value.saga.takeUnless { shelfType in listOf("manga", "comic") },
            order = value.order.filter { shelfType !in listOf("manga", "comic") || it.field !in listOf(ShelfField.SAGA, ShelfField.SEQUENCE) }.ifEmpty { if(manualIsDefault() || manualDraft!=null || useManualShelfOrder && manualView) emptyList() else shelfOrderDefaults() })
    }
    private fun persistShelfFilters() {
        if(manualDraft!=null) return
        settings.set("BOOK_SHELF_FILTERS:$shelfType", org.json.JSONObject().apply {
            put("completion",shelfFilters.collectionCompletion.name);put("query", shelfFilters.query); put("status", shelfFilters.status.name); put("genre", shelfFilters.genre.orEmpty()); put("subgenre", shelfFilters.subgenre.orEmpty())
            put("saga", shelfFilters.saga.orEmpty()); put("author", shelfFilters.author.orEmpty()); put("covers", shelfFilters.covers.name)
            put("ratings", shelfFilters.ratings.name); put("minimumRating", shelfFilters.minimumRating ?: org.json.JSONObject.NULL)
            put("order", ShelfOrder.encode(shelfFilters.order))
        }.toString())
    }
    private fun open(id: String) {
        val picker = onPlanBookSelected
        if (planSelectionTitle != null && picker != null) {
            val picked = repository.book(id)
            if (picked != null) {
                picker(picked)
                return
            }
        }
        selected = id; shownSessions = 4; historyExpanded = false; show("detail")
    }
    private fun renderPlanSelectionBanner(parent: LinearLayout) {
        val label = planSelectionTitle ?: return
        val banner = row(parent).apply {
            tag = "plan-selection-banner"
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(10), ui.dp(10))
            background = ui.outlined(ui.activeSurface, ui.accent, 0)
        }
        (banner.layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(10)
        val textCol = ui.column()
        banner.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
        ui.label(textCol, "ADICIONAR EM ${label.uppercase(Locale("pt", "BR"))}", 10, ui.accent, bold = true).apply {
            letterSpacing = 0.08f
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(2)
        }
        ui.label(textCol, "Toque em um livro (ou volume de coleção) para adicionar à meta.", 11, ui.muted).apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        }
        TextView(activity).apply {
            text = "Cancelar"
            textSize = 11f
            setTextColor(ui.text)
            background = ui.outlined(ui.surface, ui.lineColor, 0)
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
            setOnClickListener {
                val cancel = onCancelPlanSelection
                clearPlanSelection()
                cancel?.invoke() ?: changed()
            }
        }.also { banner.addView(it, LinearLayout.LayoutParams(-2, -2)) }
    }
    private fun backgroundBook():LibraryBook?=when(screen){
        "detail"->selected?.let(repository::book)
        "collection"->repository.collections().firstOrNull {it.id==selectedCollection}?.shelfBook(repository.books())
        else->null
    }
    fun detailBackgroundColor():Int? {
        if(!ui.rectilinear || busy)return null
        val book=backgroundBook() ?: return null
        return DisplayUi(activity).apply {scopedReadingColor(images.coverColor(book.cover))}.background
    }
    fun screenBackground(): android.graphics.drawable.Drawable? {
        if(busy)return null
        if(ui.rectilinear){
            val book=backgroundBook() ?: return ui.shape(ui.background,0)
            val color=detailBackgroundColor() ?: ui.background
            return if(book.cover.isNotBlank() && images.file(book.cover)!=null)images.background(book.copy(background="cover")) else ui.shape(color,0)
        }
        if (screen == "library") {
            val color = shelfThemeColor() ?: return if(spineMode())ui.shape(ui.background,0) else null
            return android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(shelfTint(color, .32f), shelfTint(color, .12f), ui.background))
        }
        val book=backgroundBook() ?: return null
        return if (book.cover.isNotBlank()) images.background(book.copy(background = "cover")) else ui.shape(ui.background, 0)
    }

    private fun genrePreview(parent: LinearLayout, genre: String, color: Int, exampleTitle: String = ""): LinearLayout {
        val stroke = (color and 0x00ffffff) or 0xCC000000.toInt()
        val preview = ui.column().apply {
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(shelfTint(color, .32f), shelfTint(color, .12f), ui.background)).apply {
                    cornerRadius = ui.radius(12); setStroke(ui.dp(1), stroke)
                }
        }
        parent.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
        ui.label(preview, genre, 18, bold = true)
        ui.label(preview, "ESTANTE · PRÉVIA DO FILTRO", 9, ui.muted).apply { letterSpacing = .06f }
        ui.label(preview, "Buscar na estante", 11, ui.muted).apply {
            background = ui.outlined(shelfTint(color, .13f), stroke)
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(6)
        }
        ui.label(preview, "Filtrar e ordenar", 11).apply {
            background = ui.outlined(shelfTint(color, .13f), stroke)
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        }
        val examples = repository.books().filter { it.genre.trim().ifBlank { "Sem gênero" }.equals(genre, true) }
        val samples = examples.filter { it.cover.isNotBlank() }.take(3)
        if (samples.isNotEmpty()) {
            val covers = row(preview).apply { setPadding(0, ui.dp(6), 0, ui.dp(6)); gravity = Gravity.CENTER }
            samples.forEach { book ->
                val shell = ShelfCoverFrame(activity, book, repository.currentId() == book.id, stroke)
                covers.addView(shell, LinearLayout.LayoutParams(ui.dp(54), ui.dp(80)).apply { marginEnd = ui.dp(6) })
                shell.background = ui.shape(shelfTint(color, .16f), 10)
                shell.addView(ImageView(activity).apply {
                    setImageBitmap(images.thumbnail(book.cover)); scaleType = ImageView.ScaleType.FIT_CENTER
                }, FrameLayout.LayoutParams(-1, -1))
            }
        }
        ui.label(preview, "Tema aplicado à Estante e às bordas das capas", 10, ui.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0)
        }
        return preview
    }
    fun customizeGenreColors(onChanged: () -> Unit = changed): AlertDialog {
        val books = repository.books() + repository.collections().map { it.shelfBook(emptyList()) }
        val genres = (books.map { it.genre.trim().ifBlank { "Sem gênero" } } + "Sem gênero").distinct().sorted()
        val body = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(12)) }
        val scroll = ScrollView(activity).apply { addView(body) }
        fun renderPreviews() {
            body.removeAllViews()
            ui.label(body, "Estas cores personalizam apenas a Estante: tema do filtro e bordas das capas. Dentro de livros, mangás e quadrinhos, os chips e cartões seguem a cor da capa.", 12, ui.muted)
            genres.forEach { genre ->
                val color = settings.genreColor(genre)
                genrePreview(body, genre, color, books.firstOrNull { it.genre.trim().ifBlank { "Sem gênero" }.equals(genre, true) }?.title.orEmpty()).apply {
                    contentDescription = "Escolher cor de $genre"; isFocusable = true
                    setOnClickListener { genrePalette(genre) { renderPreviews(); onChanged() } }
                }
            }
        }
        renderPreviews()
        return AlertDialog.Builder(activity).setTitle("Cores por gênero").setView(scroll).setNegativeButton("Fechar", null).show()
    }
    private fun genrePalette(genre: String, saved: () -> Unit): AlertDialog {
        val exampleTitle = repository.books().firstOrNull { it.genre.trim().ifBlank { "Sem gênero" }.equals(genre, true) }?.title.orEmpty()
        var color = settings.genreColor(genre)
        val hsv = FloatArray(3); Color.colorToHSV(color, hsv)
        val body = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)) }
        val previewHost = ui.column(); body.addView(previewHost)
        val controls = row(body).apply { gravity = Gravity.CENTER_VERTICAL }
        lateinit var wheel: ColorWheel
        lateinit var value: SeekBar
        lateinit var brightness: TextView
        var saturationControl: SeekBar? = null
        var saturationCaption: TextView? = null
        fun repaint() {
            color = Color.HSVToColor(hsv)
            previewHost.removeAllViews(); genrePreview(previewHost, genre, color, exampleTitle)
            brightness.text = "Luminosidade · ${value.progress}%"
            saturationControl?.progress = Math.round(hsv[1] * 100)
            saturationCaption?.text = "Saturação · ${Math.round(hsv[1] * 100)}%"
        }
        fun chooseHue(rgb: br.com.centralcasa.services.Rgb) {
            val next = FloatArray(3); Color.RGBToHSV(rgb.red, rgb.green, rgb.blue, next)
            hsv[0] = next[0]; hsv[1] = 1f; hsv[2] = value.progress / 100f
            repaint()
        }
        wheel = ColorWheel(activity, br.com.centralcasa.services.Rgb(Color.red(color), Color.green(color), Color.blue(color)), {}, { chooseHue(it) }) { chooseHue(it) }
        controls.addView(wheel, LinearLayout.LayoutParams(ui.dp(128), ui.dp(128)).apply { marginEnd = ui.dp(10) })
        val choices = ui.column(); controls.addView(choices, LinearLayout.LayoutParams(0, -2, 1f))
        listOf("Verde" to Color.GREEN, "Vermelho" to Color.RED, "Azul" to Color.BLUE, "Laranja" to Color.rgb(255, 128, 0), "Rosa" to Color.rgb(255, 0, 128), "Amarelo" to Color.YELLOW).chunked(3).forEach { group ->
            val colors = row(choices)
            group.forEach { (name, shade) ->
                val swatch = FrameLayout(activity).apply {
                    isFocusable = true; contentDescription = "$name para $genre"; background = ui.ripple(Color.TRANSPARENT)
                    setOnClickListener {
                        val rgb = br.com.centralcasa.services.Rgb(Color.red(shade), Color.green(shade), Color.blue(shade))
                        wheel.setColor(rgb); chooseHue(rgb)
                    }
                }
                colors.addView(swatch, LinearLayout.LayoutParams(0, ui.dp(40), 1f))
                swatch.addView(View(activity).apply { background = ui.shape(shade, 16) }, FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
            }
        }
        brightness = ui.label(body, "Luminosidade", 12, ui.muted)
        value = ui.slider(body, Math.round(hsv[2] * 100), "Luminosidade de $genre") { progress -> hsv[2] = progress / 100f; repaint() }
        ui.styleSlider(value)
        val saturationLabel = ui.label(body, "Saturação · ${Math.round(hsv[1] * 100)}%", 12, ui.muted)
        val saturation = ui.slider(body, Math.round(hsv[1] * 100), "Saturação de $genre") { progress ->
            hsv[1] = progress / 100f; saturationLabel.text = "Saturação · $progress%"; repaint()
        }
        ui.styleSlider(saturation); saturationControl = saturation; saturationCaption = saturationLabel
        val neutrals = row(body)
        listOf("Preto" to Color.BLACK, "Grafite" to Color.rgb(34, 34, 34), "Claro" to Color.rgb(230, 230, 230)).forEach { (name, shade) ->
            ui.action(neutrals, name) {
                Color.colorToHSV(shade, hsv); value.progress = Math.round(hsv[2] * 100)
                wheel.setColor(br.com.centralcasa.services.Rgb(Color.red(shade), Color.green(shade), Color.blue(shade))); repaint()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) }; minHeight = ui.dp(36) }
        }
        repaint()
        val scroll = ScrollView(activity).apply { addView(body) }
        return AlertDialog.Builder(activity).setTitle("Paleta de $genre").setView(scroll)
            .setPositiveButton("Salvar cor") { _, _ -> settings.setGenreColor(genre, color); saved() }
            .setNeutralButton("Restaurar padrão") { _, _ -> settings.setGenreColor(genre, null); saved() }
            .setNegativeButton("Cancelar", null).show()
    }
    fun render(parent: LinearLayout) {
        captureEditor(); captureCollectionEditor(); timerLabel = null
        if (busy) { ui.label(parent, "Preparando sua biblioteca…", 22, bold = true); ui.label(parent, "Aguarde a conclusão do arquivo.", 14, ui.muted); return }
        when (screen) { "collection_editor" -> collectionEdit(parent); "collection" -> collectionDetail(parent); "detail" -> detail(parent); "editor" -> edit(parent); "stats" -> statistics(parent); else -> library(parent) }
        if (visible) { handler.removeCallbacks(tick); handler.post(tick) }
    }
    private fun row(parent: LinearLayout): LinearLayout = ui.row().also { parent.addView(it) }
    private fun actions(parent: LinearLayout, vararg items: Pair<String, () -> Unit>) {
        val r = row(parent); items.forEachIndexed { i, item ->
            val col = ui.column(); r.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i < items.lastIndex) marginEnd = ui.dp(8) }); ui.action(col, item.first, onClick = item.second)
        }
    }
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    private fun attempt(action: () -> Unit) { try { action() } catch (e: Exception) { toast(e.message ?: "Não foi possível concluir.") } }
    private fun cover(parent: LinearLayout, book: LibraryBook, width: Int, height: Int) {
        val bitmap = images.thumbnail(book.cover)
        if (bitmap != null) parent.addView(ImageView(activity).apply {
            setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Capa de ${book.title}"
            background = ui.shape(ui.activeSurface, 12)
        }, LinearLayout.LayoutParams(ui.dp(width), ui.dp(height)).apply { marginEnd = ui.dp(14) })
        else {
            val placeholder = ui.column().apply { background = ui.shape(ui.activeSurface, 12); setPadding(ui.dp(10), ui.dp(14), ui.dp(10), ui.dp(8)); gravity = Gravity.CENTER }
            parent.addView(placeholder, LinearLayout.LayoutParams(ui.dp(width), ui.dp(height)).apply { marginEnd = ui.dp(14) })
            placeholder.addView(ImageView(activity).apply {
                setImageDrawable(emptyCoverDrawable()); scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp((width * .65f).toInt()), ui.dp((height * .4f).toInt())))
            ui.label(placeholder, book.title, if (width > 90) 16 else if (width < 60) 9 else 12, bold = true).apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END }
        }
    }
    fun restBook(): RestBook? = repository.current()?.let { b ->
        RestBook(b.title, b.author, images.thumbnail(b.cover),
            if (b.pagesKnown) "${b.currentPage} / ${b.totalPages} páginas · ${b.percent}%" else "Volume ${b.sequenceNumber ?: ""}",
            b.percent, if (b.complete) "✓ Leitura concluída" else "Leitura em andamento")
    }
    fun homeCard(parent: LinearLayout, compact: Boolean) {
        val b = repository.current()
        val panel = ui.column().apply {
            background = ui.outlined(); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            isFocusable = true; contentDescription = "Abrir livro atual e biblioteca"; setOnClickListener { openCurrent() }
        }
        parent.addView(panel, LinearLayout.LayoutParams(-1, -1))
        ui.label(panel, "CONTINUAR LEITURA", 9, ui.text).apply { letterSpacing = .14f }
        val body = ui.row(); panel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val coverImage = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Capa do livro atual"
            val bitmap = b?.let { images.thumbnail(it.cover) }
            if (bitmap != null) setImageBitmap(bitmap) else setImageDrawable(emptyCoverDrawable())
        }
        body.addView(coverImage, LinearLayout.LayoutParams(ui.dp(if (compact) 58 else 76), -1).apply { marginEnd = ui.dp(12) })
        val info = ui.column(); body.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        ui.label(info, b?.title ?: "Sua próxima leitura", if (compact) 18 else 22).apply { typeface = Typeface.create("serif", Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        ui.label(info, b?.author.orEmpty().ifBlank { "Adicionar ou importar livros" }, 11, ui.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        if (b != null) {
            ui.label(info, if (b.pagesKnown) "${b.currentPage} / ${b.totalPages} páginas · ${b.percent}%" else "Volume ${b.sequenceNumber ?: ""} · ${if (b.complete) "Lido" else "Páginas não informadas"}", 10, ui.text)
            ui.progress(info, b.percent).apply { layoutParams = LinearLayout.LayoutParams(-1, ui.dp(3)).apply { topMargin = ui.dp(5); bottomMargin = ui.dp(8) } }
            val draft = repository.draft()?.takeIf { it.bookId == b.id }
            ui.label(info, draft?.let { "${readingTime(repository.elapsed(it))} · ${if (it.runningSince == null) "Em pausa" else "Em leitura"}" } ?: if (b.complete) "Leitura concluída" else "Continuar leitura", 10, ui.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        }
        ui.icon(body, "chevron", "Abrir livro atual", 32) { openCurrent() }.apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) }
    }
    /** Compact current reading widget, without changing the full card used by Viblio. */
    fun homeSquare(parent: LinearLayout, wide: Boolean, span: Int = if(wide) 2 else 1) {
        val b = repository.current()
        parent.gravity = android.view.Gravity.CENTER
        parent.contentDescription = b?.let { "Abrir ${it.title}, ${it.percent}%" } ?: "Escolher livro atual"
        parent.setOnClickListener { openCurrent() }
        val body = if (wide) ui.row() else ui.column()
        body.gravity = android.view.Gravity.CENTER; parent.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val cover = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val bitmap = b?.let { images.thumbnail(it.cover) }
            if (bitmap != null) setImageBitmap(bitmap) else setImageDrawable(emptyCoverDrawable())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        body.addView(cover, LinearLayout.LayoutParams(if (wide) ui.dp(64) else -1, if (wide) -1 else 0, if (wide) 0f else 1f))
        val info = if (wide) ui.column().also { body.addView(it, LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=ui.dp(10) }) } else body
        if(wide) ui.label(info,"Livro",9,ui.muted,true).apply { tag="home-caption"; letterSpacing=0.1f }
        ui.label(info, b?.title ?: "Livro atual", 17).apply { maxLines=if(wide) 2 else 1; ellipsize=android.text.TextUtils.TruncateAt.END; gravity=if(wide) android.view.Gravity.START else android.view.Gravity.CENTER }
        if(wide) ui.label(info, b?.author.orEmpty(), 11, ui.muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
        if(wide) ui.label(info,if(span==3 && b?.pagesKnown==true) "${b.currentPage} / ${b.totalPages} páginas · ${b.percent}%" else "${b?.percent ?: 0}% lido",11,ui.muted)
        ui.progress(parent, b?.percent ?: 0).apply {
            layoutParams=LinearLayout.LayoutParams(-1,ui.dp(3))
            progressTintList=android.content.res.ColorStateList.valueOf(b?.let(::readingAccent) ?: ui.accent)
        }
    }
    private fun shelfThemeColor(): Int? = shelfFilters.genre?.takeIf { it.isNotBlank() && it != "Sem gênero" }?.let(settings::genreColor)
    private fun shelfTint(color: Int, amount: Float): Int = Color.rgb(
        (Color.red(color) * amount + Color.red(ui.background) * (1 - amount)).toInt(),
        (Color.green(color) * amount + Color.green(ui.background) * (1 - amount)).toInt(),
        (Color.blue(color) * amount + Color.blue(ui.background) * (1 - amount)).toInt())
    private fun library(parent: LinearLayout) {
        spineZoom=(settings.get("SHELF_SPINE_ZOOM").toFloatOrNull() ?: 1f).coerceIn(.75f,1.5f)
        val arranging=manualDraft!=null
        val useManual=useManualShelfOrder && (arranging || manualView || manualIsDefault() && shelfFilters.order.isEmpty())
        val theme = if(arranging) null else shelfThemeColor()
        val themeStroke = theme?.let { (it and 0x00ffffff) or 0xCC000000.toInt() }
        val volumes = repository.books()
        val collections = repository.collections().filter { shelfType == "all" || it.kind == shelfType }
        val all = volumes.filter { it.collectionId.isBlank() && (shelfType == "all" || it.kind == shelfType) } + collections.map { it.shelfBook(volumes) }
        if(manualIsDefault() && !arranging) {
            val saved=savedManualOrder();val reconciled=ManualShelfOrder.reconcile(saved,all.map { it.id })
            if(reconciled!=saved && !usePersonalArchive) settings.set("SHELF_MANUAL_ORDER:$shelfType",org.json.JSONArray(reconciled).toString())
        }
        val currentShelfId = repository.current()?.let { it.collectionId.ifBlank { it.id } }
        renderPlanSelectionBanner(parent)
        val header = row(parent)
        ui.label(header, if(ui.rectilinear)"ESTANTE" else "Estante", if(ui.rectilinear)30 else 36, bold = true).apply {
            typeface = Typeface.create("serif", if(ui.rectilinear)Typeface.NORMAL else Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        ui.action(header, "${CollectionKinds.labels[shelfType]} ▾") {
            val types = CollectionKinds.labels.keys.toList()
            AlertDialog.Builder(activity).setTitle("Mostrar na estante").setSingleChoiceItems(types.map { CollectionKinds.labels[it] }.toTypedArray(), types.indexOf(shelfType)) { dialog, index ->
                persistShelfFilters(); shelfType = types[index];manualView=false; shelfFilters = loadShelfFilters(); settings.set("SHELF_TYPE", shelfType); selectedCollection = null; shelfScroll = 0; dialog.dismiss(); changed()
            }.setNegativeButton("Cancelar", null).show()
        }.apply { textSize = 10f; minWidth = 0; setPadding(ui.dp(6), 0, ui.dp(6), 0); layoutParams = LinearLayout.LayoutParams(ui.dp(72), ui.dp(36)) }
        if(!separateShelfStatistics) ui.icon(header, "stats", "Estatísticas", 40) { show("stats") }
        ui.icon(header, "add", "Adicionar à estante", 40) {
            if (shelfType in listOf("manga", "comic")) editCollection(null)
            else AlertDialog.Builder(activity).setTitle("Adicionar").setItems(arrayOf("Livro", "Mangá", "Quadrinho")) { _, which ->
                if (which == 0) { fields.clear(); selected = null; selectedCollection = null; editor = LibraryBook(title = "", totalPages = 0); newBook = true; show("editor") }
                else editCollection(null, if (which == 1) "manga" else "comic")
            }.show()
        }
        if(useSpineLayout)ui.icon(header,if(spineMode())"book-spines" else "shelf-covers",if(spineMode())"Mostrar capas" else "Mostrar lombadas",40){
            settings.set("SHELF_SPINE_VIEW:$shelfType",(!spineMode()).toString());shelfScroll=0;changed()
        }.apply {tag="shelf-view-toggle";isSelected=spineMode()}
        ui.icon(header, "more", "Biblioteca e backup", 36) {
            val options=arrayOf("Livro atual", if(jsonAllowed())"Backup / importar" else "Catálogo do servidor", "Capas por linha · ${CollectionKinds.labels[shelfType]}", "Cores por gênero") + (if(useManualShelfOrder) arrayOf("Organizar estante", "Minha ordem · limpar filtros") else emptyArray()) + (if(onAccount!=null)arrayOf("Conta") else emptyArray())
            AlertDialog.Builder(activity).setTitle("Estante").setItems(options) { _, index ->
                if(options[index]=="Conta"){onAccount?.invoke();return@setItems}
                when (index) {
                    0 -> openCurrent(); 1 -> backupMenu(); 3 -> customizeGenreColors()
                    4 -> beginManualOrder(all)
                    5 -> { shelfFilters=ShelfFilters();manualView=true;shelfScroll=0;persistShelfFilters();changed() }
                    2 -> AlertDialog.Builder(activity).setTitle("Capas por linha · ${CollectionKinds.labels[shelfType]}").setSingleChoiceItems((1..6).map { "$it" }.toTypedArray(), settings.shelfColumns(shelfType) - 1) { dialog, which -> settings.setShelfColumns(shelfType, which + 1); dialog.dismiss(); changed() }.setNegativeButton("Cancelar", null).show()
                }
            }.show()
        }
        if(arranging) for(index in 0 until header.childCount) header.getChildAt(index).isEnabled=false
        ui.label(parent, "${all.size} " + (if (shelfType == "book") "livros" else "títulos"), 11, ui.muted)
        if(arranging) {
            ui.label(parent,"Segure uma capa e arraste para a posição desejada.",12,ui.muted)
            val controls=row(parent)
            weightedActionOn(controls,"Salvar como principal","Salvar organização principal") {
                val order=ManualShelfOrder.reconcile(manualDraft.orEmpty(),all.map { it.id })
                settings.set("SHELF_MANUAL_ORDER:$shelfType",org.json.JSONArray(if(usePersonalArchive)ManualShelfOrder.withHidden(savedManualOrder(),order) else order).toString())
                settings.set("SHELF_MANUAL_DEFAULT:$shelfType","true")
                manualDraft=null;manualView=true;shelfFilters=ShelfFilters();stopManualScroll();persistShelfFilters();changed();toast("Sua ordem foi salva como principal")
            }
            weightedActionOn(controls,"Cancelar","Cancelar organização") { cancelManualOrder() }
        }
        val query = input(parent, if (shelfType == "book") "Buscar livros" else if (shelfType == "all") "Buscar na estante" else "Buscar ${CollectionKinds.labels[shelfType]?.lowercase()}", shelfFilters.query, false).apply {
            contentDescription = "Buscar livros"; minHeight = ui.dp(40);visibility=if(arranging) View.GONE else View.VISIBLE
            if (theme != null) background = ui.outlined(shelfTint(theme, .13f), requireNotNull(themeStroke))
            layoutParams = LinearLayout.LayoutParams(-1, ui.dp(42)).apply { topMargin = ui.dp(6); bottomMargin = ui.dp(8) }
            setCompoundDrawables(ui.symbol("search", ui.muted, 16), null, null, null); compoundDrawablePadding = ui.dp(8)
        }
        val filterButton = ui.action(parent, "Filtrar e ordenar") { filterDialog(all) }.apply {
            contentDescription = "Filtrar livros"; minHeight = ui.dp(40);visibility=if(arranging) View.GONE else View.VISIBLE
            background = if (theme != null) ui.outlined(shelfTint(theme, .13f), requireNotNull(themeStroke)) else ui.outlined()
            gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawables(ui.symbol("filter"), null, ui.symbol("chevron"), null); compoundDrawablePadding = ui.dp(8)
            layoutParams = LinearLayout.LayoutParams(-1, ui.dp(42)).apply { bottomMargin = ui.dp(8) }
        }
        val resultInfo = ui.label(parent, "", 10, ui.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(2), 0, ui.dp(4)) }
        val grid = ui.column().apply { tag = "shelf-pinch-grid" }
        if(arranging)grid.setOnDragListener {view,event->
            when(event.action){
                android.view.DragEvent.ACTION_DRAG_STARTED->{val from=event.localState as? String;from!=null && from in manualDraft.orEmpty()}
                android.view.DragEvent.ACTION_DRAG_LOCATION->{manualScroll(view,event.y);true}
                android.view.DragEvent.ACTION_DRAG_ENDED->{stopManualScroll();true}
                else->false
            }
        }

        parent.addView(grid)
        if(useAsyncShelfImages || spineMode()) shelfThumbnails.attach(grid)
        var renderedColumns = 0
        val refresh:()->Unit = refresh@{
            if(useAsyncShelfImages || spineMode()) shelfThumbnails.reset()
            grid.removeAllViews()
            val candidates=if(!useCollectionInventory || shelfFilters.collectionCompletion==CollectionCompletion.ALL)all else all.filter {book->
                collections.firstOrNull {it.id==book.id}?.let {CollectionInventory.of(it,volumes).status==shelfFilters.collectionCompletion} ?: false
            }
            val filtered=shelfFilters.select(candidates,currentShelfId)
            val matches=if(useManual) ManualShelfOrder.select(if(arranging) all else filtered,manualDraft ?: savedManualOrder()) else filtered
            filterButton.text = (if(useManual) "Filtrar · Minha ordem" else "Filtrar e ordenar · ${shelfFilters.order.size} critérios") + if (shelfFilters.activeCount > 0) " · ${shelfFilters.activeCount} filtros" else ""
            val active = listOfNotNull(shelfFilters.collectionCompletion.takeIf {it!=CollectionCompletion.ALL}?.label,shelfFilters.status.takeIf { it != ShelfStatus.ALL }?.label, shelfFilters.genre, shelfFilters.subgenre, shelfFilters.saga, shelfFilters.author, shelfFilters.covers.takeIf { it != ShelfCovers.ALL }?.label)
            resultInfo.text = (if(useManual) "Minha ordem" else shelfFilters.order.joinToString(" › ") { it.label }) + if (active.isEmpty() && shelfFilters.query.isBlank()) "" else "\n${matches.size} de ${all.size} livros" + if (active.isEmpty()) "" else " · ${active.joinToString(" · ")}"
            if (matches.isEmpty()) { ui.label(grid, if (all.isEmpty()) "Sua biblioteca começa aqui" else "Nenhum livro com esses filtros", 20, bold = true); ui.label(grid, "Adicione um livro ou importe seu JSON pelo botão de backup.", 12, ui.muted) }
            val currentBookId = currentShelfId
            val availableWidth = if (grid.width > 0) grid.width / activity.resources.displayMetrics.density else (activity.resources.configuration.screenWidthDp - 40).toFloat()
            if(spineMode() && !arranging){
                renderSpines(grid,matches,collections,volumes,availableWidth,all);return@refresh
            }
            val columns = ShelfLayout.columns(availableWidth, settings.shelfColumns(shelfType), activity.resources.configuration.fontScale)
            renderedColumns = columns
            val compactCovers = columns >= 5
            var coverOrder = 0
            val gap = if (compactCovers) 4 else 8
            matches.chunked(columns).forEach { books ->
                val r = row(grid); r.gravity = Gravity.TOP
                for (x in 0 until columns) {
                    val book = books.getOrNull(x)
                    val cell = ui.column().apply { gravity = Gravity.TOP; background = ui.ripple(Color.TRANSPARENT, 6) }
                    r.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(if (x == columns - 1) 0 else gap); bottomMargin = ui.dp(10) })
                    if (book != null) {
                        val state = when {
                            book.complete -> "Concluído"
                            book.currentPage == 0 -> "Não iniciado"
                            currentBookId == book.id -> "Livro atual, em leitura"
                            else -> "Em leitura"
                        }
                        cell.tag = "shelf-cover:${book.id}"
                        cell.setTag(br.com.centralcasa.R.id.shelf_cover_order, coverOrder++)
                        cell.contentDescription = "${book.title}, $state, ${book.percent} por cento"
                        cell.isFocusable = true
                        if(useManualShelfOrder) cell.setOnLongClickListener {
                            if(manualDraft==null) {
                                val touching=(activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll) as? ReadingScrollView)?.isTouching==true
                                beginManualOrder(all)
                                if(touching) startShelfDragAfterLayout(book.id)
                            } else startShelfDrag(cell,book.id)
                            true
                        }
                        cell.setOnClickListener {
                            if(manualDraft!=null) return@setOnClickListener
                            if (collections.any { it.id == book.id }) { selectedCollection = book.id; historyExpanded = false; shownSessions = 4; show("collection") }
                            else { selectedCollection = null; open(book.id) }
                        }
                        val coverFrame = ShelfCoverFrame(activity, book, currentBookId == book.id, themeStroke)
                        cell.addView(coverFrame, LinearLayout.LayoutParams(-1, -2))
                        if(arranging) cell.setOnDragListener { view,event->
                            val from=event.localState as? String
                            if(from==null || from !in manualDraft.orEmpty()) false else when(event.action) {
                                android.view.DragEvent.ACTION_DRAG_STARTED->true
                                android.view.DragEvent.ACTION_DRAG_ENTERED->{ coverFrame.alpha=.6f;true }
                                android.view.DragEvent.ACTION_DRAG_EXITED->{ coverFrame.alpha=1f;true }
                                android.view.DragEvent.ACTION_DRAG_LOCATION->{ manualScroll(view,event.y);true }
                                android.view.DragEvent.ACTION_DROP->{
                                    manualDraft=ManualShelfOrder.move(manualDraft.orEmpty(),from,book.id)
                                    stopManualScroll()
                                    val positions=manualDraft.orEmpty().withIndex().associate { it.value to it.index }
                                    for(rowIndex in 0 until grid.childCount) {
                                        val line=grid.getChildAt(rowIndex) as? ViewGroup ?: continue
                                        for(cellIndex in 0 until line.childCount) {
                                            val item=line.getChildAt(cellIndex);val id=item.tag?.toString()?.removePrefix("shelf-cover:")
                                            positions[id]?.let { item.setTag(br.com.centralcasa.R.id.shelf_cover_order,it) }
                                        }
                                    }
                                    grid.post { if(manualDraft!=null && grid.parent!=null) reflowCovers(grid,renderedColumns,false) }
                                    true
                                }
                                android.view.DragEvent.ACTION_DRAG_ENDED->{ coverFrame.alpha=1f;stopManualScroll();true }
                                else->true
                            }
                        }
                        coverFrame.background = ui.shape(theme?.let { shelfTint(it, .16f) } ?: ui.surface, 10)
                        val bitmap = if(useAsyncShelfImages) null else images.thumbnail(book.cover)
                        coverFrame.addView(ShelfCoverView(activity).apply {
                            if (bitmap != null) setImageBitmap(bitmap) else setImageDrawable(emptyCoverDrawable())
                            if(useAsyncShelfImages) shelfThumbnails.bind(this,book.cover)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            background = ui.shape(ui.activeSurface, 6)
                            clipToOutline = true
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, FrameLayout.LayoutParams(-1, -2))
                        ui.label(cell, book.title, 11, ui.text).apply {
                            visibility = if (ui.rectilinear || compactCovers) View.GONE else View.VISIBLE
                            minLines = 2; maxLines = 2; includeFontPadding = true; ellipsize = android.text.TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) }
                            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        }
                    } else { cell.background = null }
                }
            }
        }
        refreshSpines=refresh
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { shelfFilters = shelfFilters.copy(query = s.toString()); shelfScroll = 0; persistShelfFilters(); refresh() }
            override fun afterTextChanged(s: Editable?) {}
        })
        grid.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft && (spineMode() || ShelfLayout.columns((right - left) / activity.resources.displayMetrics.density, settings.shelfColumns(shelfType), activity.resources.configuration.fontScale) != renderedColumns)) {
                grid.post { if (!destroyed && grid.parent != null) refresh() }
            }
        }
        refresh()
        grid.post { activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll).scrollTo(0, shelfScroll) }
    }
    private fun renderSpines(grid:LinearLayout,books:List<LibraryBook>,collections:List<CollectionTitle>,volumes:List<LibraryBook>,available:Float,all:List<LibraryBook>){
        val counts=volumes.groupingBy {it.collectionId}.eachCount()
        val collectionIds=collections.map {it.id}.toSet()
        var width=0;var line:LinearLayout?=null
        fun nextRow():LinearLayout {
            val group=ui.column();grid.addView(group,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(18)})
            val row=row(group).apply {gravity=Gravity.BOTTOM;tag="spine-row"}
            row.layoutParams=LinearLayout.LayoutParams(-1,ui.dp((220*spineZoom).toInt())).apply {marginStart=ui.dp(5);marginEnd=ui.dp(5)}
            group.addView(ShelfWoodView(activity,shelfMaterials).apply {tag="shelf-wood";importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(-1,ui.dp((18*spineZoom).toInt())))
            width=0;return row
        }
        books.forEachIndexed {index,book->
            val collection=book.id in collectionIds
            val count=counts[book.id] ?: 0
            val dpWidth=(ShelfSpines.width(book.totalPages,book.pagesKnown,collection)*spineZoom).toInt().coerceIn(1,(available-10).toInt().coerceAtLeast(1))
            if(line==null || width+dpWidth>available-10)line=nextRow()
            val cell=ui.column().apply {tag="shelf-cover:${book.id}";contentDescription=listOf(book.title,book.author).filter(String::isNotBlank).joinToString(", ")+(if(collection)", $count volumes" else "");isFocusable=true;setTag(br.com.centralcasa.R.id.shelf_cover_order,index)}
            line!!.addView(cell,LinearLayout.LayoutParams(ui.dp(dpWidth),-1))
            val spine=ShelfSpineView(activity,book.title,if(collection)"$count volumes" else "",author=book.author,materials=shelfMaterials).apply {tag="shelf-spine:${book.id}";importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
            cell.addView(spine,LinearLayout.LayoutParams(-1,-1))
            if(!collection && book.spineImage.isNotBlank()) shelfThumbnails.bindSpine(spine,book.spineImage) else shelfThumbnails.bind(spine,book.cover)
            cell.setOnClickListener {if(collection){selectedCollection=book.id;historyExpanded=false;shownSessions=4;show("collection")}else {selectedCollection=null;open(book.id)}}
            if(useManualShelfOrder)cell.setOnLongClickListener {beginManualOrder(all);true}
            width+=dpWidth
        }
    }

    private fun filterDialog(books: List<LibraryBook>): AlertDialog {
        val body = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)) }
        fun select(label: String, options: List<String>, index: Int): Spinner {
            val line = row(body).apply { background = ui.outlined(); setPadding(ui.dp(8), 0, ui.dp(4), 0) }
            line.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) }
            ui.label(line, label, 12).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(65), -2) }
            return Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, options)
                minimumHeight = ui.dp(42); contentDescription = label
                setSelection(index.coerceAtLeast(0)); line.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        ui.label(body, "Filtrar", 20, bold = true).typeface = Typeface.create("serif", Typeface.BOLD)
        val statuses = ShelfStatus.entries
        val status = select(if(useCollectionInventory)"Leitura" else "Situação", statuses.map { it.label }, shelfFilters.status.ordinal)
        val completion=if(useCollectionInventory && shelfType in listOf("manga","comic","all"))select("Coleção",CollectionCompletion.entries.map {it.label},shelfFilters.collectionCompletion.ordinal) else null
        fun metadata(values: List<String>, missing: String, all: String) = listOf(all) + values.map { it.trim().ifBlank { missing } }.distinctBy { ShelfFilters.key(it) }.sortedBy { ShelfFilters.key(it) }
        val genres = metadata(books.map { it.genre }, "Sem gênero", "Todos")
        val genre = select("Gênero", genres, genres.indexOf(shelfFilters.genre ?: genres[0]))
        val subgenres = metadata(books.flatMap { it.selectedSubgenres }, "Sem subgênero", "Todos")
        val subgenre = select("Subgênero", subgenres, subgenres.indexOf(shelfFilters.subgenre ?: subgenres[0]))
        val sagas = metadata(books.map { it.saga }, "Sem saga", "Todas")
        val saga = select("Saga", sagas, sagas.indexOf(shelfFilters.saga ?: sagas[0]))
        if (shelfType in listOf("manga", "comic")) (saga.parent as View).visibility = View.GONE
        val authors = metadata(books.map { it.author }, "Sem autor", "Todos")
        val author = select(if (shelfType == "manga") "Mangaká" else "Autor", authors, authors.indexOf(shelfFilters.author ?: authors[0]))
        val ratingOptions = listOf("Todas", "Com nota", "Sem nota") + (1..10).map { "${String.format(Locale("pt", "BR"), "%.1f", it / 2.0)} ou mais" }
        val ratingIndex = shelfFilters.minimumRating?.let { (it * 2).toInt() + 2 } ?: when (shelfFilters.ratings) { ShelfRatings.ALL -> 0; ShelfRatings.RATED -> 1; ShelfRatings.UNRATED -> 2 }
        val ratingFilter = select("Nota", ratingOptions, ratingIndex)
        val covers = select("Capa", ShelfCovers.entries.map { it.label }, shelfFilters.covers.ordinal)
        ui.label(body, "Ordenar por prioridade", 20, bold = true).apply {
            typeface = Typeface.create("serif", Typeface.BOLD); (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12)
        }
        ui.label(body, "Segure a alça e arraste. Toque no número para mover.", 10, ui.muted)
        val priorities = shelfFilters.order.ifEmpty { shelfOrderDefaults() }.toMutableList()
        val priorityHost = ui.column(); body.addView(priorityHost)
        lateinit var drawPriorities: () -> Unit
        fun chooseField(index: Int?) {
            val choices = ShelfField.entries.filter { field -> (shelfType !in listOf("manga", "comic") || field !in listOf(ShelfField.SAGA, ShelfField.SEQUENCE)) && (priorities.none { it.field == field } || index?.let { priorities[it].field == field } == true) }
            if (choices.isEmpty()) { toast("Todos os critérios já foram adicionados."); return }
            AlertDialog.Builder(activity).setTitle(if (index == null) "Adicionar critério" else "Alterar critério")
                .setItems(choices.map { it.label }.toTypedArray()) { _, which ->
                    if (index == null) priorities.add(ShelfOrder(choices[which])) else priorities[index] = ShelfOrder(choices[which], priorities[index].descending)
                    drawPriorities()
                }.setNegativeButton("Cancelar", null).show()
        }
        drawPriorities = {
            priorityHost.removeAllViews()
            priorities.forEachIndexed { index, criterion ->
                val line = row(priorityHost).apply {
                    background = ui.outlined(); setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) }
                }
                fun mini(value: String, description: String, width: Int, click: () -> Unit) = ui.action(line, value, onClick = click).apply {
                    contentDescription = description; minHeight = ui.dp(40); setPadding(0, ui.dp(4), 0, ui.dp(4)); background = ui.ripple(Color.TRANSPARENT, 6)
                    layoutParams = LinearLayout.LayoutParams(ui.dp(width), ui.dp(40)); textSize = 11f
                }
                mini("⠿", "Arrastar ${criterion.field.label}", 26) {}.setOnLongClickListener { handle ->
                    @Suppress("DEPRECATION")
                    handle.startDrag(android.content.ClipData.newPlainText("priority", index.toString()), View.DragShadowBuilder(line), criterion.field, 0)
                    true
                }
                mini("${index + 1}", "Mover ${criterion.field.label}", 24) {
                    val destinations = priorities.indices.filter { it != index }
                    AlertDialog.Builder(activity).setTitle("Mover ${criterion.field.label}").setItems(destinations.map { "Prioridade ${it + 1}" }.toTypedArray()) { _, which ->
                        priorities.add(destinations[which], priorities.removeAt(index)); drawPriorities()
                    }.setNegativeButton("Cancelar", null).show()
                }
                ui.action(line, criterion.field.label) { chooseField(index) }.apply {
                    contentDescription = "Critério ${index + 1}: ${criterion.field.label}"; minHeight = ui.dp(40)
                    background = ui.ripple(Color.TRANSPARENT, 6); gravity = Gravity.CENTER_VERTICAL; textSize = 11f
                    setPadding(ui.dp(4), 0, ui.dp(4), 0); layoutParams = LinearLayout.LayoutParams(0, ui.dp(40), 1f)
                    maxLines = 2
                }
                mini(criterion.direction, "Direção de ${criterion.field.label}: ${criterion.direction}", 78) {
                    priorities[index] = criterion.copy(descending = !criterion.descending); drawPriorities()
                }
                mini("×", "Remover ${criterion.field.label}", 28) { priorities.removeAt(index); drawPriorities() }
                line.setOnDragListener { _, event ->
                    when (event.action) {
                        android.view.DragEvent.ACTION_DRAG_STARTED -> event.localState is ShelfField
                        android.view.DragEvent.ACTION_DRAG_ENTERED -> { line.background = ui.outlined(stroke = 0xffe5ab54.toInt()); true }
                        android.view.DragEvent.ACTION_DRAG_EXITED, android.view.DragEvent.ACTION_DRAG_ENDED -> { line.background = ui.outlined(); true }
                        android.view.DragEvent.ACTION_DROP -> {
                            val from = priorities.indexOfFirst { it.field == event.localState }
                            if (from >= 0 && from != index) { priorities.add(index, priorities.removeAt(from)); drawPriorities() }
                            true
                        }
                        else -> true
                    }
                }
            }
        }
        drawPriorities()
        ui.action(body, "+ Adicionar critério") { chooseField(null) }.apply { background = ui.outlined(); minHeight = ui.dp(40) }
        ui.label(body, if (shelfType in listOf("manga", "comic")) "Empates usam título A–Z." else "Livros sem saga ou volume aparecem depois dos demais. Empates usam título A–Z.", 10, ui.muted).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(8) }
        val dialog = AlertDialog.Builder(activity).setTitle("Filtrar e ordenar · ${CollectionKinds.labels[shelfType]}")
            .setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton("Aplicar") { _, _ ->
                manualView=false
                shelfFilters = shelfFilters.copy(collectionCompletion=completion?.let {CollectionCompletion.entries[it.selectedItemPosition]} ?: CollectionCompletion.ALL, subgenre = subgenres[subgenre.selectedItemPosition].takeIf { subgenre.selectedItemPosition > 0 }, status = statuses[status.selectedItemPosition], genre = genres[genre.selectedItemPosition].takeIf { genre.selectedItemPosition > 0 },
                    saga = sagas[saga.selectedItemPosition].takeIf { shelfType !in listOf("manga", "comic") && saga.selectedItemPosition > 0 }, author = authors[author.selectedItemPosition].takeIf { author.selectedItemPosition > 0 },
                    ratings = when (ratingFilter.selectedItemPosition) { 0 -> ShelfRatings.ALL; 2 -> ShelfRatings.UNRATED; else -> ShelfRatings.RATED },
                    minimumRating = if (ratingFilter.selectedItemPosition >= 3) (ratingFilter.selectedItemPosition - 2) / 2.0 else null,
                    covers = ShelfCovers.entries[covers.selectedItemPosition], order = priorities.toList().ifEmpty { listOf(ShelfOrder(ShelfField.TITLE)) })
                shelfScroll = 0; persistShelfFilters(); changed()
            }.setNeutralButton("Limpar") { _, _ -> manualView=false;shelfFilters = ShelfFilters(order = shelfOrderDefaults()); shelfScroll = 0; persistShelfFilters(); changed() }
            .setNegativeButton("Cancelar", null).show()
        dialog.window?.setBackgroundDrawable(ui.shape(ui.surface, 14))
        return dialog
    }
    private fun weightedActionOn(parent: LinearLayout, text: String, description: String, action: () -> Unit) = ui.action(parent, text, onClick = action).apply {
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6); bottomMargin = ui.dp(8) }; contentDescription = description
    }
    private fun stars(parent: LinearLayout, rating: Double?, save: (Double?) -> Unit) {
        val line = ui.row().apply { gravity = Gravity.CENTER }
        parent.addView(line, if (parent.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        val slider = RatingStarsView(activity, rating).apply {
            onRatingChanged = { value -> attempt { save(value); changed() } }
            setOnLongClickListener {
                AlertDialog.Builder(activity).setTitle("Remover nota?").setPositiveButton("Remover") { _, _ -> attempt { save(null); changed() } }.setNegativeButton("Cancelar", null).show()
                true
            }
        }
        line.addView(slider, LinearLayout.LayoutParams(ui.dp(150), ui.dp(48)))
    }
    private fun titleIdentity(parent: LinearLayout, title: String, author: String, publisher: String) {
        ui.label(parent, title, 28).apply {
            textSize = 28f; gravity = Gravity.CENTER; typeface = Typeface.create("serif", Typeface.NORMAL)
            setPadding(0, ui.dp(6), 0, ui.dp(6))
        }
        val subtitle = listOf(author.ifBlank { "Autor não informado" }, publisher.takeIf { it.isNotBlank() }?.let { "Editora $it" }.orEmpty())
            .filter { it.isNotBlank() }.joinToString(" · ")
        ui.label(parent, subtitle, 14, ui.muted).apply {
            textSize = 14f; gravity = Gravity.CENTER; setPadding(0, 0, 0, ui.dp(8))
        }
    }
    private fun classificationColor(book:LibraryBook):Int {
        val base=images.coverColor(book.cover) ?: 0xff633039.toInt()
        return if(useContrastCoverBorders)CoverColors.border(base,CoverColors.surface(base)) else readingAccent(book)
    }
    private fun identityDivider(parent: LinearLayout, color: Int) {
        val line = row(parent).apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) }
        line.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        line.addView(View(activity).apply { setBackgroundColor((color and 0x00ffffff) or 0x33000000) },
            LinearLayout.LayoutParams(0, ui.dp(1).coerceAtLeast(1), 4f))
        line.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
    }
    private fun classificationTiles(parent: LinearLayout, genre: String, subgenres: List<String>, color: Int) {
        val tiles = buildList { if (genre.isNotBlank()) add(genre to true); subgenres.forEach { add(it to false) } }
        if (tiles.isEmpty()) return
        // One centred row; long names stay readable by scrolling rather than clipping or shrinking.
        val scroll = HorizontalScrollView(activity).apply {
            isFillViewport = true; isHorizontalScrollBarEnabled = false; clipToPadding = false
        }
        val host = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        scroll.addView(host, FrameLayout.LayoutParams(-2, -2))
        parent.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        tiles.forEachIndexed { index, (name, isGenre) ->
            host.addView(BookSubgenreTile(activity, name, color, isGenre), LinearLayout.LayoutParams(-2, -2).apply {
                if (index > 0) marginStart = ui.dp(3)
            })
        }
        identityDivider(parent, color)
    }
    private fun sagaIdentity(parent: LinearLayout, book: LibraryBook) {
        val sequence = book.sequenceNumber?.let { if (book.collectionId.isNotBlank()) "Volume $it" else "Livro $it" }.orEmpty()
        val saga = listOf(book.saga, sequence).filter { it.isNotBlank() }.joinToString(" · ")
        if (saga.isBlank()) return
        val line = row(parent).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ui.dp(8)) }
        line.addView(ImageView(activity).apply {
            setImageDrawable(ui.symbol("stack", readingAccent(book), 18)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { marginEnd = ui.dp(6) })
        ui.label(line, saga, 13, ui.muted).apply {
            textSize = 13f; maxWidth = ui.dp(activity.resources.configuration.screenWidthDp - 96)
            layoutParams = LinearLayout.LayoutParams(-2, -2); gravity = Gravity.CENTER
        }
    }
    private fun collectionDetail(parent: LinearLayout) {
        val c = repository.collections().firstOrNull { it.id == selectedCollection }
        if (c == null) { selectedCollection = null; screen = "library"; library(parent); return }
        val volumes = repository.books().filter { it.collectionId == c.id }.sortedWith(compareBy<LibraryBook> { it.sequenceNumber ?: Int.MAX_VALUE }.thenBy { it.title })
        renderPlanSelectionBanner(parent)
        val header = row(parent)
        ui.icon(header, "back", "Voltar à estante", 40) { selectedCollection = null; show("library") }
        stars(header, c.rating) { repository.setRating(c.id, it, true) }
        ui.icon(header, "more", "Opções do título", 40) {
            AlertDialog.Builder(activity).setTitle(c.title).setItems(arrayOf("Editar título", "Escolher capa do volume 1", "Remover capa do volume 1", "Volumes por linha", if(usePersonalArchive) "Remover título da Estante" else "Excluir título e volumes")) { _, choice ->
                when (choice) {
                    0 -> editCollection(c)
                    1 -> launchDocument(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, COLLECTION_COVER)
                    2 -> { repository.saveTitleCover(c.copy(cover = "")); changed() }
                    3 -> AlertDialog.Builder(activity).setTitle("Volumes por linha · ${c.title}")
                        .setSingleChoiceItems((1..6).map { "$it" }.toTypedArray(), settings.collectionVolumesPerRow(c.id, c.kind) - 1) { dialog, index ->
                            settings.setCollectionVolumesPerRow(c.id, index + 1); dialog.dismiss(); changed()
                        }.setNegativeButton("Cancelar", null).show()
                    else -> AlertDialog.Builder(activity).setTitle(if(usePersonalArchive)"Remover ${c.title}?" else "Excluir ${c.title}?").setMessage(if(usePersonalArchive) "Os volumes sairão da Estante. Nota e histórico serão guardados em Conta → Removidos da Estante." else "Os volumes, notas, sessões e datas dessa coleção serão removidos. Exporte um backup antes.")
                        .setPositiveButton(if(usePersonalArchive)"Remover" else "Excluir") { _, _ -> attempt { if(usePersonalArchive)repository.archive(c.id,true) else repository.deleteCollection(c.id); selectedCollection = null; show("library") } }.setNegativeButton("Cancelar", null).show()
                }
            }.show()
        }
        val titleBook = c.shelfBook(volumes)
        val hero = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, ui.dp(4), 0, ui.dp(12)) }
        parent.addView(hero, LinearLayout.LayoutParams(-1, -2))
        val width = detailCoverWidth()
        cover(hero, titleBook, width, width * 3 / 2)
        (hero.getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { marginEnd = 0; gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(12) }
        (hero.getChildAt(0) as? ImageView)?.apply { background = ui.shape(ui.activeSurface, 3); clipToOutline = true }
        if(catalog!=null)hero.getChildAt(0).apply {contentDescription="Capa de ${c.title} · segure para trocar";setOnLongClickListener {volumes.firstOrNull {it.sequenceNumber==1}?.let(::personalCover) ?: toast("Adicione o volume 1 para escolher sua capa.");true}}
        titleIdentity(hero, c.title, c.author, c.publisher)
        classificationTiles(hero, c.genre, c.subgenres, classificationColor(titleBook))
        val read = volumes.count { it.complete }
        val percent = if (volumes.isEmpty()) 0 else read * 100 / volumes.size
        val progress = genreDetailPanel(parent, titleBook, 12)
        ui.label(progress, "Seus volumes", 18, bold = true).typeface = Typeface.create("serif", Typeface.BOLD)
        val numbers = row(progress)
        ui.label(numbers, "$read / ${volumes.size} volumes lidos", 13).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        ui.label(numbers, "$percent%", 13).layoutParams = LinearLayout.LayoutParams(-2, -2)
        ui.progress(progress, percent).apply { if(useCoverProgressColor) progressTintList=android.content.res.ColorStateList.valueOf(readingAccent(titleBook));contentDescription = "$percent por cento dos volumes possuídos lidos" }
        ui.label(progress, "${volumes.size} volumes obtidos", 12, ui.muted)
        if(useCollectionInventory){
            val inventory=CollectionInventory.of(c,volumes)
            ui.label(progress,when(inventory.status){CollectionCompletion.COMPLETE->"Coleção completa · ${inventory.expected} volumes";CollectionCompletion.INCOMPLETE->"${inventory.missing} volumes faltantes · total ${inventory.expected}";else->"Total de volumes não informado"},12,ui.muted)
        }else c.missingVolumes?.let { ui.label(progress, "$it volumes faltantes", 12, ui.muted) }
        val columns = ShelfLayout.columns((activity.resources.configuration.screenWidthDp - 64).toFloat(), settings.collectionVolumesPerRow(c.id, c.kind), activity.resources.configuration.fontScale)
        val volumeGrid = ui.column().apply { tag = "shelf-pinch-grid" }
        progress.addView(volumeGrid)
        var volumeOrder = 0
        volumes.chunked(columns).forEach { group ->
            val line = row(volumeGrid).apply { gravity = Gravity.TOP; clipChildren = false; clipToPadding = false; setPadding(0, ui.dp(4), 0, ui.dp(4)) }
            repeat(columns) { index ->
                val v = group.getOrNull(index)
                val shell = if (v != null) ShelfCoverFrame(activity, v, repository.currentId() == v.id) else FrameLayout(activity)
                line.addView(shell, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginEnd = ui.dp(if (index == columns - 1) 0 else 6); topMargin = ui.dp(3); bottomMargin = ui.dp(7)
                })
                if (v != null) {
                    shell.background = ui.shape(ui.surface, 10)
                    val body = ui.column(); shell.addView(body, FrameLayout.LayoutParams(-1, -2))
                    body.addView(ShelfCoverView(activity).apply {
                        val bitmap = images.thumbnail(v.cover)
                        if (bitmap == null) setImageDrawable(emptyCoverDrawable()) else setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.FIT_CENTER; background = ui.shape(ui.activeSurface, 6); clipToOutline = true
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(-1, -2))
                    run {
                        ui.label(body, v.sequenceNumber?.let { "Volume $it" } ?: "Sem número", 12, bold = true).apply {
                            visibility = if (columns >= 5) View.GONE else View.VISIBLE
                            includeFontPadding = true; gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, ui.dp(2)); maxLines = 2
                        }
                        ui.label(body, if (v.complete) "Lido" else if (v.currentPage > 0) "Em leitura" else "Não lido", 10, ui.muted).apply {
                            visibility = if (columns >= 5) View.GONE else View.VISIBLE
                            includeFontPadding = true; gravity = Gravity.CENTER; setPadding(0, 0, 0, ui.dp(3)); maxLines = 2
                        }
                    }
                    shell.tag = "shelf-cover:${v.id}"
                    shell.setTag(br.com.centralcasa.R.id.shelf_cover_order, volumeOrder++)
                    shell.contentDescription = "${c.title}, volume ${v.sequenceNumber}, ${if (v.complete) "lido" else "não lido"}"
                    shell.isFocusable = true; shell.setOnClickListener { open(v.id) }
                }
            }
        }
        ui.action(progress, "+ Adicionar volume") {
            fields.clear(); selected = null; newBook = true
            editor = LibraryBook(title = c.title, author = c.author, genre = c.genre, totalPages = 1,
                sequenceNumber = (volumes.mapNotNull { it.sequenceNumber }.maxOrNull() ?: 0) + 1,
                saga = c.title, kind = c.kind, collectionId = c.id, publisher = c.publisher, subgenres = c.subgenres, subgenre = c.subgenres.firstOrNull().orEmpty(),
                pagesKnown = false, cover = if (volumes.isEmpty()) c.cover else "")
            show("editor")
        }
        collectionHistory(parent, titleBook, volumes)
    }
    private fun collectionHistory(parent: LinearLayout, titleBook: LibraryBook, volumes: List<LibraryBook>) {
        val ids = volumes.map { it.id }.toSet()
        val dates = repository.dates().filter { it.bookId in ids }
        val sessions = repository.sessions().filter { it.bookId in ids }
        val grouped = sessions.groupBy { it.readingDay ?: ReadingDays.at(it.startedAt) }
        val days = (grouped.keys + dates.map { it.day }).distinct().sortedDescending()
        val card = genreDetailPanel(parent, titleBook, 12).apply {if(useGroupedReadingHistory)tag="reading-history-card"}
        val heading = row(card)
        detailIcon(heading, "reading-history")
        val names = ui.column(); heading.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        ui.label(names, "Histórico de leitura", 13)
        ui.label(names, "${days.size} dias registrados", 10, ui.muted)
        val addDate={
            if (volumes.isEmpty()) toast("Adicione um volume para registrar a leitura.")
            else AlertDialog.Builder(activity).setTitle("Qual volume você leu?")
                .setItems(volumes.map { it.sequenceNumber?.let { n -> "Volume $n" } ?: it.title }.toTypedArray()) { _, index -> datesEditor.show(volumes[index]) }
                .setNegativeButton("Cancelar", null).show()
            Unit
        }
        if(useGroupedReadingHistory)ui.icon(heading,"plus","Adicionar datas de leitura",44){addDate()}
        detailIcon(heading, "down").rotation = if (historyExpanded) 180f else 0f
        heading.isFocusable = true
        heading.contentDescription = "Histórico de leitura · ${days.size} dias registrados"
        heading.setOnClickListener { historyExpanded = !historyExpanded; changed() }
        if (!historyExpanded) return
        if(!useGroupedReadingHistory)ui.action(card,"+ Adicionar datas"){addDate()}
        if (days.isEmpty()) ui.label(card, "Ainda sem datas registradas. Informe os dias em que leu seus volumes.", 12, ui.muted)
        days.take(shownSessions).forEach { day ->
            val readVolumes = volumes.filter { volume -> dates.any { it.day == day && it.bookId == volume.id } || grouped[day].orEmpty().any { it.bookId == volume.id } }
            if(useGroupedReadingHistory){
                sessionDay(card,"collection:${titleBook.id}:$day",day,grouped[day].orEmpty(),dates.any {it.day==day},volumes.associateBy {it.id},true){
                    fun edit(volume:LibraryBook)=datesEditor.editDay(day,grouped[day].orEmpty().filter {it.bookId==volume.id},dates.filter {it.day==day && it.bookId==volume.id})
                    if(readVolumes.size==1)edit(readVolumes.first())
                    else AlertDialog.Builder(activity).setTitle("Qual volume deseja editar?")
                        .setItems(readVolumes.map {it.sequenceNumber?.let {n->"Volume $n"} ?: it.title}.toTypedArray()){_,index->edit(readVolumes[index])}
                        .setNegativeButton("Cancelar",null).show()
                }
                return@forEach
            }
            val line = row(card)
            ui.label(line, ReadingDays.label(day), 13, bold = true).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            ui.icon(line, "edit", "Editar leituras de ${ReadingDays.label(day)}", 44) {
                fun edit(volume: LibraryBook) = datesEditor.editDay(day, grouped[day].orEmpty().filter { it.bookId == volume.id }, dates.filter { it.day == day && it.bookId == volume.id })
                if (readVolumes.size == 1) edit(readVolumes.first())
                else AlertDialog.Builder(activity).setTitle("Qual volume deseja editar?")
                    .setItems(readVolumes.map { it.sequenceNumber?.let { n -> "Volume $n" } ?: it.title }.toTypedArray()) { _, index -> edit(readVolumes[index]) }
                    .setNegativeButton("Cancelar", null).show()
            }
            ui.label(card, readVolumes.joinToString(" · ") { it.sequenceNumber?.let { n -> "Vol. $n" } ?: it.title }, 11, ui.muted).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }
        if (days.size > shownSessions) ui.action(card, "Ver mais dias") { shownSessions += 4; changed() }
    }
    private fun editCollection(original: CollectionTitle?, type: String = shelfType.takeIf { it in listOf("manga", "comic") } ?: "manga") {
        editor = null; fields.clear()
        collectionFields.clear(); collectionEditor = original?.copy(cover = original.shelfBook(repository.books()).cover) ?: CollectionTitle(kind = type, title = "")
        collectionEditorNew = original == null; collectionOwned = "0"
        if (original == null) selectedCollection = null
        show("collection_editor")
    }
    private fun captureCollectionEditor() {
        if (screen != "collection_editor" || collectionFields.isEmpty()) return
        val c = collectionEditor ?: return
        consumeTypedSubgenre()
        collectionEditor = c.copy(title = collectionFields["title"]?.text.toString(), author = collectionFields["author"]?.text.toString(),
            publisher = collectionFields["publisher"]?.text.toString(), genre = editingGenre, subgenres = editingSubgenres.toList(),
            missingVolumes = collectionFields["missing"]?.text?.toString()?.toIntOrNull())
        collectionFields["owned"]?.let { collectionOwned = it.text.toString() }
        if(useCollectionInventory){
            val missing=collectionFields["missing"]?.text?.toString()?.toIntOrNull()
            val owned=if(collectionEditorNew)collectionOwned.toIntOrNull() ?: 0 else repository.books().filter {it.collectionId==c.id}.mapNotNull {it.sequenceNumber}.distinct().size
            collectionEditor=collectionEditor?.copy(expectedVolumes=if(!collectionEditorNew && missing==c.missingVolumes)c.expectedVolumes else missing?.let {(it+owned).takeIf {n->n>0}})
        }
    }
    private fun collectionEdit(parent: LinearLayout) {
        val c = collectionEditor ?: return
        collectionFields.clear(); editingGenre = c.genre; editingSubgenres.clear(); editingSubgenres.addAll(c.subgenres)
        val noun = when (c.kind) { "manga" -> "mangá"; "comic" -> "quadrinho"; "book" -> "light novel"; else -> "título" }
        val header = row(parent)
        ui.icon(header, "back", "Cancelar edição", 40) { back() }
        ui.label(header, "${if (collectionEditorNew) "Adicionar" else "Editar"} $noun", 26, bold = true).apply {
            typeface = Typeface.create("serif", Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        ui.label(parent, "Sua coleção, do seu jeito", 12, ui.muted)
        fun section(title: String, subtitle: String = "") = detailPanel(parent, 0x66151515, 0xff393939.toInt(), 14, glass = true).apply {
            ui.label(this, title, 18, bold = true).typeface = Typeface.create("serif", Typeface.BOLD)
            if (subtitle.isNotBlank()) ui.label(this, subtitle, 11, ui.muted)
        }
        fun field(target: LinearLayout, key: String, caption: String, value: String, numeric: Boolean = false, vocabulary: List<String> = emptyList()) {
            ui.label(target, caption, 12, ui.muted, true)
            if (vocabulary.isEmpty()) collectionFields[key] = input(target, caption, value, numeric)
            else {
                val field = AutoCompleteTextView(activity).apply {
                    setText(value); hint = caption; setTextColor(ui.text); setHintTextColor(ui.muted); textSize = 14f
                    threshold = 1; isSingleLine = true; minHeight = ui.dp(52)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.outlined(0x40151515)
                    setAdapter(BookMetadataAdapter(activity, vocabulary)); setOnClickListener { showDropDown() }
                    setOnFocusChangeListener { _, focused -> if (focused) showDropDown() }
                }
                collectionFields[key] = field; target.addView(field, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
            }
        }
        val all = repository.collections(); val books = repository.books()
        val identity = section("Identificação")
        field(identity, "title", "Título *", c.title)
        field(identity, "author", if (c.kind == "manga") "Mangaká" else "Autor", c.author, vocabulary = all.map { it.author } + books.map { it.author })
        field(identity, "publisher", "Editora", c.publisher, vocabulary = all.map { it.publisher } + books.map { it.publisher })
        val classification = section("Classificação", "Gênero organiza a Estante; a cor dos cartões acompanha a capa.")
        if (c.kind == "book") ui.label(classification, "Light novel · coleção por volumes", 12, ui.muted)
        genreFields(classification)
        val volumes = section("Volumes", "Somente os exemplares que você possui ficam na estante.")
        if (collectionEditorNew) field(volumes, "owned", "Volumes que tenho", collectionOwned, true)
        else ui.label(volumes, "${books.count { it.collectionId == c.id }} volumes cadastrados. Adicione ou edite cada volume na página do título.", 12, ui.muted)
        field(volumes, "missing", "Volumes faltantes (opcional)", c.missingVolumes?.toString().orEmpty(), true)
        ui.label(volumes, "Deixe faltantes vazio se não souber o total. Ao salvar, o total de referência será obtidos + faltantes. Novos exemplares são numerados desde o volume 1; páginas e leitura são preenchidas em cada volume.", 11, ui.muted)
        val art = section("Capa", "A capa principal é a do volume 1. Cada volume pode ter sua própria imagem.")
        repository.books().firstOrNull { it.collectionId==c.id && it.sequenceNumber==1 }?.let { first->
            catalogCoverAction(art,first) { name->
                if(screen=="collection_editor" && collectionEditor?.id==c.id) { captureCollectionEditor();collectionEditor=collectionEditor?.copy(cover=name);collectionFields.clear();changed() }
            }
        }
        val preview = row(art); cover(preview, c.shelfBook(emptyList()).copy(title = c.title.ifBlank { "Novo título" }, cover = c.cover), 76, 112)
        val options = ui.column(); preview.addView(options, LinearLayout.LayoutParams(0, -2, 1f))
        ui.action(options, if (c.cover.isBlank()) "Escolher imagem" else "Trocar imagem") {
            captureCollectionEditor(); launchDocument(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, COLLECTION_COVER)
        }
        ui.action(options, "Buscar capa") {
            captureCollectionEditor(); launchDocument(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?tbm=isch&q=" + Uri.encode(requireNotNull(collectionEditor).let { CoverSearch.query(it.title, it.author, it.kind, 1, it.publisher) }))), WEB)
        }
        if (c.cover.isNotBlank()) ui.action(art, "Remover capa") { captureCollectionEditor(); collectionEditor = collectionEditor?.copy(cover = ""); collectionFields.clear(); changed() }
        ui.action(parent, "Salvar $noun", primary = true) { attempt {
            captureCollectionEditor(); val input = requireNotNull(collectionEditor)
            val owned = if (collectionEditorNew) collectionOwned.toIntOrNull() ?: throw IllegalArgumentException("Informe quantos volumes você possui.") else 0
            val updated = input.copy(title = input.title.trim(), author = input.author.trim(), publisher = input.publisher.trim(), genre = input.genre.trim(), updatedAt = System.currentTimeMillis()).validated()
            if (collectionEditorNew && updated.kind == "book" && editor != null) repository.createLightNovel(updated, requireNotNull(editor), owned)
            else if (collectionEditorNew) repository.createCollection(updated, owned) else repository.saveTitleCover(updated)
            repository.books().filter { it.collectionId==updated.id && (it.cover.isNotBlank() || it.catalogId!=null) }.forEach { catalog?.saved(it) }
            collectionFields.clear(); collectionEditor = null; editor = null; selectedCollection = updated.id; show("collection")
        } }
        ui.action(parent, "Cancelar") { back() }
    }
    private fun detail(parent: LinearLayout) {
        val b = selected?.let(repository::book)
        if (b == null) { screen = "library"; library(parent); return }
        val header = row(parent)
        ui.action(header, "") { show(if (selectedCollection != null) "collection" else "library") }.apply {
            setCompoundDrawables(ui.symbol("back"), null, null, null)
            contentDescription = "Voltar à estante"
            layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
            background = ui.ripple(Color.TRANSPARENT)
        }
        stars(header, b.rating) { value -> repository.setRating(b.id, value) }
        ui.action(header, "") { bookMenu(b) }.apply {
            setCompoundDrawables(null, null, ui.symbol("more"), null)
            contentDescription = "Opções do livro"
            layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
            background = ui.ripple(Color.TRANSPARENT)
        }
        val hero = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, ui.dp(4), 0, ui.dp(12)) }
        parent.addView(hero, LinearLayout.LayoutParams(-1, -2))
        val width = detailCoverWidth()
        cover(hero, b, width, width * 3 / 2)
        if(catalog!=null)hero.getChildAt(0).apply {contentDescription="Capa de ${b.title} · segure para trocar";setOnLongClickListener {personalCover(b);true}}
        (hero.getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { marginEnd = 0; gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(12) }
        (hero.getChildAt(0) as? ImageView)?.apply { background = ui.shape(ui.activeSurface, 3); clipToOutline = true }
        titleIdentity(hero, b.title, b.author, b.publisher)
        classificationTiles(hero, b.genre, b.selectedSubgenres, classificationColor(b))
        sagaIdentity(hero, b)

        if (!b.pagesKnown) {
            val reading = genreDetailPanel(parent, b, 12)
            ui.label(reading, "LEITURA", 10, ui.muted, true)
            ui.label(reading, if (b.complete) "Volume lido" else "Ainda não lido", 22, bold = true).typeface = Typeface.create("serif", Typeface.BOLD)
            ui.label(reading, "Total de páginas não informado. Cadastre as páginas para acompanhar o progresso e cronometrar a leitura.", 12, ui.muted)
            ui.action(reading, if (b.complete) "Marcar como não lido" else "Marcar como lido", primary = true) { repository.markVolumeRead(b.id, !b.complete); changed() }
            ui.action(reading, "Informar páginas / editar volume") { fields.clear(); editor = b; newBook = false; show("editor") }
            collectionHistory(parent, b, listOf(b))
            return
        }
        val progressArea = ui.column(); parent.addView(progressArea)
        fun shownPercent(page: Int) = if (page == b.totalPages) 100 else Math.round(page * 100f / b.totalPages).coerceIn(0, 99)
        val numbers = row(progressArea)
        val pages = ui.label(numbers, "${b.currentPage} / ${b.totalPages} páginas", 12).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        val percent = ui.label(numbers, "${shownPercent(b.currentPage)}%", 12).apply { layoutParams = LinearLayout.LayoutParams(-2, -2) }
        val slider = SeekBar(activity).apply {
            max = b.totalPages; progress = b.currentPage
            contentDescription = "Página atual de ${b.title}"
            progressTintList = android.content.res.ColorStateList.valueOf(if(useCoverProgressColor) readingAccent(b) else ui.accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xff444444.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(if(useCoverProgressColor) readingAccent(b) else ui.accent)
            setPadding(0, 0, 0, 0)
        }
        ui.styleSlider(slider,color=if(useCoverProgressColor) readingAccent(b) else null)
        progressArea.addView(slider, LinearLayout.LayoutParams(-1, ui.dp(24)))
        val remaining = ui.label(progressArea, "Faltam ${b.totalPages - b.currentPage} páginas", 11, ui.muted)
        ui.label(progressArea, "Arraste para atualizar sua página", 10, ui.muted).apply { gravity = Gravity.CENTER; (layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; bottomMargin = ui.dp(10) } }
        fun preview(page: Int) {
            pages.text = "$page / ${b.totalPages} páginas"
            percent.text = "${shownPercent(page)}%"
            remaining.text = "Faltam ${b.totalPages - page} páginas"
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var dragging = false
            override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true; seekBar?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                preview(progress)
                // Accessibility changes do not emit touch-start/stop callbacks.
                if (fromUser && !dragging) persist(progress)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { dragging = false; seekBar?.parent?.requestDisallowInterceptTouchEvent(false); persist(slider.progress) }
            private fun persist(page:Int){
                if(progressDialog?.isShowing==true)return
                if(!ui.rectilinear){attempt {repository.updateProgress(b.id,page)};return}
                val current=repository.book(b.id) ?: return
                if(current.currentPage==page)return
                fun refresh(){val scroll=activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll);val position=scroll.scrollY;changed();scroll.post {scroll.scrollTo(0,position)}}
                val body=ui.column().apply {setPadding(ui.dp(20),ui.dp(8),ui.dp(20),0)}
                ui.label(body,"Página atual",12,ui.muted)
                val destination=EditText(activity).apply {
                    tag="reading-page";inputType=InputType.TYPE_CLASS_NUMBER;isSingleLine=true
                    filters=arrayOf(android.text.InputFilter.LengthFilter(6));setText(page.toString());setSelectAllOnFocus(true)
                    setTextColor(ui.text);setHintTextColor(ui.muted)
                }
                body.addView(destination,LinearLayout.LayoutParams(-1,ui.dp(52)))
                fun chosenPage():Int? {
                    val value=destination.text.toString().toIntOrNull()
                    if(value==null || value !in 0..current.totalPages){destination.error="Use uma página entre 0 e ${current.totalPages}";return null}
                    destination.error=null;return value
                }
                fun correctPage(value:Int){
                    val latest=requireNotNull(repository.book(b.id)){"Livro não encontrado."}
                    require(latest.currentPage==current.currentPage && latest.totalPages==current.totalPages){"A página mudou. Confira o progresso antes de corrigir."}
                    repository.updateProgress(b.id,value)
                }
                fun showDialog(dialog:AlertDialog){
                    progressDialog=dialog;dialog.setOnDismissListener {if(progressDialog===dialog)progressDialog=null};dialog.show()
                }
                if(page<current.currentPage){
                    fun restore(){val latestPage=repository.book(b.id)?.currentPage ?: current.currentPage;slider.progress=latestPage;preview(latestPage)}
                    val dialog=AlertDialog.Builder(activity).setTitle("Corrigir página").setView(body)
                        .setPositiveButton("Só corrigir página",null).setNegativeButton("Cancelar"){_,_->restore()}.create()
                    dialog.setOnCancelListener {restore()}
                    dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val target=chosenPage() ?: return@setOnClickListener
                        try {correctPage(target);dialog.dismiss();refresh()}catch(e:Exception){destination.error=e.message ?: "Não foi possível corrigir"}
                    }}
                    showDialog(dialog);return
                }
                val draft=repository.draft()?.takeIf {it.bookId==b.id}
                if(draft?.runningSince!=null)repository.pause()
                val interval=ui.label(body,"${draft?.startPage ?: current.currentPage} → $page páginas",16)
                destination.addTextChangedListener(object:TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){interval.text="${draft?.startPage ?: current.currentPage} → ${s?.toString().orEmpty()} páginas";destination.error=null}
                    override fun afterTextChanged(s:Editable?){}
                })
                val time=ui.row();body.addView(time)
                fun input(hint:String,max:Int)=EditText(activity).apply {this.hint=hint;inputType=InputType.TYPE_CLASS_NUMBER;filters=arrayOf(android.text.InputFilter.LengthFilter(max));setTextColor(ui.text);setHintTextColor(ui.muted);time.addView(this,LinearLayout.LayoutParams(0,ui.dp(52),1f))}
                val hours=input("Horas",3).apply {tag="reading-hours"};val minutes=input("Minutos",2).apply {tag="reading-minutes"}
                if(draft!=null){val total=(repository.elapsed(draft)+59999)/60000;hours.setText((total/60).toString());minutes.setText((total%60).toString())}
                fun restore(){val latestPage=repository.book(b.id)?.currentPage ?: current.currentPage;slider.progress=latestPage;preview(latestPage);if(draft?.runningSince!=null){repository.start(b.id);refresh()}}
                val dialog=AlertDialog.Builder(activity).setTitle("Registrar leitura").setView(body).setPositiveButton("Registrar leitura",null)
                    .setNeutralButton("Só corrigir página",null).setNegativeButton("Cancelar"){_,_->restore()}.create()
                dialog.setOnCancelListener {restore()}
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val target=chosenPage() ?: return@setOnClickListener
                        try {correctPage(target);if(draft?.runningSince!=null)repository.start(b.id);dialog.dismiss();refresh()}catch(e:Exception){destination.error=e.message ?: "Não foi possível corrigir"}
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val target=chosenPage() ?: return@setOnClickListener
                        if(target<=(draft?.startPage ?: current.currentPage)){destination.error="Para voltar a página, use Só corrigir página";return@setOnClickListener}
                        val h=hours.text.toString().toIntOrNull() ?: 0;val m=minutes.text.toString().toIntOrNull() ?: 0
                        if(m !in 0..59 || h*60+m<=0){minutes.error="Informe o tempo de leitura";return@setOnClickListener}
                        try {repository.recordReading(b.id,current.currentPage,target,(h*60L+m)*60000);dialog.dismiss();refresh()}catch(e:Exception){destination.error=e.message ?: "Não foi possível registrar"}
                    }
                }
                showDialog(dialog)
            }

        })
        timerCard(parent, b)
        val records = repository.sessions(b.id); val summary = ReadingSummary.from(records)
        val metrics = genreDetailPanel(parent, b, 12)
        val paceRow = row(metrics)
        detailIcon(paceRow, "clock")
        if(ui.rectilinear){
            metrics.tag="book-reading-rhythm"
            ui.label(paceRow,"Seu ritmo",13).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            val values=row(metrics).apply {gravity=Gravity.BOTTOM;(layoutParams as LinearLayout.LayoutParams).topMargin=ui.dp(6)}
            val total=ui.column();values.addView(total,LinearLayout.LayoutParams(0,-2,1.2f))
            ui.label(total,if(records.isEmpty())"—" else compactReadingTime(summary.durationMs),32,readingAccent(b)).apply {
                tag="book-reading-total";typeface=Typeface.create("serif",Typeface.NORMAL)
            }
            ui.label(total,if(b.complete)"Tempo para concluir" else "Tempo total de leitura",10,ui.muted).tag="book-reading-total-label"
            val average=ui.column().apply {setPadding(ui.dp(10),0,0,0)};values.addView(average,LinearLayout.LayoutParams(0,-2,1f))
            ui.label(average,if(summary.millisPerPage==null)"— por página" else readingPace(summary.millisPerPage),14).apply {gravity=Gravity.END}
            ui.label(average,"Ritmo médio",10,ui.muted).apply {gravity=Gravity.END}
        }else {
            val paceTitle = ui.column(); paceRow.addView(paceTitle, LinearLayout.LayoutParams(0, -2, 1.6f))
            ui.label(paceTitle, "Seu ritmo", 13).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(2) }
            ui.label(paceTitle, "Média das páginas cronometradas", 10, ui.muted).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0 }
            ui.label(paceRow, if (summary.millisPerPage == null) "—" else readingPace(summary.millisPerPage), 12).apply {
                gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
        }
        val manualDates = repository.dates(b.id)
        val grouped = records.groupBy { it.readingDay ?: ReadingDays.at(it.startedAt) }
        val days = (grouped.keys + manualDates.map { it.day }).distinct().sortedDescending()
        val history = ui.column().apply { tag = "book-history" }; parent.addView(history)
        val historyCard = genreDetailPanel(history, b, 12).apply {if(useGroupedReadingHistory)tag="reading-history-card"}
        val historyRow = row(historyCard)
        detailIcon(historyRow, "reading-history")
        val historyNames = ui.column(); historyRow.addView(historyNames, LinearLayout.LayoutParams(0, -2, 1f))
        ui.label(historyNames, "Histórico de leitura", 13)
        ui.label(historyNames, if(useGroupedReadingHistory) "${days.size} dias registrados" else "${days.size} dias registrados · ${records.size} sessões", 10, ui.muted)
        if(useGroupedReadingHistory)ui.icon(historyRow,"plus","Adicionar datas de leitura",44){datesEditor.show(b)}
        detailIcon(historyRow, "down").rotation = if (historyExpanded) 180f else 0f
        val historyToggle=if(useGroupedReadingHistory)historyRow else historyCard
        historyToggle.contentDescription = "Histórico de leitura · ${days.size} dias registrados"
        historyToggle.isFocusable = true
        historyToggle.setOnClickListener {
            historyExpanded = !historyExpanded; changed()
            if (historyExpanded) {
                val scroll = activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll)
                scroll.post { scroll.findViewWithTag<View>("book-history")?.let { scroll.smoothScrollTo(0, it.top) } }
            }
        }
        if (historyExpanded) {
            if(useGroupedReadingHistory){
                if(days.isEmpty())ui.label(historyCard,"Ainda sem datas registradas",11,ui.muted)
            }else {
                val datesCard=genreDetailPanel(history,b,8)
                val datesHeading=row(datesCard)
                ui.label(datesHeading,"Datas da leitura",12,bold=true).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
                ui.icon(datesHeading,"plus","Adicionar datas de leitura",44){datesEditor.show(b)}
                ui.label(datesCard,if(days.isEmpty())"Ainda sem datas registradas" else if(days.size==1)ReadingDays.label(days.first()) else "${ReadingDays.label(days.last())} → ${ReadingDays.label(days.first())}",11,ui.muted)
            }
            days.take(shownSessions).forEach { day ->
                val sessions = grouped[day].orEmpty()
                val manual = manualDates.filter { it.day == day }
                if(useGroupedReadingHistory){
                    sessionDay(historyCard,"${b.id}:$day",day,sessions,manual.isNotEmpty(),mapOf(b.id to b)){datesEditor.editDay(day,sessions,manual)}
                    return@forEach
                }
                val p = if(useGroupedReadingHistory) ui.column().apply {
                    historyCard.addView(View(activity).apply {setBackgroundColor(0x33ffffff)},LinearLayout.LayoutParams(-1,ui.dp(1)).apply {topMargin=ui.dp(8)})
                    setPadding(0,ui.dp(4),0,ui.dp(4));historyCard.addView(this)
                } else genreDetailPanel(history, b, 8)
                p.tag="reading-history-day"
                val heading = row(p)
                ui.label(heading, ReadingDays.label(day), 14, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
                ui.icon(heading, "edit", "Editar leituras de ${ReadingDays.label(day)}", 44) { datesEditor.editDay(day, sessions, manual) }
                if (sessions.isNotEmpty()) {
                    val daily = ReadingSummary.from(sessions)
                    ui.label(p, "${sessions.sumOf { it.pages }} páginas · ${readingTime(daily.durationMs)} · ${sessions.size} sessão(ões)", 11, ui.muted)
                }
                if (manual.isNotEmpty()) ui.label(p, "Data informada por você", 11, ui.muted)
            }
            if (days.size > shownSessions) ui.action(if(useGroupedReadingHistory)historyCard else history, "Ver mais dias") { shownSessions += 4; changed() }
        }
    }
    private fun detailCoverWidth(): Int {
        val config = activity.resources.configuration
        // Stable across reading state and expanded history; extra content scrolls instead.
        return minOf((config.screenWidthDp * .48f).toInt(), ((config.screenHeightDp - 345).coerceAtLeast(220) * .55f).toInt()).coerceIn(120, 210)
    }
    private fun bookMenu(b: LibraryBook) {
        val options = mutableListOf("Editar livro", "Adicionar datas de leitura").apply { if (b.pagesKnown) add(1, "Informar página exata") }
        options.add("Trocar capa")
        if(b.collectionId.isBlank())options.add("Foto da lombada")
        if (repository.currentId() != b.id) options.add("Tornar livro atual")
        if (repository.draft()?.bookId == b.id) options.addAll(listOf("Pausar leitura e salvar", "Descartar tempo da leitura"))
        if(usePersonalArchive)options.add("Remover da Estante")
        if(onAccount!=null)options.add("Conta")
        AlertDialog.Builder(activity).setTitle(b.title).setItems(options.toTypedArray()) { _, index ->
            when (options[index]) {
                "Conta" -> onAccount?.invoke()
                "Remover da Estante" -> AlertDialog.Builder(activity).setTitle("Remover da Estante?").setMessage("Nota e histórico serão guardados em Conta → Removidos da Estante.").setPositiveButton("Remover") {_,_->attempt {repository.archive(b.id);selected=null;show("library")}}.setNegativeButton("Cancelar",null).show()
                "Trocar capa" -> personalCover(b)
                "Foto da lombada" -> spinePhoto(b)
                "Editar livro" -> { fields.clear(); editor = repository.book(b.id); newBook = false; show("editor") }
                "Informar página exata" -> updatePage(requireNotNull(repository.book(b.id)))
                "Pausar leitura e salvar" -> pauseAndSaveReading(b)
                "Adicionar datas de leitura" -> datesEditor.show(b)
                "Tornar livro atual" -> { repository.setCurrent(b.id); changed() }
                else -> AlertDialog.Builder(activity).setTitle("Descartar tempo da leitura?")
                    .setMessage("O tempo não será registrado. As páginas serão mantidas.")
                    .setPositiveButton("Descartar") { _, _ -> repository.discard(); changed() }.setNegativeButton("Cancelar", null).show()
            }
        }.show()
    }
    private fun readingAccent(b: LibraryBook): Int {
        if(ui.rectilinear && repository.currentId()==b.id)return ui.accent
        val base=images.coverColor(b.cover) ?: 0xff633039.toInt()
        return if(ui.rectilinear)CoverColors.border(base,ui.activeSurface,4.6) else CoverColors.accent(base)
    }
    private fun genreDetailPanel(parent: LinearLayout, b: LibraryBook, inset: Int): LinearLayout = ui.column().apply {
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        val base=images.coverColor(b.cover) ?: 0xff633039.toInt()
        val surface=if(ui.rectilinear)DisplayUi(activity).apply {scopedReadingColor(images.coverColor(b.cover))}.surface else CoverColors.surface(base)
        val accent=if(ui.rectilinear)readingAccent(b) else if(useContrastCoverBorders)CoverColors.border(base,surface) else readingAccent(b)
        setPadding(ui.dp(inset), ui.dp(inset), ui.dp(inset), ui.dp(inset))
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf((surface and 0x00ffffff) or (if(ui.rectilinear)0xff000000.toInt() else 0xD9000000.toInt()), (surface and 0x00ffffff) or (if(ui.rectilinear)0xff000000.toInt() else 0xBF000000.toInt()))).apply {
            cornerRadius = ui.radius(14);setStroke(ui.dp(if(ui.rectilinear)1 else if(useContrastCoverBorders)2 else 1).coerceAtLeast(1),if(useContrastCoverBorders)accent else (accent and 0x00ffffff) or 0xCC000000.toInt())
        }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
    }
    private fun detailPanel(parent: LinearLayout, color: Int, border: Int, inset: Int, glass: Boolean = false): LinearLayout = ui.column().apply {
        setPadding(ui.dp(inset), ui.dp(inset), ui.dp(inset), ui.dp(inset))
        background = if(ui.rectilinear)ui.outlined(ui.surface,ui.lineColor,1) else if (glass) android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf((color and 0x00ffffff) or 0x66000000, (color and 0x00ffffff) or 0x40000000)).apply {
            cornerRadius = ui.radius(10); setStroke(ui.dp(2).coerceAtLeast(1), border)
        } else android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(color, color and 0x00ffffff or 0xD9000000.toInt())).apply {
            cornerRadius = ui.radius(10); setStroke(ui.dp(1).coerceAtLeast(1), border)
        }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
    }
    private fun detailIcon(parent: LinearLayout, name: String) = ImageView(activity).apply {
        setImageDrawable(DisplayIcon(name, ui.muted)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        parent.addView(this, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(12) })
    }
    private fun timerCard(parent: LinearLayout, b: LibraryBook) {
        val ink = ui.text
        val accent = readingAccent(b)
        val p = genreDetailPanel(parent, b, 12)
        val d = repository.draft()
        val ownSession = d?.bookId == b.id
        val heading = row(p)
        heading.addView(ImageView(activity).apply {
            setImageDrawable(ui.symbol("clock", ink, 20)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(8) })
        ui.label(heading, "TEMPO DE LEITURA", 9, ink).apply {
            letterSpacing = .08f; maxLines = 2; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        fun headerAction(text: String, icon: String, action: () -> Unit) {
            ui.action(heading, text, onClick = action).apply {
                layoutParams = LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginStart = ui.dp(8) }
                minHeight = ui.dp(44); textSize = 11f; setTextColor(ui.background)
                setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
                background = ui.ripple(if(ui.rectilinear)accent else 0xfff1eee7.toInt(), 22)
                setCompoundDrawables(ui.symbol(icon, ui.background, 14), null, null, null)
                compoundDrawablePadding = ui.dp(5)
            }
        }
        when {
            d != null && !ownSession -> headerAction("Abrir leitura", "book_nav") { open(d.bookId) }
            ownSession -> headerAction(if (d!!.runningSince != null) "Pausar leitura" else "Continuar leitura",
                if (d.runningSince != null) "pause" else "play") { attempt {
                if (d.runningSince != null) pauseAndSaveReading(b) else { repository.start(b.id); changed() }
            } }
            !b.complete -> headerAction("Iniciar leitura", "play") { attempt { repository.start(b.id); changed() } }
        }
        if (d != null && !ownSession) {
            ui.label(p, "Há uma sessão em outro livro", 14, ink).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(8) }
            ui.label(p, "Abra essa leitura para pausá-la e salvar a página.", 11, ui.muted)
            return
        }
        timerBookId = b.id
        val timeRow = row(p)
        timeRow.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) }
        timerLabel = ui.label(timeRow, readingTime(d?.let(repository::elapsed) ?: 0), 40, if(ui.rectilinear)accent else ink).apply {
            gravity = Gravity.START; textSize = 38f; typeface = Typeface.create("serif", if(ui.rectilinear)Typeface.NORMAL else Typeface.BOLD)
            fontFeatureSettings = "tnum"; maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            if (android.os.Build.VERSION.SDK_INT >= 26) setAutoSizeTextTypeUniformWithConfiguration(22, 38, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        val status = when { b.complete -> "Concluído"; ownSession && d!!.runningSince!=null -> "Lendo"; ownSession || b.currentPage>0 || repository.sessions(b.id).isNotEmpty() -> "Pausado"; else -> "Não iniciado" }
        ui.label(timeRow, status, 10, accent).apply {
            tag="reading-status"
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) }
            setPadding(ui.dp(9), ui.dp(6), ui.dp(9), ui.dp(6))
            background = ui.shape(0x24000000, 16).apply { setStroke(ui.dp(1).coerceAtLeast(1), accent) }
            if (ownSession) text = "● $status"
        }
        ui.label(p, if (ownSession) "Tempo desta sessão" else if (b.complete) "Leitura concluída" else "Comece na página ${b.currentPage}", 11, ui.muted)
        p.addView(View(activity).apply { setBackgroundColor((accent and 0x00ffffff) or 0x44000000) },
            LinearLayout.LayoutParams(-1, ui.dp(1)).apply { topMargin = ui.dp(6) })
        val footer = row(p).apply { minimumHeight = ui.dp(44) }
        footer.layoutParams = LinearLayout.LayoutParams(-1, -2)
        ui.label(footer, "Página ${b.currentPage} de ${b.totalPages}", 11, ink).apply {
            maxLines = 2; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        if (ownSession) ui.action(footer, "Concluir livro") { concludeBook(b) }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, ui.dp(44))
            minHeight = ui.dp(44); textSize = 11f; setTextColor(ink)
            background = ui.ripple(Color.TRANSPARENT, 8)
            setCompoundDrawables(null, null, ui.symbol("chevron", ink, 14), null)
            compoundDrawablePadding = ui.dp(5); setPadding(ui.dp(10), 0, 0, 0)
        }
    }
    private fun pauseAndSaveReading(b: LibraryBook) {
        repository.pause()
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val field = input(body, "Página em que parou (0 a ${b.totalPages})", b.currentPage.toString(), true)
        ui.label(body, "Salva o tempo e a data no histórico e libera a leitura de outro livro. O livro só fica concluído se chegar à última página.", 13, ui.muted)
        val dialog = AlertDialog.Builder(activity).setTitle("Pausar leitura")
            .setView(body).setPositiveButton("Salvar leitura", null).setNegativeButton("Cancelar") { _, _ -> changed() }.create()
        dialog.setOnCancelListener { changed() }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val page = field.text.toString().toIntOrNull() ?: throw IllegalArgumentException("Informe a página.")
                repository.finish(page); dialog.dismiss(); changed(); toast("Sessão salva no histórico")
            } catch (e: Exception) { field.error = e.message }
        } }; dialog.show()
    }
    private fun concludeBook(b: LibraryBook) {
        AlertDialog.Builder(activity).setTitle("Concluir ${b.title}?")
            .setMessage("A página será marcada como ${b.totalPages}. O tempo da leitura será salvo e a média por página será calculada.")
            .setPositiveButton("Concluir livro") { _, _ -> attempt {
                val session = repository.finishBook(b.id)
                changed(); toast("Livro concluído · ${readingPace(session.millisPerPage)}")
            } }.setNegativeButton("Continuar lendo", null).show()
    }
    private fun updatePage(b: LibraryBook) {
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val field = input(body, "Página atual (0 a ${b.totalPages})", b.currentPage.toString(), true)
        ui.label(body, "Esta atualização mantém o tempo da leitura em andamento.", 13, ui.muted)
        val dialog = AlertDialog.Builder(activity).setTitle("Atualizar progresso").setView(body).setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { try {
            val page = field.text.toString().toIntOrNull() ?: throw IllegalArgumentException("Informe a página.")
            repository.updateProgress(b.id, page); dialog.dismiss(); changed()
        } catch (e: Exception) { field.error = e.message } } }; dialog.show()
    }
    private fun input(parent: LinearLayout, hint: String, value: String, numeric: Boolean): EditText = EditText(activity).apply {
        this.hint = hint; setText(value); setTextColor(ui.text); setHintTextColor(ui.muted); textSize = 14f
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.outlined(0x40151515)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isSingleLine = true; minHeight = ui.dp(52)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
    }
    private fun captureEditor() {
        if (screen != "editor" || fields.isEmpty()) return
        val b = editor ?: return
        consumeTypedSubgenre()
        val enteredTotal = fields["total"]?.text?.toString()?.toIntOrNull()
        val known = enteredTotal != null && enteredTotal > 0
        val enteredPage = fields["page"]?.text?.toString()?.toIntOrNull() ?: 0
        editor = b.copy(saga = fields["saga"]?.text?.toString().orEmpty().trim(), sequenceNumber = fields["sequence"]?.text?.toString()?.let { if (it.isBlank()) null else it.toIntOrNull() ?: 0 }, title = fields["title"]?.text?.toString().orEmpty(), author = fields["author"]?.text?.toString().orEmpty(), publisher = fields["publisher"]?.text?.toString().orEmpty(), genre = editingGenre, subgenre = editingSubgenres.firstOrNull().orEmpty(), subgenres = editingSubgenres.toList(), totalPages = enteredTotal ?: if (b.collectionId.isNotBlank()) 1 else 0,
            currentPage = if (known && !b.pagesKnown && b.readFinished && enteredPage == 0) enteredTotal!! else enteredPage,
            pagesKnown = known, readFinished = if (known) false else b.readFinished)
    }
    private fun edit(parent: LinearLayout) {
        val b = editor ?: return
        fields.clear(); editingGenre = b.genre; editingSubgenres.clear(); editingSubgenres.addAll(b.selectedSubgenres)
        val header = row(parent)
        ui.icon(header, "back", "Cancelar edição", 40) { back() }
        ui.label(header, if (b.collectionId.isNotBlank()) (if (newBook) "Adicionar volume" else "Editar volume") else if (newBook) "Adicionar livro" else "Editar livro", 26, bold = true).apply {
            typeface = Typeface.create("serif", Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        ui.label(parent, "Sua biblioteca, do seu jeito", 12, ui.muted)
        fun section(title: String, subtitle: String = "") = detailPanel(parent, 0x66151515, 0xff393939.toInt(), 14, glass = true).apply {
            ui.label(this, title, 18, bold = true).typeface = Typeface.create("serif", Typeface.BOLD)
            if (subtitle.isNotBlank()) ui.label(this, subtitle, 11, ui.muted)
        }
        fun field(target: LinearLayout, key: String, caption: String, value: String, number: Boolean = false) {
            ui.label(target, caption, 12, ui.muted, true); fields[key] = input(target, caption, value, number)
        }
        val identity = section("Livro", "Digite o título e escolha um autor da sua biblioteca ou informe outro.")
        field(identity, "title", "Título *", b.title)
        if(catalog!=null && newBook && b.collectionId.isBlank()) catalogSuggestions(identity)
        suggestedField(identity, "author", "Autor", b.author, repository.books().map { it.author })
        suggestedField(identity, "publisher", "Editora", b.publisher, repository.books().map { it.publisher } + repository.collections().map { it.publisher })
        genreFields(section("Classificação", "Gênero organiza a Estante; a cor dos cartões acompanha a capa."))
        val saga = section("Saga e edição", "Opcional. As sugestões usam as sagas já cadastradas.")
        suggestedField(saga, "saga", "Nome da saga", b.saga, repository.books().map { it.saga })
        field(saga, "sequence", if (b.collectionId.isNotBlank()) "Número do volume" else "Número na saga", b.sequenceNumber?.toString().orEmpty(), true)
        val progress = section("Leitura", if (!b.pagesKnown && b.collectionId.isNotBlank()) "Páginas opcionais. Volume já lido mantém a conclusão ao informar o total." else "Use página 0 se ainda não começou.")
        val numbers = row(progress)
        val total = ui.column(); val page = ui.column()
        numbers.addView(total, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(10) })
        numbers.addView(page, LinearLayout.LayoutParams(0, -2, 1f))
        field(total, "total", "Total de páginas *", if (b.totalPages == 0 || !b.pagesKnown) "" else b.totalPages.toString(), true)
        field(page, "page", "Página atual", b.currentPage.toString(), true)
        val art = section("Capa", "A capa também será usada no fundo da leitura.")
        catalogCoverAction(art,b) { name->
            if(screen=="editor" && editor?.id==b.id) { captureEditor();editor=editor?.copy(cover=name);fields.clear();changed() }
        }
        val preview = row(art); cover(preview, b.copy(title = b.title.ifBlank { "Nova leitura" }), 76, 112)
        val options = ui.column(); preview.addView(options, LinearLayout.LayoutParams(0, -2, 1f))
        ui.action(options, "Tirar foto") {
            catalogGeneration++;captureEditor(); launchPhoto(b, "cover", br.com.viblio.camera.BookPhotoCropActivity.SOURCE_CAMERA)
        }
        ui.action(options, if (b.cover.isBlank()) "Escolher imagem" else "Trocar imagem") {
            catalogGeneration++;captureEditor(); launchPhoto(b, "cover", br.com.viblio.camera.BookPhotoCropActivity.SOURCE_GALLERY)
        }
        ui.action(options, "Buscar capa") {
            captureEditor(); launchDocument(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?tbm=isch&q=" + Uri.encode(requireNotNull(editor).let { CoverSearch.query(it.title, it.author, it.kind, it.sequenceNumber, it.publisher) }))), WEB)
        }
        if (b.cover.isNotBlank()) ui.action(art, "Remover capa") {
            catalogGeneration++;captureEditor(); editor = editor?.copy(cover = "", background = if (editor?.background == "cover") "black" else editor!!.background); changed()
        }
        ui.action(parent, if (b.collectionId.isNotBlank()) "Salvar volume" else "Salvar livro", primary = true) { saveEditor() }
        ui.action(parent, "Cancelar") { back() }
        if (!newBook) ui.action(parent, if(usePersonalArchive) "Remover da Estante" else "Excluir livro") {
            AlertDialog.Builder(activity).setTitle("Excluir ${b.title}?").setMessage(if(usePersonalArchive) "O item sairá da Estante. Nota e histórico serão guardados em Conta → Removidos da Estante." else "O livro, suas sessões e datas de leitura serão removidos deste aparelho. Exporte um backup para guardá-los.")
                .setPositiveButton(if(usePersonalArchive)"Remover" else "Excluir") { _, _ -> attempt {if(usePersonalArchive)repository.archive(b.id) else repository.delete(b.id); editor = null; fields.clear(); selected = null; show("library")} }.setNegativeButton("Cancelar", null).show()
        }
    }
    private fun openCatalogCovers(book:LibraryBook,chosen:(LibraryBook)->Unit){
        val gateway=catalog ?: return
        if(!gateway.configured){gateway.configure();return}
        if(screen=="editor")captureEditor()
        if(screen=="collection_editor")captureCollectionEditor()
        val generation=++catalogGeneration;val from=screen;val target=selected;val title=selectedCollection
        fun valid()=!destroyed && catalogGeneration==generation && screen==from && selected==target && selectedCollection==title
        fun show(record:br.com.centralcasa.books.catalog.CatalogBook){
            gateway.chooseCover(record.id,record.source){result->if(valid())result.fold({choice->
                gateway.loadCover(record.copy(coverUrl=choice.url)){loaded->if(valid())loaded.fold({name->
                    catalogCover="";chosen(book.copy(cover=name,catalogId=record.id,catalogSource=record.source))
                },{toast(it.message ?: "Não foi possível carregar a capa.")})}
            },{toast(it.message ?: "Não foi possível listar as capas.")})}
        }
        if(book.catalogId!=null){show(br.com.centralcasa.books.catalog.CatalogBook(book.catalogId,book.catalogSource,book.title));return}
        gateway.search(book.title.takeIf {it.trim().length>=3}.orEmpty(),book.author.takeIf {it.trim().length>=3}.orEmpty(),""){result->if(valid())result.fold({rows->
            val matches=rows.filter {it.kind==book.kind && ShelfFilters.key(it.title)==ShelfFilters.key(book.title) && (book.sequenceNumber==null && it.sequence==null || book.sequenceNumber==it.sequence)}
            if(matches.isEmpty()){toast("Não encontrei esta obra. Confira o título e o autor na Pesquisa.");return@fold}
            val authorMatches=matches.filter {book.author.isBlank() || ShelfFilters.key(it.author)==ShelfFilters.key(book.author)}
            show((authorMatches.ifEmpty {matches}).first())
        },{toast(it.message ?: "Não foi possível consultar o catálogo.")})}
    }
    private fun launchPhoto(book:LibraryBook,mode:String,source:String){
        val intent=Intent(activity,br.com.viblio.camera.BookPhotoCropActivity::class.java)
            .putExtra(br.com.viblio.camera.BookPhotoCropActivity.EXTRA_MODE,mode)
            .putExtra(br.com.viblio.camera.BookPhotoCropActivity.EXTRA_SOURCE,source)
            .putExtra(br.com.viblio.camera.BookPhotoCropActivity.EXTRA_BOOK_ID,book.id)
            .putExtra(br.com.viblio.camera.BookPhotoCropActivity.EXTRA_PROFILE,LibraryProfiles.database(activity))
        launchDocument(intent,if(mode=="spine")SPINE_PHOTO else COVER_PHOTO)
    }
    private fun spinePhoto(book:LibraryBook){
        val options=mutableListOf("Tirar foto da lombada","Imagem do celular").apply { if(book.spineImage.isNotBlank())add("Usar lombada padrão") }
        AlertDialog.Builder(activity).setTitle("Foto da lombada").setItems(options.toTypedArray()){_,option->
            when(options[option]){
                "Tirar foto da lombada"->launchPhoto(book,"spine",br.com.viblio.camera.BookPhotoCropActivity.SOURCE_CAMERA)
                "Imagem do celular"->launchPhoto(book,"spine",br.com.viblio.camera.BookPhotoCropActivity.SOURCE_GALLERY)
                else->attempt {
                    val latest=repository.book(book.id)?.takeUnless {it.archived} ?: error("Livro não encontrado.")
                    repository.save(latest.copy(spineImage=""));toast("Lombada padrão restaurada");changed()
                }
            }
        }.setNegativeButton("Cancelar",null).show()
    }
    private fun personalCover(book:LibraryBook){
        val options=(if(catalog!=null)listOf("Capas do catálogo") else emptyList())+listOf("Tirar foto da capa","Imagem do celular")
        AlertDialog.Builder(activity).setTitle("Trocar capa").setItems(options.toTypedArray()){_,option->
            if(options[option]=="Tirar foto da capa")launchPhoto(book,"cover",br.com.viblio.camera.BookPhotoCropActivity.SOURCE_CAMERA)
            else if(options[option]=="Imagem do celular")launchPhoto(book,"cover",br.com.viblio.camera.BookPhotoCropActivity.SOURCE_GALLERY)
            else openCatalogCovers(book){choice->
                val latest=repository.book(book.id)
                if(latest==null || latest.archived || latest.cover!=book.cover || latest.catalogId!=book.catalogId || latest.catalogSource!=book.catalogSource){toast("O livro mudou. Abra a troca de capa novamente.");return@openCatalogCovers}
                attempt {repository.save(latest.copy(cover=choice.cover,catalogId=choice.catalogId,catalogSource=choice.catalogSource));catalog?.saved(requireNotNull(repository.book(book.id)));changed()}
            }
        }.setNegativeButton("Cancelar",null).show()
    }
    private fun catalogCoverAction(parent:LinearLayout,book:LibraryBook,choose:(String)->Unit){
        if(catalog==null)return
        ui.action(parent,"Capas do catálogo") {openCatalogCovers(book){choice->
            if(screen=="editor" && editor?.id==book.id){captureEditor();editor=editor?.copy(catalogId=choice.catalogId,catalogSource=choice.catalogSource)}
            choose(choice.cover)
        }}.apply {background=ui.outlined()}
    }
    private fun catalogSuggestions(parent:LinearLayout) {
        val gateway=catalog ?: return
        if(gateway.supportsIsbnScan){
            val scanRow=row(parent)
            ui.label(scanRow,"Buscar pelo ISBN",12,ui.muted).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            ui.icon(scanRow,"barcode","Ler ISBN com a câmera",48){gateway.scanIsbn()}.apply {tag="editor-scan-isbn"}
        }
        val status=ui.label(parent,if(gateway.configured) "Digite três letras para buscar no catálogo." else "Catálogo não conectado. Cadastro manual disponível.",11,ui.muted)
        val results=ui.column()
        val suggestions=ScrollView(activity).apply { addView(results);visibility=View.GONE;isNestedScrollingEnabled=true }
        parent.addView(suggestions,LinearLayout.LayoutParams(-1,ui.dp(220)))
        suggestions.setOnTouchListener { view,event ->
            view.parent.requestDisallowInterceptTouchEvent(event.actionMasked !in listOf(android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL));false
        }
        val title=fields.getValue("title")
        val formBook=editor?.id
        fun valid(generation:Int)=!destroyed && screen=="editor" && editor?.id==formBook && catalogGeneration==generation && status.parent!=null && fields["title"]===title
        fun choose(result:br.com.centralcasa.books.catalog.CatalogBook) {
            captureEditor()
            val current=editor ?: return
            val manualCover=current.cover.takeIf { it.isNotBlank() && (!newBook || it!=catalogCover) }.orEmpty()
            editor=result.fill(current).copy(cover=manualCover)
            fields.clear();changed()
            val generation=++catalogGeneration
            if(manualCover.isBlank() && result.coverUrl.isNotBlank()) {
                gateway.loadCover(result) { loaded ->
                    if(!destroyed && screen=="editor" && editor?.id==current.id && catalogGeneration==generation) {
                        captureEditor()
                        if(editor?.cover.isNullOrBlank()) loaded.fold({ name->catalogCover=name;editor=editor?.copy(cover=name);fields.clear();changed() },{ toast("Dados preenchidos. Não foi possível carregar a capa; escolha uma imagem se quiser.") })
                    }
                }
            }
        }
        fun search(author:String="",isbn:String="") {
            val query=if(isbn.isNotBlank()) "" else title.text.toString().trim()
            val generation=++catalogGeneration;results.removeAllViews();suggestions.visibility=View.GONE
            if(!gateway.configured || query.length<3 && author.length<3 && isbn.isBlank()) return
            status.text="Buscando no catálogo…"
            val deliver:(Result<List<br.com.centralcasa.books.catalog.CatalogBook>>)->Unit = { response ->
                if(valid(generation)) response.fold({ items ->
                    status.text=if(items.isEmpty()) "Nenhum resultado. Você pode cadastrar manualmente." else "Escolha uma edição ou continue preenchendo manualmente."
                    val matching=items.filter {it.kind==editor?.kind}
                    if(isbn.isNotBlank() && matching.size==1){choose(matching.single());return@fold}
                    if(items.isNotEmpty() && matching.isEmpty())status.text="Esta edição pertence a outra categoria. Use a Pesquisa para adicioná-la."
                    suggestions.visibility=if(matching.isEmpty()) View.GONE else View.VISIBLE
                    matching.forEach { record ->
                        ui.action(results,listOf(record.title,record.author,record.publisher,record.isbn).filter(String::isNotBlank).joinToString(" · ")) { choose(record) }
                    }
                },{ status.text="Catálogo indisponível. Cadastro manual disponível." })
            }
            if(isbn.isNotBlank())gateway.searchIsbn(isbn,deliver) else gateway.search(query.takeIf {it.length>=3}.orEmpty(),author,done=deliver)
        }
        var pending:Runnable?=null
        title.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                catalogGeneration++;pending?.let(handler::removeCallbacks);results.removeAllViews();suggestions.visibility=View.GONE
                if(gateway.configured && s.toString().trim().length>=3) {
                    status.text="Buscando no catálogo…";pending=Runnable { if(screen=="editor" && fields["title"]===title) search() };handler.postDelayed(pending!!,400)
                } else status.text=if(gateway.configured) "Digite três letras para buscar no catálogo." else "Catálogo não conectado. Cadastro manual disponível."
            }
            override fun afterTextChanged(s:Editable?) {}
        })
        pendingScannedIsbn?.let {isbn->pendingScannedIsbn=null;handler.post {if(!destroyed && screen=="editor" && fields["title"]===title && editor?.id==formBook)search(isbn=isbn)}}
        ui.action(parent,if(gateway.configured) "Buscar por autor ou ISBN" else "Conectar catálogo") {
            if(!gateway.configured) gateway.configure() else {
                val body=ui.column().apply { setPadding(ui.dp(16),ui.dp(8),ui.dp(16),0) }
                val author=input(body,"Autor (opcional)",fields["author"]?.text?.toString().orEmpty(),false)
                val isbn=input(body,"ISBN (opcional)","",false)
                AlertDialog.Builder(activity).setTitle("Buscar no catálogo").setView(body).setPositiveButton("Buscar") { _,_->search(author.text.toString().trim(),isbn.text.toString().trim()) }.setNegativeButton("Cancelar",null).show()
            }
        }
    }
    private fun suggestedField(parent: LinearLayout, key: String, title: String, value: String, vocabulary: List<String>) {
        ui.label(parent, title, 12, ui.muted, true)
        val field = AutoCompleteTextView(activity).apply {
            setText(value); hint = title; setTextColor(ui.text); setHintTextColor(ui.muted); textSize = 14f
            threshold = 1; isSingleLine = true; minHeight = ui.dp(52)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.outlined(0x40151515)
            setAdapter(BookMetadataAdapter(activity, vocabulary))
            setOnClickListener { showDropDown() }
            setOnFocusChangeListener { _, focused -> if (focused) showDropDown() }
        }
        fields[key] = field
        parent.addView(field, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
    }
    private fun createMetadata(title: String, caption: String, accept: (String) -> Unit) {
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), 0) }
        val value = input(body, caption, "", false)
        val dialog = AlertDialog.Builder(activity).setTitle(title).setView(body).setPositiveButton("Criar", null).setNegativeButton("Cancelar", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = value.text.toString().trim().replace(Regex("\\s+"), " ")
            if (name.isBlank()) value.error = "Informe um nome." else { dialog.dismiss(); accept(name) }
        }
    }
    private fun captureClassification() {
        if (screen == "collection_editor") captureCollectionEditor() else captureEditor()
    }
    private fun startLightNovel() {
        captureEditor()
        val b = editor ?: return
        collectionFields.clear()
        collectionEditor = CollectionTitle(kind = "book", title = b.title, author = b.author,
            publisher = b.publisher, genre = b.genre.takeUnless { ShelfFilters.key(it) in listOf("light novel", "light novels") }.orEmpty(), subgenres = b.selectedSubgenres.takeUnless { ShelfFilters.key(b.genre) in listOf("light novel", "light novels") }.orEmpty(), cover = b.cover, rating = b.rating)
        collectionEditorNew = true; collectionOwned = "1"
        fields.clear(); selected = null; selectedCollection = null
        show("collection_editor")
    }
    private fun genreFields(parent: LinearLayout) {
        subgenreInput = null
        val books = repository.books() + repository.collections().map { it.shelfBook(emptyList()) }
        ui.label(parent, "Gênero principal", 12, ui.muted, true)
        ui.action(parent, editingGenre.ifBlank { "Selecionar gênero" } + "  ▾") {
            val canCreateLightNovel = screen == "editor" && editor?.kind == "book" && editor?.collectionId.orEmpty().isBlank()
            val genres = BookGenres.suggestions(books.map { it.genre } + editingGenre)
            val options = genres + (if (canCreateLightNovel) listOf("Light novel · por volumes") else emptyList()) + "+ Criar novo gênero"
            fun choose(name: String) {
                if (canCreateLightNovel && ShelfFilters.key(name) in listOf("light novel", "light novels")) { startLightNovel(); return }
                captureClassification()
                val category = BookGenres.split(genres.firstOrNull { ShelfFilters.key(it) == ShelfFilters.key(name) } ?: name)
                if (category.subgenre.isNotBlank() && editingSubgenres.none { ShelfFilters.key(it) == ShelfFilters.key(category.subgenre) }) {
                    if (editingSubgenres.size >= 3) { toast("Remova um subgênero para adicionar outro."); return }
                    editingSubgenres.add(category.subgenre)
                }
                editingGenre = category.genre; captureClassification(); changed()
            }
            AlertDialog.Builder(activity).setTitle("Gênero principal").setItems(options.toTypedArray()) { _, index ->
                if (index < genres.size) choose(genres[index])
                else if (canCreateLightNovel && index == genres.size) startLightNovel()
                else createMetadata("Novo gênero", "Nome do gênero", ::choose)
            }.setNeutralButton("Sem gênero") { _, _ -> subgenreInput?.setText(""); editingGenre = ""; editingSubgenres.clear(); captureClassification(); changed() }
                .setNegativeButton("Cancelar", null).show()
        }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; background = ui.outlined(0x40151515) }
        val heading = row(parent).apply { setPadding(0, ui.dp(12), 0, ui.dp(8)) }
        ui.label(heading, "Subgêneros", 14, bold = true).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        ui.label(heading, "${editingSubgenres.size} / 3", 11, ui.muted).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
            background = ui.shape(ui.activeSurface, 8); layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        val color = editor?.let(::readingAccent) ?: collectionEditor?.shelfBook(repository.books())?.let(::readingAccent) ?: 0xff633039.toInt()
        if (editingSubgenres.isNotEmpty()) {
            val tiles = row(parent).apply { gravity = Gravity.TOP; setPadding(0, ui.dp(3), 0, ui.dp(6)) }
            editingSubgenres.toList().forEachIndexed { index, name ->
                val chip = ui.row().apply {
                    minimumHeight = ui.dp(48); setPadding(ui.dp(8), ui.dp(8), ui.dp(4), ui.dp(8))
                    background = ui.outlined((color and 0x00ffffff) or 0x40000000, color, 12)
                }
                tiles.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = if (index < editingSubgenres.lastIndex) ui.dp(6) else 0 })
                ui.label(chip, name, 12).apply {
                    maxLines = 3; includeFontPadding = true; ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                ui.action(chip, "×") { editingSubgenres.remove(name); captureClassification(); changed() }.apply {
                    contentDescription = "Remover subgênero $name"; minHeight = ui.dp(32); textSize = 18f
                    setPadding(0, 0, 0, 0); background = ui.ripple(Color.TRANSPARENT, 8)
                    layoutParams = LinearLayout.LayoutParams(ui.dp(28), ui.dp(36))
                }
            }
        } else ui.label(parent, "Adicione detalhes ao gênero principal.", 12, ui.muted)
        val vocabulary = BookGenres.suggestions(BookGenres.subgenres(books, editingGenre) + settings.savedSubgenres(editingGenre) + editingSubgenres)
        settings.rememberSubgenres(editingGenre, vocabulary)
        if (editingSubgenres.size < 3) {
            subgenreInputGenre = editingGenre
            val field = AutoCompleteTextView(activity).apply {
                hint = if (editingGenre.isBlank()) "Escolha o gênero principal primeiro" else "Digite ou escolha um subgênero"
                setTextColor(ui.text); setHintTextColor(ui.muted); textSize = 14f; isSingleLine = true
                minHeight = ui.dp(52); threshold = 1
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.outlined(0x40151515, color, 12)
                isEnabled = editingGenre.isNotBlank(); alpha = if (isEnabled) 1f else .55f
                setAdapter(BookMetadataAdapter(activity, vocabulary.filter { candidate -> editingSubgenres.none { ShelfFilters.key(it) == ShelfFilters.key(candidate) } }))
                setOnClickListener { if (isEnabled) showDropDown() }
                setOnItemClickListener { _, _, _, _ ->
                    if (consumeTypedSubgenre()) { captureClassification(); changed() }
                }
                setOnEditorActionListener { _, action, event ->
                    if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP) {
                        if (consumeTypedSubgenre()) { captureClassification(); changed() }; true
                    } else false
                }
            }
            subgenreInput = field
            parent.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6); bottomMargin = ui.dp(6) })
        }
        ui.label(parent, if (editingGenre.isBlank()) "Escolha o gênero principal para ver as sugestões." else "Digite o nome e confirme no teclado ou salve o título. Ele ficará disponível em outros títulos de $editingGenre.", 11, ui.muted)
    }
    private fun consumeTypedSubgenre(): Boolean {
        val field = subgenreInput ?: return false
        val value = field.text.toString().trim().replace(Regex("\\s+"), " ")
        if (value.isBlank() || subgenreInputGenre.isBlank()) return false
        if (editingSubgenres.any { ShelfFilters.key(it) == ShelfFilters.key(value) }) { field.setText(""); return false }
        if (editingSubgenres.size >= 3) { field.error = "Escolha até três subgêneros."; return false }
        val existing = settings.savedSubgenres(subgenreInputGenre).firstOrNull { ShelfFilters.key(it) == ShelfFilters.key(value) }
        val name = existing ?: value
        settings.rememberSubgenres(subgenreInputGenre, listOf(name))
        editingSubgenres.add(name); field.setText("")
        return true
    }

    private fun saveEditor() {
        captureEditor()
        try {
            val b = requireNotNull(editor)
            val totalText = fields["total"]?.text?.toString().orEmpty()
            require(totalText.toIntOrNull() != null || totalText.isBlank() && b.collectionId.isNotBlank()) { "Informe o total de páginas." }
            require(fields["page"]?.text?.toString()?.toIntOrNull() != null) { "Informe a página atual (use 0 se não começou)." }
            repository.save(b.copy(updatedAt = System.currentTimeMillis())); catalog?.saved(b); selected = b.id; editor = null; fields.clear(); show("detail")
        } catch (e: Exception) { toast(e.message ?: "Confira os campos.") }
    }
    private var statisticsTab="summary" // "summary" | "plan" | "periods"
    private var readingPeriodUnit=ReadingPeriodUnit.MONTH
    private var readingPeriodAnchor=ReadingDays.at(System.currentTimeMillis())
    private var periodVisibleBooks=10
    private fun periodStatistics(parent:LinearLayout,books:List<LibraryBook>,sessions:List<ReadingSession>,mint:Int){
        val format=java.text.NumberFormat.getIntegerInstance(Locale("pt","BR"))
        val dates=repository.dates()
        val range=ReadingPeriods.range(readingPeriodAnchor,readingPeriodUnit)
        val todayDay=ReadingDays.at(System.currentTimeMillis())
        val currentRange=ReadingPeriods.range(todayDay,readingPeriodUnit)
        val isCurrentPeriod=range.start==currentRange.start
        val prevAnchor=ReadingPeriods.shift(readingPeriodAnchor,readingPeriodUnit,-1)
        val prevRange=ReadingPeriods.range(prevAnchor,readingPeriodUnit)

        val summary=PeriodReading.from(books,sessions,dates,range)
        val prevSummary=PeriodReading.from(books,sessions,dates,prevRange)
        val completedIds=PeriodCompletions.completedBookIds(books,sessions,dates,range)
        val activeBookIds=summary.bookIds+completedIds
        val countUnit=when(statisticsType){"book"->"livros";"manga","comic"->"volumes";else->"exemplares"}
        val singleUnit=when(statisticsType){"book"->"livro";"manga","comic"->"volume";else->"exemplar"}

        val wrap=ui.column().apply {setPadding(ui.dp(20),ui.dp(10),ui.dp(20),ui.dp(20))}
        parent.addView(wrap,LinearLayout.LayoutParams(-1,-2))

        // 1) Compact segmented unit selector (keeps each period-unit:* background == null for tests)
        val unitBox=row(wrap).apply {
            background=ui.outlined(ui.surface,ui.lineColor,0)
        }
        (unitBox.layoutParams as LinearLayout.LayoutParams).bottomMargin=ui.dp(10)
        ReadingPeriodUnit.entries.forEach { unit ->
            val active=unit==readingPeriodUnit
            val tab=ui.column().apply {
                tag="period-unit:${unit.name}";isSelected=active;isFocusable=true;contentDescription=unit.label
                setOnClickListener {readingPeriodUnit=unit;periodVisibleBooks=10;changed()}
            }
            unitBox.addView(tab,LinearLayout.LayoutParams(0,-2,1f))
            ui.label(tab,unit.label,11,if(active)mint else ui.muted,bold=active).apply {
                gravity=Gravity.CENTER;minHeight=ui.dp(38);maxLines=1
                (layoutParams as LinearLayout.LayoutParams).bottomMargin=0
            }
            tab.addView(View(activity).apply {setBackgroundColor(if(active)mint else Color.TRANSPARENT)},LinearLayout.LayoutParams(-1,ui.dp(2)))
        }

        // 2) Clean period navigator (< Novembro 2026 >) + contextual actions
        val nav=row(wrap).apply {gravity=Gravity.CENTER_VERTICAL}
        (nav.layoutParams as LinearLayout.LayoutParams).bottomMargin=ui.dp(12)
        val prevBtn=TextView(activity).apply {
            text="‹";textSize=19f;gravity=Gravity.CENTER;setTextColor(mint)
            contentDescription="Período anterior";isFocusable=true
            background=ui.outlined(ui.surface,ui.lineColor,0)
            setOnClickListener {readingPeriodAnchor=ReadingPeriods.shift(readingPeriodAnchor,readingPeriodUnit,-1);periodVisibleBooks=10;changed()}
        }
        nav.addView(prevBtn,LinearLayout.LayoutParams(ui.dp(36),ui.dp(36)))
        val navCenter=ui.column().apply {gravity=Gravity.CENTER_HORIZONTAL}
        nav.addView(navCenter,LinearLayout.LayoutParams(0,-2,1f).apply {marginStart=ui.dp(8);marginEnd=ui.dp(8)})
        val niceLabel=range.label.replaceFirstChar {it.titlecase(Locale("pt","BR"))}
        ui.label(navCenter,niceLabel,19).apply {
            tag="period-label";gravity=Gravity.CENTER;typeface=Typeface.create("serif",Typeface.NORMAL)
            includeFontPadding=true;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
            (layoutParams as LinearLayout.LayoutParams).bottomMargin=0
        }
        val showRetro=ui.rectilinear && (readingPeriodUnit==ReadingPeriodUnit.MONTH || readingPeriodUnit==ReadingPeriodUnit.YEAR)
        if(!isCurrentPeriod || showRetro){
            val subActions=ui.row().apply {gravity=Gravity.CENTER}
            navCenter.addView(subActions,LinearLayout.LayoutParams(-2,-2).apply {topMargin=ui.dp(2)})
            if(!isCurrentPeriod){
                TextView(activity).apply {
                    text="Período atual";textSize=10.5f;setTextColor(mint)
                    setPadding(ui.dp(4),ui.dp(2),ui.dp(4),ui.dp(2))
                    setOnClickListener {readingPeriodAnchor=todayDay;periodVisibleBooks=10;changed()}
                }.also {subActions.addView(it)}
            }
            if(!isCurrentPeriod && showRetro){
                ui.label(subActions,"·",10,ui.muted).apply {
                    layoutParams=LinearLayout.LayoutParams(-2,-2).apply {marginStart=ui.dp(3);marginEnd=ui.dp(3)}
                }
            }
            if(showRetro){
                TextView(activity).apply {
                    text="Retrospectiva ›";textSize=10.5f;setTextColor(mint)
                    typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                    setPadding(ui.dp(4),ui.dp(2),ui.dp(4),ui.dp(2))
                    setOnClickListener {
                        val dlg=retrospectiveDialog ?: RetrospectiveDialog(activity,ui,repository).also {retrospectiveDialog=it}
                        val title=if(readingPeriodUnit==ReadingPeriodUnit.YEAR)"Meu ano em livros" else "Meu mês em livros"
                        val slug="viblio_${range.start.replace("-","")}_${readingPeriodUnit.name.lowercase(Locale.US)}"
                        dlg.showRange(range,title,slug)
                    }
                }.also {subActions.addView(it)}
            }
        }
        val nextBtn=TextView(activity).apply {
            text="›";textSize=19f;gravity=Gravity.CENTER;setTextColor(mint)
            contentDescription="Próximo período";isFocusable=true
            background=ui.outlined(ui.surface,ui.lineColor,0)
            setOnClickListener {readingPeriodAnchor=ReadingPeriods.shift(readingPeriodAnchor,readingPeriodUnit,1);periodVisibleBooks=10;changed()}
        }
        nav.addView(nextBtn,LinearLayout.LayoutParams(ui.dp(36),ui.dp(36)))

        // 3) Editorial Hero Card with left accent stripe + comparison badge
        val heroOuter=ui.row().apply {
            background=ui.outlined(ui.surface,ui.lineColor,0)
        }
        wrap.addView(heroOuter,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(10)})
        heroOuter.addView(View(activity).apply {setBackgroundColor(mint)},LinearLayout.LayoutParams(ui.dp(3),-1))
        val heroBody=ui.column().apply {setPadding(ui.dp(14),ui.dp(12),ui.dp(14),ui.dp(12))}
        heroOuter.addView(heroBody,LinearLayout.LayoutParams(0,-2,1f))

        val heroTop=row(heroBody).apply {gravity=Gravity.BOTTOM}
        val pagesCol=ui.column()
        heroTop.addView(pagesCol,LinearLayout.LayoutParams(0,-2,1f))
        ui.label(pagesCol,"PÁGINAS REGISTRADAS",9,ui.muted).apply {
            letterSpacing=0.08f;(layoutParams as LinearLayout.LayoutParams).bottomMargin=ui.dp(2)
        }
        ui.label(pagesCol,format.format(summary.pages),36,mint).apply {
            tag="period-metric:1";typeface=Typeface.create("serif",Typeface.NORMAL);includeFontPadding=false
            (layoutParams as LinearLayout.LayoutParams).bottomMargin=0
        }
        val delta=PeriodInsights.deltaPercent(summary.pages,prevSummary.pages)
        if(delta!=null){
            val pos=delta>=0
            val badgeCol=if(pos) Color.rgb(127,176,134) else Color.rgb(220,131,115)
            val sign=if(pos) "+" else ""
            TextView(activity).apply {
                text="$sign$delta% vs ant."
                textSize=10f;setTextColor(badgeCol)
                background=ui.outlined(Color.argb(38,Color.red(badgeCol),Color.green(badgeCol),Color.blue(badgeCol)),Color.argb(120,Color.red(badgeCol),Color.green(badgeCol),Color.blue(badgeCol)),0)
                setPadding(ui.dp(7),ui.dp(3),ui.dp(7),ui.dp(3))
            }.also {heroTop.addView(it,LinearLayout.LayoutParams(-2,-2))}
        }

        heroBody.addView(View(activity).apply {setBackgroundColor(ui.lineColor)},LinearLayout.LayoutParams(-1,ui.dp(1)).apply {topMargin=ui.dp(10);bottomMargin=ui.dp(10)})

        val kpiRow=row(heroBody)
        val byId=books.associateBy {it.id}
        val timedKnown=summary.sessions.filter {byId[it.bookId]?.pagesKnown==true}
        val timedDur=timedKnown.sumOf {it.durationMs}
        val timedPages=timedKnown.sumOf {it.pages.toLong()}
        val speedHint=if(timedDur>0L && timedPages>0L) " · ${format.format(kotlin.math.round(timedPages*3600000.0/timedDur).toLong())} p/h" else ""

        listOf(
            Triple(3,completedIds.size.toString(),if(completedIds.size==1)"$singleUnit concluído" else "$countUnit concluídos"),
            Triple(0,activeBookIds.size.toString(),"com leitura"),
            Triple(2,compactReadingTime(summary.durationMs),"tempo$speedHint"),
        ).forEach { (idx,value,caption) ->
            val col=ui.column()
            kpiRow.addView(col,LinearLayout.LayoutParams(0,-2,1f))
            ui.label(col,value,18,ui.text).apply {
                tag="period-metric:$idx";typeface=Typeface.create("serif",Typeface.NORMAL)
                maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                (layoutParams as LinearLayout.LayoutParams).bottomMargin=ui.dp(1)
            }
            ui.label(col,caption,9,ui.muted).apply {
                maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                (layoutParams as LinearLayout.LayoutParams).bottomMargin=0
            }
        }

        // 4) Interactive Vertical Bar Chart Card
        val buckets=PeriodInsights.buckets(readingPeriodAnchor,readingPeriodUnit,books,sessions,dates)
        val peakVal=buckets.maxOfOrNull {it.value} ?: 0L
        val chartCard=ui.column().apply {
            setPadding(ui.dp(14),ui.dp(12),ui.dp(14),ui.dp(10))
            background=ui.outlined(ui.surface,ui.lineColor,0)
        }
        wrap.addView(chartCard,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(10)})
        val chartHead=row(chartCard).apply {gravity=Gravity.CENTER_VERTICAL}
        (chartHead.layoutParams as LinearLayout.LayoutParams).bottomMargin=ui.dp(4)
        val chartTitle=when(readingPeriodUnit){
            ReadingPeriodUnit.YEAR -> "RITMO POR MÊS · ${readingPeriodAnchor.take(4)}"
            ReadingPeriodUnit.MONTH -> "RITMO POR SEMANA"
            ReadingPeriodUnit.WEEK -> "RITMO DIÁRIO"
            ReadingPeriodUnit.DAY -> "RITMO DO DIA"
        }
        ui.label(chartHead,chartTitle,9,ui.muted).apply {
            letterSpacing=0.08f;layoutParams=LinearLayout.LayoutParams(0,-2,1f)
        }
        val peakUnit=if(summary.pages>0L) "${format.format(peakVal)} págs" else format.format(peakVal)
        ui.label(chartHead,if(peakVal>0L)"Pico: $peakUnit" else "Sem registros",10,if(peakVal>0L)mint else ui.muted).apply {
            layoutParams=LinearLayout.LayoutParams(-2,-2)
        }
        val chartEntries=buckets.map { PeriodBarEntry(it.label,it.value) }
        val chartTap:((Int)->Unit)?=when(readingPeriodUnit){
            ReadingPeriodUnit.YEAR -> { idx ->
                buckets.getOrNull(idx)?.targetAnchor?.let { mStart ->
                    readingPeriodAnchor=mStart;readingPeriodUnit=ReadingPeriodUnit.MONTH;periodVisibleBooks=10;changed()
                }
            }
            ReadingPeriodUnit.WEEK -> { idx ->
                buckets.getOrNull(idx)?.targetAnchor?.let { dStart ->
                    readingPeriodAnchor=dStart;readingPeriodUnit=ReadingPeriodUnit.DAY;periodVisibleBooks=10;changed()
                }
            }
            else -> null
        }
        chartCard.addView(PeriodBarChartView(activity,ui,chartEntries,mint,chartTap),LinearLayout.LayoutParams(-1,-2))
        if(readingPeriodUnit!=ReadingPeriodUnit.YEAR){
            val yrRow=row(chartCard).apply {gravity=Gravity.END}
            (yrRow.layoutParams as LinearLayout.LayoutParams).topMargin=ui.dp(4)
            TextView(activity).apply {
                text="Ver meses de ${readingPeriodAnchor.take(4)} ›"
                textSize=10.5f;setTextColor(mint)
                setPadding(ui.dp(4),ui.dp(2),ui.dp(4),ui.dp(2))
                setOnClickListener {readingPeriodUnit=ReadingPeriodUnit.YEAR;periodVisibleBooks=10;changed()}
            }.also {yrRow.addView(it)}
        }

        // 5) Books read in period with real cover thumbnails
        val listCard=ui.column().apply {
            setPadding(ui.dp(14),ui.dp(12),ui.dp(14),ui.dp(12))
            background=ui.outlined(ui.surface,ui.lineColor,0)
        }
        wrap.addView(listCard,LinearLayout.LayoutParams(-1,-2))
        val listHead=row(listCard).apply {gravity=Gravity.CENTER_VERTICAL}
        (listHead.layoutParams as LinearLayout.LayoutParams).bottomMargin=ui.dp(8)
        ui.label(listHead,"Leituras no período",17).apply {
            typeface=Typeface.create("serif",Typeface.NORMAL)
            layoutParams=LinearLayout.LayoutParams(0,-2,1f)
        }
        val rows=books.filter {it.id in activeBookIds}.sortedWith(compareBy<LibraryBook> {if(it.id in completedIds)0 else 1}.thenBy {ShelfFilters.key(it.title)})
        ui.label(listHead,"${rows.size} ${if(rows.size==1)singleUnit else countUnit}",10,ui.muted).apply {
            layoutParams=LinearLayout.LayoutParams(-2,-2)
        }
        rows.take(periodVisibleBooks).forEachIndexed { idx,book ->
            if(idx>0){
                listCard.addView(View(activity).apply {setBackgroundColor(ui.lineColor)},LinearLayout.LayoutParams(-1,ui.dp(1)).apply {topMargin=ui.dp(8);bottomMargin=ui.dp(8)})
            }
            val isDoneInPeriod=book.id in completedIds
            val itemRow=row(listCard).apply {
                gravity=Gravity.CENTER_VERTICAL
                if(onOpenPlannedBook!=null){
                    isFocusable=true
                    setOnClickListener {onOpenPlannedBook?.invoke(book.id)}
                }
            }
            val thumb=ImageView(activity).apply {
                scaleType=ImageView.ScaleType.CENTER_CROP
                background=ui.outlined(ui.activeSurface,ui.lineColor,0)
                setImageDrawable(ui.symbol("book_nav",ui.muted,18))
                if(book.cover.isNotBlank()) images.thumbnail(book.cover)?.let(::setImageBitmap)
            }
            itemRow.addView(thumb,LinearLayout.LayoutParams(ui.dp(34),ui.dp(50)).apply {marginEnd=ui.dp(10)})
            val infoCol=ui.column()
            itemRow.addView(infoCol,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(8)})
            val titleText=book.title+(book.sequenceNumber?.let {if(book.kind=="book" && book.collectionId.isBlank())" · Livro $it" else " · Vol. $it"} ?: "")
            ui.label(infoCol,titleText,13).apply {
                typeface=Typeface.create("serif",Typeface.NORMAL)
                maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                (layoutParams as LinearLayout.LayoutParams).bottomMargin=ui.dp(2)
            }
            val records=summary.sessions.filter {it.bookId==book.id}
            val metaParts=mutableListOf<String>()
            if(book.author.isNotBlank()) metaParts.add(book.author)
            if(records.isNotEmpty()){
                if(book.pagesKnown) metaParts.add("${format.format(records.sumOf {it.pages})} págs.")
                if(records.sumOf {it.durationMs}>0L) metaParts.add(compactReadingTime(records.sumOf {it.durationMs}))
            } else if(!isDoneInPeriod){
                metaParts.add("Leitura registrada")
            } else if(book.pagesKnown){
                metaParts.add("${format.format(book.totalPages)} págs.")
            }
            ui.label(infoCol,metaParts.joinToString(" · "),10,ui.muted).apply {
                maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                (layoutParams as LinearLayout.LayoutParams).bottomMargin=0
            }
            TextView(activity).apply {
                text=if(isDoneInPeriod)"Concluído" else "Lendo"
                textSize=9.5f
                setTextColor(if(isDoneInPeriod)mint else ui.muted)
                background=ui.outlined(ui.surface,if(isDoneInPeriod)mint else ui.lineColor,0)
                setPadding(ui.dp(6),ui.dp(3),ui.dp(6),ui.dp(3))
            }.also {itemRow.addView(it,LinearLayout.LayoutParams(-2,-2))}
        }
        if(rows.isEmpty()) ui.label(listCard,"Nenhuma leitura registrada neste período.",11,ui.muted)
        if(rows.size>periodVisibleBooks){
            ui.action(listCard,"Ver mais títulos"){periodVisibleBooks+=10;changed()}.apply {
                background=null;setTextColor(mint);gravity=Gravity.START
            }
        }
    }
    private fun redesignedStatistics(parent: LinearLayout) {
        val mint=if(ui.rectilinear)ui.accent else 0xff9bd8bb.toInt();val amber=if(ui.rectilinear)ui.muted else 0xffbca566.toInt();val neutral=if(ui.rectilinear)ui.lineColor else 0xff435148.toInt()
        val format=java.text.NumberFormat.getIntegerInstance(Locale("pt","BR"))
        fun label(target:LinearLayout,text:String,size:Int=13,color:Int=ui.text,bold:Boolean=false)=
            ui.label(target,text,size,color,bold).apply {includeFontPadding=true;(layoutParams as LinearLayout.LayoutParams).bottomMargin=ui.dp(4)}
        fun heading(target:LinearLayout,text:String,size:Int=23)=label(target,text,size).apply {typeface=Typeface.create("serif",Typeface.NORMAL)}
        parent.setPadding(0,ui.dp(8),0,ui.dp(24))
        fun card(title:String,icon:String):LinearLayout=ui.column().apply {
            tag="statistics:$icon";setPadding(ui.dp(20),ui.dp(14),ui.dp(20),ui.dp(14))
            if(icon=="book-spines")setBackgroundColor(if(ui.rectilinear)ui.surface else 0xff18251f.toInt())
            parent.addView(this,LinearLayout.LayoutParams(-1,-2))
            val line=row(this)
            heading(line,title,21).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            if(icon!="pie")line.addView(ImageView(activity).apply {
                setImageDrawable(DisplayIcon(icon,if(icon=="books")mint else ui.muted));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(ui.dp(18),ui.dp(18)))
        }
        fun divider(target:LinearLayout)=target.addView(View(activity).apply {setBackgroundColor(if(ui.rectilinear)ui.lineColor else 0xff25372d.toInt())},LinearLayout.LayoutParams(-1,ui.dp(1)).apply {topMargin=ui.dp(10);bottomMargin=ui.dp(10)})
        val header=row(parent).apply {setPadding(ui.dp(if(separateShelfStatistics) 20 else 8),0,ui.dp(16),0)}
        if(!separateShelfStatistics) ui.icon(header,"back","Voltar à estante",44){onStatisticsBack?.invoke() ?: show("library")}
        heading(header,if(ui.rectilinear)"ESTATÍSTICAS" else "Estatísticas",30).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
        val subtabs=row(parent).apply {setPadding(ui.dp(20),ui.dp(4),ui.dp(20),0)}
        val tabDefs=if(ui.rectilinear) listOf("summary" to "Resumo","plan" to "Metas","periods" to "Períodos") else listOf("summary" to "Resumo","periods" to "Períodos")
        tabDefs.forEach { (key,name) ->
            val active=statisticsTab==key
            val cell=ui.column().apply {
                tag="statistics-subtab:$key";isSelected=active;isFocusable=true;contentDescription="Estatísticas: $name"
                setOnClickListener {if(statisticsTab!=key){statisticsTab=key;changed()}}
            }
            subtabs.addView(cell,LinearLayout.LayoutParams(0,-2,1f))
            label(cell,name,12,if(active)mint else ui.muted).apply {gravity=Gravity.CENTER;minHeight=ui.dp(40);maxLines=1}
            cell.addView(View(activity).apply {setBackgroundColor(if(active)mint else if(ui.rectilinear)ui.lineColor else 0xff25372d.toInt())},LinearLayout.LayoutParams(-1,ui.dp(if(active)2 else 1)))
        }
        if(statisticsTab=="plan" && ui.rectilinear){
            val panel=annualPlanPanel ?: AnnualPlanPanel(
                activity = activity,
                ui = ui,
                repo = repository,
                catalogProvider = { planCatalogProvider() },
                onOpenBook = onOpenPlannedBook,
                onAddCatalogBook = onAddCatalogToShelf,
                onPickBookFromShelf = onRequestShelfBookForPlan,
            ) { if(!destroyed) changed() }.also { annualPlanPanel = it }
            panel.render(parent)
            return
        }
        val filters=row(parent).apply {setPadding(ui.dp(20),ui.dp(6),ui.dp(20),0)}
        linkedMapOf("all" to "Todos","book" to "Livros","manga" to "Mangás","comic" to "Quadrinhos").forEach { (key,name)->
            val active=statisticsType==key
            val tab=ui.column().apply {
                tag="statistics-filter:$key";isSelected=active;isFocusable=true;contentDescription="Estatísticas: $name"
                setOnClickListener {statisticsType=key;settings.set("STATISTICS_TYPE",key);changed()}
            }
            filters.addView(tab,LinearLayout.LayoutParams(0,-2,1f))
            label(tab,name,11,if(active)mint else ui.muted).apply {gravity=Gravity.CENTER;minHeight=ui.dp(36);maxLines=1}
            tab.addView(View(activity).apply {setBackgroundColor(if(active)mint else if(ui.rectilinear)ui.lineColor else 0xff25372d.toInt())},LinearLayout.LayoutParams(-1,ui.dp(if(active)2 else 1)))
        }
        val books=repository.books().filter {statisticsType=="all" || it.kind==statisticsType}
        val byId=books.associateBy {it.id}
        val sessions=repository.sessions().filter {it.bookId in byId}
        if(statisticsTab=="periods"){periodStatistics(parent,books,sessions,mint);return}

        val knownSessions=sessions.filter {byId[it.bookId]?.pagesKnown==true}
        val totalDuration=sessions.sumOf {it.durationMs}
        val timedDuration=knownSessions.sumOf {it.durationMs}
        val timedPages=knownSessions.sumOf {it.pages.toLong()}
        val pages=books.filter {it.pagesKnown}.sumOf {it.currentPage.toLong()}
        val done=books.count {it.complete};val reading=books.count {it.currentPage>0 && !it.complete};val unread=books.size-done-reading
        val pace=card("Seu ritmo","books")
        heading(pace,format.format(pages),76).apply {setTextColor(mint);tag="statistics-pages";letterSpacing=-.06f}
        label(pace,"páginas lidas",11,ui.muted)
        divider(pace)
        val rhythm=row(pace)
        val time=ui.column();rhythm.addView(time,LinearLayout.LayoutParams(0,-2,1f))
        heading(time,compactReadingTime(totalDuration),23);label(time,"tempo de leitura",11,ui.muted)
        val rate=ui.column();rhythm.addView(rate,LinearLayout.LayoutParams(0,-2,1f))
        heading(rate,if(timedDuration>0 && timedPages>0) "${format.format(kotlin.math.round(timedPages*3600000.0/timedDuration).toLong())} pág/h" else "—",23)
        label(rate,"ritmo médio",11,ui.muted)
        val shelf=card("Sua estante","book-spines")
        val totals=row(shelf).apply {setPadding(0,ui.dp(12),0,0)}
        listOf(books.size to when(statisticsType){"book"->"livros";"all"->"exemplares";else->"volumes"},done to "concluídos",books.map {ShelfFilters.key(it.author)}.filter {it.isNotBlank()}.distinct().size to "autores").forEachIndexed { index,(value,name)->
            val column=ui.column().apply {gravity=Gravity.START};totals.addView(column,LinearLayout.LayoutParams(0,-2,1f))
            heading(column,format.format(value),if(index==0)38 else 30).apply {if(index==0)setTextColor(mint)}
            label(column,name,10,ui.muted)
        }
        val progress=card("Progresso da coleção","pie")
        val bar=FrameLayout(activity)
        progress.addView(bar,LinearLayout.LayoutParams(-1,ui.dp(23)).apply {topMargin=ui.dp(10);bottomMargin=ui.dp(12)})
        val states=row(progress);progress.removeView(states);bar.addView(states,FrameLayout.LayoutParams(-1,-1))
        val colors=listOf(mint,amber,neutral)
        listOf(done,reading,unread).forEachIndexed {index,count->if(count>0)states.addView(View(activity).apply {setBackgroundColor(colors[index])},LinearLayout.LayoutParams(0,-1,count.toFloat()))}
        if(books.isEmpty())states.setBackgroundColor(neutral)
        bar.addView(object:View(activity){
            val paint=android.graphics.Paint().apply {color=if(ui.rectilinear)(ui.background and 0x00ffffff) or 0x66000000 else 0x660d1713;strokeWidth=ui.dp(1).toFloat()}
            override fun onDraw(canvas:android.graphics.Canvas){var x=ui.dp(6).toFloat();while(x<width){canvas.drawLine(x,0f,x,height.toFloat(),paint);x+=ui.dp(6)}}
        },FrameLayout.LayoutParams(-1,-1))
        listOf("concluídos" to done,"em andamento" to reading,"não iniciados" to unread).forEachIndexed {index,(name,count)->
            val legend=row(progress).apply {minimumHeight=ui.dp(31)}
            label(legend,"—",12,colors[index]).layoutParams=LinearLayout.LayoutParams(ui.dp(20),-2)
            label(legend,name,11,ui.muted).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            label(legend,format.format(count),12).apply {layoutParams=LinearLayout.LayoutParams(-2,-2);gravity=Gravity.END}
        }
        divider(progress)
        fun distribution(title:String,icon:String,entries:List<BookClassificationStats.Count>){
            val section=ui.column().apply {setPadding(ui.dp(20),0,ui.dp(20),0)};parent.addView(section)
            val toggle=row(section).apply {minimumHeight=ui.dp(52);tag="statistics-toggle:$icon";isFocusable=true;contentDescription=title;setOnClickListener {if(!statisticsExpanded.add(icon))statisticsExpanded.remove(icon);changed()}}
            heading(toggle,title,23).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            toggle.addView(ImageView(activity).apply {setImageDrawable(DisplayIcon(if(icon in statisticsExpanded)"down" else "chevron",mint))},LinearLayout.LayoutParams(ui.dp(16),ui.dp(16)))
            val target=ui.column().apply {tag="statistics-distribution:$icon";visibility=if(icon in statisticsExpanded)View.VISIBLE else View.GONE};section.addView(target)
            divider(section)
            fun add(items:List<BookClassificationStats.Count>){items.forEach {entry->
                val line=row(target).apply {setPadding(0,ui.dp(8),0,ui.dp(4))}
                label(line,entry.name,12).apply {
                    tag="statistics-classification-name";maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
                    layoutParams=LinearLayout.LayoutParams(0,-2,1.2f)
                }
                ui.progress(line,entry.books*100/(entries.firstOrNull()?.books ?: 1).coerceAtLeast(1)).apply {
                    progressTintList=android.content.res.ColorStateList.valueOf(mint)
                    contentDescription="${entry.name}: ${entry.books} exemplares"
                    layoutParams=LinearLayout.LayoutParams(0,ui.dp(8),1.5f).apply {marginStart=ui.dp(8);marginEnd=ui.dp(12)}
                }
                label(line,format.format(entry.books),12).apply {
                    tag="statistics-classification-count";gravity=Gravity.END;maxLines=1
                    layoutParams=LinearLayout.LayoutParams(-2,-2)
                }
            }}
            add(entries.take(3))
            if(entries.isEmpty())label(target,"Ainda sem dados neste filtro.",12,ui.muted)
            if(entries.size>3)ui.action(target,"Ver todos"){add(entries.drop(3));target.findViewWithTag<View>("statistics-more")?.visibility=View.GONE}.apply {tag="statistics-more";setTextColor(mint);background=null;gravity=Gravity.START}
        }
        distribution("Gêneros","stats",BookClassificationStats.genres(books))
        distribution("Subgêneros","classification-tag",BookClassificationStats.subgenres(books))

        if(ui.rectilinear){
            // 1) Dias lidos (calendário mensal navegável por setas ‹ › + sequência + lembrete)
            val hmSection=ui.column().apply {tag="statistics-heatmap";setPadding(ui.dp(20),ui.dp(10),ui.dp(20),ui.dp(8))}
            parent.addView(hmSection,LinearLayout.LayoutParams(-1,-2))
            val hmBox=ui.column().apply {
                setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12))
                background=ui.outlined(ui.surface,ui.lineColor,0)
            }
            hmSection.addView(hmBox,LinearLayout.LayoutParams(-1,-2))

            val hmTop=row(hmBox).apply {gravity=Gravity.CENTER_VERTICAL}
            (hmTop.layoutParams as LinearLayout.LayoutParams).bottomMargin=ui.dp(10)
            val hmTitleCol=ui.column()
            hmTop.addView(hmTitleCol,LinearLayout.LayoutParams(0,-2,1f))
            ui.label(hmTitleCol,"DIAS LIDOS",9,ui.muted).apply {
                letterSpacing=0.08f;(layoutParams as LinearLayout.LayoutParams).bottomMargin=ui.dp(1)
            }
            heading(hmTitleCol,"${AnnualPlanCalculator.fullName(heatmapMonth)} $heatmapYear",18).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin=0
            }
            val prevMonthBtn=TextView(activity).apply {
                text="‹";textSize=17f;gravity=Gravity.CENTER;setTextColor(mint)
                contentDescription="Mês anterior";isFocusable=true
                background=ui.outlined(ui.background,ui.lineColor,0)
                setOnClickListener {
                    val (ny,nm)=ReadingHeatmap.shiftMonth(heatmapYear,heatmapMonth,-1)
                    heatmapYear=ny;heatmapMonth=nm;heatmapSelectedDay=null;changed()
                }
            }
            val nextMonthBtn=TextView(activity).apply {
                text="›";textSize=17f;gravity=Gravity.CENTER;setTextColor(mint)
                contentDescription="Próximo mês";isFocusable=true
                background=ui.outlined(ui.background,ui.lineColor,0)
                setOnClickListener {
                    val (ny,nm)=ReadingHeatmap.shiftMonth(heatmapYear,heatmapMonth,1)
                    heatmapYear=ny;heatmapMonth=nm;heatmapSelectedDay=null;changed()
                }
            }
            hmTop.addView(prevMonthBtn,LinearLayout.LayoutParams(ui.dp(30),ui.dp(30)).apply {marginEnd=ui.dp(4)})
            hmTop.addView(nextMonthBtn,LinearLayout.LayoutParams(ui.dp(30),ui.dp(30)))

            val remOn=br.com.viblio.reminder.ReadingReminder.isEnabled(activity)
            TextView(activity).apply {
                tag="reading-reminder-btn"
                text=if(remOn) "Lembrete ${br.com.viblio.reminder.ReadingReminder.label(activity)}" else "Lembrete"
                textSize=10f
                setTextColor(if(remOn) mint else ui.muted)
                background=ui.outlined(if(remOn) ui.activeSurface else ui.surface, if(remOn) mint else ui.lineColor, 0)
                setPadding(ui.dp(7),ui.dp(5),ui.dp(7),ui.dp(5))
                setOnClickListener { openReadingReminderDialog() }
            }.also {hmTop.addView(it,LinearLayout.LayoutParams(-2,-2).apply {marginStart=ui.dp(6)})}

            val todayDay=ReadingDays.at(System.currentTimeMillis())
            val hmSummary=ReadingHeatmap.forYear(heatmapYear,books,sessions,repository.dates(),todayDay=todayDay)
            val hmMonthGrid=ReadingHeatmap.monthGrid(heatmapYear,heatmapMonth,hmSummary)
            val isCurrentYear=heatmapYear==java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val streakRow=row(hmBox)
            (streakRow.layoutParams as LinearLayout.LayoutParams).bottomMargin=ui.dp(10)
            val metrics=if(isCurrentYear) listOf(
                format.format(hmMonthGrid.activeDaysInMonth) to "dias no mês",
                format.format(hmSummary.currentStreak) to "sequência atual",
                format.format(hmSummary.bestStreak) to "melhor em $heatmapYear",
            ) else listOf(
                format.format(hmMonthGrid.activeDaysInMonth) to "dias no mês",
                format.format(hmSummary.totalActiveDays) to "dias em $heatmapYear",
                format.format(hmSummary.bestStreak) to "melhor em $heatmapYear",
            )
            metrics.forEachIndexed { i,(v,cap)->
                val cCol=ui.column();streakRow.addView(cCol,LinearLayout.LayoutParams(0,-2,1f))
                heading(cCol,v,22).apply {if(i==0)setTextColor(mint);(layoutParams as LinearLayout.LayoutParams).bottomMargin=0}
                label(cCol,cap,10,ui.muted).apply {(layoutParams as LinearLayout.LayoutParams).bottomMargin=0}
            }

            val dayDetailLabel=TextView(activity).apply {
                textSize=10.5f
                setTextColor(ui.muted)
                maxLines=2
                ellipsize=android.text.TextUtils.TruncateAt.END
            }
            fun describeDay(day:String?):String {
                if(day==null) return "Toque em um dia para ver detalhes"
                val act=hmSummary.daysByDate[day]
                val pretty=runCatching {ReadingDays.label(day)}.getOrDefault(day)
                if(act==null) return "$pretty · Sem leitura registrada"
                val parts=mutableListOf(pretty)
                if(act.pages>0) parts.add("${format.format(act.pages)} págs.")
                if(act.durationMs>0L) parts.add(compactReadingTime(act.durationMs))
                if(act.bookTitles.isNotEmpty()) parts.add(act.bookTitles.take(2).joinToString(", "))
                return parts.joinToString(" · ")
            }
            dayDetailLabel.text=describeDay(heatmapSelectedDay)

            val hmView=ReadingHeatmapView(activity,ui,hmSummary,hmMonthGrid,mint,todayDay,heatmapSelectedDay){ pickedDay->
                heatmapSelectedDay=pickedDay
                dayDetailLabel.text=describeDay(pickedDay)
            }
            hmBox.addView(hmView,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(10)})

            hmBox.addView(View(activity).apply {setBackgroundColor(ui.lineColor)},LinearLayout.LayoutParams(-1,ui.dp(1)).apply {bottomMargin=ui.dp(8)})
            val footRow=row(hmBox).apply {gravity=Gravity.CENTER_VERTICAL}
            footRow.addView(dayDetailLabel,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(8)})
            val legendRow=ui.row().apply {gravity=Gravity.CENTER_VERTICAL}
            footRow.addView(legendRow,LinearLayout.LayoutParams(-2,-2))
            ui.label(legendRow,"-",9,ui.muted).apply {layoutParams=LinearLayout.LayoutParams(-2,-2).apply {marginEnd=ui.dp(3)}}
            listOf(
                0xFF1E1D1B.toInt(),
                Color.argb(82,Color.red(mint),Color.green(mint),Color.blue(mint)),
                Color.argb(165,Color.red(mint),Color.green(mint),Color.blue(mint)),
                mint,
            ).forEach { col->
                legendRow.addView(View(activity).apply {setBackgroundColor(col)},LinearLayout.LayoutParams(ui.dp(9),ui.dp(9)).apply {marginEnd=ui.dp(2)})
            }
            ui.label(legendRow,"+",9,ui.muted).apply {layoutParams=LinearLayout.LayoutParams(-2,-2).apply {marginStart=ui.dp(2)}}

            // 2) Retrospectiva por último em Resumo (abaixo de Dias lidos)
            val retroWrap=ui.column().apply {setPadding(ui.dp(20),ui.dp(4),ui.dp(20),ui.dp(16))}
            parent.addView(retroWrap,LinearLayout.LayoutParams(-1,-2))
            val retroCard=row(retroWrap).apply {
                tag="statistics-retrospective-card"
                gravity=Gravity.CENTER_VERTICAL
                setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10))
                background=ui.outlined(ui.surface,ui.lineColor,0)
                isFocusable=true
                setOnClickListener {
                    val dlg=retrospectiveDialog ?: RetrospectiveDialog(activity,ui,repository).also {retrospectiveDialog=it}
                    dlg.showYear(heatmapYear)
                }
            }
            val retroLeft=ui.column()
            retroCard.addView(retroLeft,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(10)})
            val retroHead=row(retroLeft).apply {gravity=Gravity.CENTER_VERTICAL}
            (retroHead.layoutParams as LinearLayout.LayoutParams).bottomMargin=ui.dp(2)
            retroHead.addView(ImageView(activity).apply {
                setImageDrawable(br.com.viblio.ui.ViblioBookMarkDrawable(mint,Color.WHITE))
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(ui.dp(14),ui.dp(14)).apply {marginEnd=ui.dp(5)})
            ui.label(retroHead,"RETROSPECTIVA $heatmapYear",10,mint,true).apply {
                letterSpacing=0.08f
                (layoutParams as LinearLayout.LayoutParams).bottomMargin=0
            }
            ui.label(retroLeft,"Gerar cartão visual para salvar ou compartilhar",11,ui.muted).apply {
                maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                (layoutParams as LinearLayout.LayoutParams).bottomMargin=0
            }
            TextView(activity).apply {
                text="Abrir ›"
                textSize=11f
                setTextColor(ui.background)
                typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                background=ui.shape(mint,0)
                setPadding(ui.dp(10),ui.dp(6),ui.dp(10),ui.dp(6))
            }.also {retroCard.addView(it,LinearLayout.LayoutParams(-2,-2))}
        }
    }
    private var pendingReminderPicker=false
    fun onReminderPermissionResult(granted:Boolean) {
        if(!pendingReminderPicker || destroyed || activity.isFinishing || activity.isDestroyed) return
        pendingReminderPicker=false
        if(!granted) {
            toast("Ative as notificações nas configurações do aparelho para receber o lembrete.")
            return
        }
        showReminderTimePicker()
    }
    private fun showReminderTimePicker() {
        val curH=br.com.viblio.reminder.ReadingReminder.hour(activity)
        val curM=br.com.viblio.reminder.ReadingReminder.minute(activity)
        android.app.TimePickerDialog(activity,{ _,h,m->
            br.com.viblio.reminder.ReadingReminder.enable(activity,h,m)
            toast("Lembrete diário ativado para ${br.com.viblio.reminder.ReadingReminder.label(activity)}.")
            changed()
        },curH,curM,true).show()
    }
    private fun openReadingReminderDialog(){
        val enabled=br.com.viblio.reminder.ReadingReminder.isEnabled(activity)
        fun pickTime(){
            if(android.os.Build.VERSION.SDK_INT>=33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
                pendingReminderPicker=true
                activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),935)
                return
            }
            showReminderTimePicker()
        }
        val msg=if(enabled) "Ativo às ${br.com.viblio.reminder.ReadingReminder.label(activity)}. Só avisa nos dias em que você ainda não leu." else "Receba um aviso diário discreto apenas nos dias em que ainda não registrou leitura."
        val b=AlertDialog.Builder(activity)
            .setTitle("Lembrete diário de leitura")
            .setMessage(msg)
            .setPositiveButton(if(enabled)"Mudar horário" else "Escolher horário"){_,_->pickTime()}
            .setNegativeButton("Fechar",null)
        if(enabled) b.setNeutralButton("Desativar"){_,_->
            br.com.viblio.reminder.ReadingReminder.disable(activity)
            toast("Lembrete desativado.")
            changed()
        }
        b.show()
    }
    private fun compactReadingTime(ms:Long):String {
        val seconds=ms.coerceAtLeast(0)/1000;val hours=seconds/3600;val minutes=seconds/60%60
        return when {hours>0->if(minutes==0L)"${hours}h" else "${hours}h ${minutes.toString().padStart(2,'0')}min";minutes>0->"${minutes} min";else->"${seconds} s"}
    }
    private val collapsedReadingDays=mutableSetOf<String>()
    private val visibleDaySessions=mutableMapOf<String,Int>()
    private fun sessionDay(parent:LinearLayout,key:String,day:String,sessions:List<ReadingSession>,manual:Boolean,books:Map<String,LibraryBook>,showVolume:Boolean=false,edit:()->Unit){
        parent.addView(View(activity).apply {setBackgroundColor(0x33ffffff)},LinearLayout.LayoutParams(-1,ui.dp(1)).apply {topMargin=ui.dp(8)})
        val card=ui.column().apply {tag="reading-history-day";setPadding(0,ui.dp(2),0,0)};parent.addView(card)
        val toggle=row(card)
        val names=ui.column();toggle.addView(names,LinearLayout.LayoutParams(0,-2,1f))
        fun compactLabel(target:LinearLayout,text:String,size:Int,color:Int=ui.text,bold:Boolean=false)=ui.label(target,text,size,color,bold).apply {(layoutParams as LinearLayout.LayoutParams).bottomMargin=0}
        compactLabel(names,ReadingDays.label(day),13,bold=true)
        val pageCount=sessions.filter {books[it.bookId]?.pagesKnown==true}.sumOf {it.pages.toLong()}
        if(sessions.isNotEmpty())compactLabel(names,"${if(sessions.any {books[it.bookId]?.pagesKnown==true}) "$pageCount ${if(pageCount==1L)"página" else "páginas"}" else "Páginas não informadas"} · ${compactReadingTime(sessions.sumOf {it.durationMs})}",11,ui.muted)
        ui.icon(toggle,"edit","Editar leituras de ${ReadingDays.label(day)}",44){edit()}
        toggle.addView(ImageView(activity).apply {setImageDrawable(DisplayIcon("down",ui.muted));rotation=if(key in collapsedReadingDays)0f else 180f;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(ui.dp(20),ui.dp(20)))
        toggle.isFocusable=true;toggle.contentDescription="Leituras de ${ReadingDays.label(day)}"
        toggle.setOnClickListener {if(!collapsedReadingDays.add(key))collapsedReadingDays.remove(key);changed()}
        if(key in collapsedReadingDays)return
        val timeFormat=SimpleDateFormat("HH:mm",Locale("pt","BR"));val limit=visibleDaySessions[key] ?: 20
        sessions.sortedBy {it.startedAt}.take(limit).forEach {session->
            val entry=ui.column().apply {tag="reading-history-session";setPadding(ui.dp(4),ui.dp(6),ui.dp(4),ui.dp(4))};card.addView(entry)
            entry.isFocusable=true;entry.contentDescription="Editar leitura · ${session.pages} páginas";entry.setOnClickListener {datesEditor.editSession(session)}
            val line=row(entry)
            compactLabel(line,"${timeFormat.format(Date(session.startedAt))}–${timeFormat.format(Date(session.finishedAt))}",12,bold=true).apply {tag="session-clock";layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
            compactLabel(line,compactReadingTime(session.durationMs),12,ui.muted).apply {tag="session-duration";gravity=Gravity.END;layoutParams=LinearLayout.LayoutParams(-2,-2)}
            val book=books[session.bookId]
            if(showVolume)compactLabel(entry,book?.sequenceNumber?.let {"Volume $it"} ?: book?.title.orEmpty(),11,ui.muted)
            compactLabel(entry,if(book?.pagesKnown==true)"Páginas ${session.startPage} → ${session.endPage} · ${session.pages} ${if(session.pages==1)"página" else "páginas"}" else "Páginas não informadas",11,ui.muted).apply {includeFontPadding=true}
        }
        if(sessions.size>limit)ui.action(card,"Ver mais sessões"){visibleDaySessions[key]=limit+20;changed()}
        if(manual)compactLabel(card,"Data informada por você",11,ui.muted)
    }
    private fun statistics(parent: LinearLayout) {
        if(useRedesignedStatistics){redesignedStatistics(parent);return}
        fun statsLabel(container: LinearLayout, value: String, size: Int = 16, color: Int = ui.text, bold: Boolean = false) =
            ui.label(container, value, size, color, bold).apply { includeFontPadding = true }
        fun frame(): FrameLayout = FrameLayout(activity).apply {
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            background = android.graphics.drawable.InsetDrawable(ui.shape(0xb31b1b1b.toInt(), 12), ui.dp(3))
            foreground = android.graphics.drawable.InsetDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT); cornerRadius = ui.radius(12); setStroke(ui.dp(1).coerceAtLeast(1), 0xff696969.toInt())
            }, ui.dp(3))
            // Draw the inset outline after the children, independently of text measurement.
            foregroundGravity = Gravity.FILL
        }
        val header = row(parent)
        ui.icon(header, "back", "Voltar à estante", 36) { onStatisticsBack?.invoke() ?: show("library") }
        statsLabel(header, "Estatísticas", 32, bold = true).apply {
            typeface = Typeface.create("serif", Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val scopes = linkedMapOf("all" to "Geral", "book" to "Livros", "manga" to "Mangás", "comic" to "Quadrinhos")
        ui.icon(header, "more", "Filtrar estatísticas", 40) {
            val kinds = scopes.keys.toList()
            AlertDialog.Builder(activity).setTitle("Estatísticas de").setSingleChoiceItems(scopes.values.toTypedArray(), kinds.indexOf(statisticsType)) { dialog, index ->
                statisticsType = kinds[index]; settings.set("STATISTICS_TYPE", statisticsType); dialog.dismiss(); changed()
            }.setNegativeButton("Cancelar", null).show()
        }
        statsLabel(parent, "${scopes[statisticsType]} · Sua leitura em números", 12, ui.muted).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(12) }
        val books = repository.books().filter { statisticsType == "all" || it.kind == statisticsType }; val sessions = repository.sessions().filter { record -> books.any { it.id == record.bookId } }; val summary = ReadingSummary.from(sessions)
        if (books.any { !it.pagesKnown }) statsLabel(parent, "${books.count { !it.pagesKnown }} volume(s) sem páginas informadas; não entram no total de páginas.", 11, ui.muted)
        val done = books.count { it.complete }; val reading = books.count { it.currentPage > 0 && !it.complete }; val unread = books.size - done - reading
        val pages = books.sumOf { it.currentPage.toLong() }; val total = books.filter { it.pagesKnown }.sumOf { it.totalPages.toLong() }
        val format = java.text.NumberFormat.getIntegerInstance(Locale("pt", "BR"))
        data class Metric(val name: String, val value: String, val icon: String, val color: Int = ui.text)
        val metrics = listOf(Metric(when (statisticsType) { "book" -> "Livros"; "all" -> "Exemplares"; else -> "Volumes" }, format.format(books.size), "books"), Metric("Concluídos", format.format(done), "check", 0xff6bd38a.toInt()),
            Metric("Em andamento", format.format(reading), "clock", 0xffffb44f.toInt()), Metric("Páginas lidas", format.format(pages), "pages"),
            Metric("Leitura geral", "${if (total == 0L) 0 else pages * 100 / total}%", "pie"),
            Metric("Autores", format.format(books.map { ShelfFilters.key(it.author) }.filter { it.isNotBlank() }.distinct().size), "people"))
        metrics.chunked(2).forEach { chunk ->
            val line = row(parent).apply { gravity = Gravity.TOP }
            chunk.forEachIndexed { index, metric ->
                val card = ui.row().apply { setPadding(ui.dp(10), ui.dp(10), ui.dp(8), ui.dp(10)); minimumHeight = ui.dp(80) }
                val shell = frame(); shell.addView(card, FrameLayout.LayoutParams(-1, -2))
                line.addView(shell, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(if (index == 0) 6 else 0); bottomMargin = ui.dp(6) })
                card.addView(ImageView(activity).apply { setImageDrawable(DisplayIcon(metric.icon, metric.color)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                    LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(8) })
                val info = ui.column(); card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                statsLabel(info, metric.value, 24, bold = true).apply { typeface = Typeface.create("serif", Typeface.BOLD); maxLines = 2 }
                statsLabel(info, metric.name, 10, ui.muted).apply { maxLines = 2 }
            }
        }
        fun panel(title: String): LinearLayout {
            val shell = frame(); parent.addView(shell, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
            return ui.column().apply {
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); shell.addView(this, FrameLayout.LayoutParams(-1, -2))
                statsLabel(this, title, 22, bold = true).typeface = Typeface.create("serif", Typeface.BOLD)
            }
        }
        val states = panel(if (statisticsType == "book") "Situação dos livros" else "Situação dos exemplares")
        val stacked = ui.row().apply { background = ui.shape(0xff555555.toInt(), 5); clipToOutline = true }
        states.addView(stacked, LinearLayout.LayoutParams(-1, ui.dp(16)).apply { topMargin = ui.dp(6); bottomMargin = ui.dp(8) })
        val colors = listOf(0xff6bd38a.toInt(), 0xffffb44f.toInt(), 0xff737373.toInt())
        listOf(done, reading, unread).forEachIndexed { index, count -> if (count > 0) stacked.addView(View(activity).apply { setBackgroundColor(colors[index]) }, LinearLayout.LayoutParams(0, -1, count.toFloat())) }
        val legends = row(states)
        listOf("Concluídos" to done, "Em andamento" to reading, "Não iniciados" to unread).forEachIndexed { index, pair ->
            val label = statsLabel(legends, "● ${pair.first} · ${pair.second}", 9, colors[index]).apply { maxLines = 2 }
            label.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val pace = panel("Seu ritmo")
        val paceLine = row(pace); detailIcon(paceLine, "clock")
        val paceInfo = ui.column(); paceLine.addView(paceInfo, LinearLayout.LayoutParams(0, -2, 1f))
        statsLabel(paceInfo, readingPace(summary.millisPerPage), 23, bold = true).typeface = Typeface.create("serif", Typeface.BOLD)
        statsLabel(paceInfo, "${readingTime(summary.durationMs)} · ${summary.timedPages} páginas cronometradas", 10, ui.muted)
        statsLabel(paceInfo, "${sessions.size} registros · somente sessões salvas", 10, ui.muted)
        fun distribution(title: String, entries: List<BookClassificationStats.Count>, empty: String) {
            val container = panel(title)
            val largest = entries.firstOrNull()?.books ?: 1
            fun addRows(items: List<BookClassificationStats.Count>) {
                items.forEach { item ->
                    val line = row(container)
                    statsLabel(line, item.name, 11).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
                    ui.progress(line, item.books * 100 / largest).apply {
                        progressTintList = android.content.res.ColorStateList.valueOf(0xffffb44f.toInt())
                        layoutParams = LinearLayout.LayoutParams(0, ui.dp(6), 1f).apply { marginStart = ui.dp(8); marginEnd = ui.dp(8) }
                        contentDescription = "${item.name}: ${item.books} exemplares"
                    }
                    statsLabel(line, item.books.toString(), 11).apply { layoutParams = LinearLayout.LayoutParams(-2, -2); gravity = Gravity.END }
                    line.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) }
                }
            }
            addRows(entries.take(5))
            if (entries.size > 5) ui.action(container, "Ver todos") {
                addRows(entries.drop(5)); container.findViewWithTag<View>("more-distribution")?.visibility = View.GONE
            }.apply { tag = "more-distribution"; minHeight = ui.dp(36) }
            if (entries.isEmpty()) statsLabel(container, empty, 12, ui.muted)
        }
        distribution("Gêneros mais presentes", BookClassificationStats.genres(books), "Adicione livros para acompanhar sua biblioteca.")
        distribution("Subgêneros mais presentes", BookClassificationStats.subgenres(books), "Adicione subgêneros aos livros para acompanhar sua distribuição.")
    }
    fun backupSettings() = backupMenu()
    private fun backupMenu() {
        if(!jsonAllowed()){catalog?.configure();return}
        AlertDialog.Builder(activity).setTitle("Biblioteca e backup").setItems((listOf("Importar JSON", "Exportar JSON completo")+if(catalog!=null) listOf("Catálogo do servidor") else emptyList()).toTypedArray()) { _, index ->
            if (index == 0) {
                AlertDialog.Builder(activity).setTitle("Importar biblioteca?").setMessage("Livros com o mesmo ID serão atualizados; os demais serão adicionados. Nada será apagado. A leitura aberta será pausada; seu tempo e progresso serão preservados. Exporte um backup antes. O formato antigo precisa conter campos reconhecidos; arquivos diferentes serão recusados com uma mensagem.")
                    .setPositiveButton("Escolher JSON") { _, _ -> repository.pause(); changed(); launchDocument(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, IMPORT) }.setNegativeButton("Cancelar", null).show()
            } else if(index==2) catalog?.configure() else launchDocument(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "central-biblioteca.json") }, EXPORT)
        }.show()
    }
    private fun launchDocument(intent: Intent, code: Int) { try { if (code == WEB) activity.startActivity(intent) else activity.startActivityForResult(intent, code) } catch (_: ActivityNotFoundException) { toast("Nenhum aplicativo disponível para abrir esta opção.") } }
    fun onActivityResult(request: Int, result: Int, data: Intent?): Boolean {
        if (request == COVER_PHOTO || request == SPINE_PHOTO) { acceptPhoto(request, result, data); return true }
        if (request !in setOf(COVER, IMPORT, EXPORT, COLLECTION_COVER)) return false
        if(request in setOf(IMPORT,EXPORT) && !jsonAllowed()){toast("Arquivos JSON estão disponíveis apenas para administradores.");return true}
        val uri = data?.data
        if (result != Activity.RESULT_OK || uri == null) return true
        captureEditor(); captureCollectionEditor(); busy = true; changed()
        val books = if (request == EXPORT) repository.books(usePersonalArchive) else emptyList()
        val sessions = if (request == EXPORT) repository.sessions() else emptyList()
        val dates = if (request == EXPORT) repository.dates() else emptyList()
        val currentId = repository.currentId()
        worker.execute {
            try {
                check(request !in setOf(IMPORT,EXPORT) || jsonAllowed()) {"Acesso a JSON não autorizado."}
                when (request) {
                    COLLECTION_COVER -> {
                        val editing = collectionEditor != null && screen == "collection_editor"
                        val id = if (editing) null else requireNotNull(selectedCollection)
                        val cover = images.save(requireNotNull(activity.contentResolver.openInputStream(uri)))
                        if (editing) post { collectionEditor = collectionEditor?.copy(cover = cover); collectionFields.clear(); toast("Capa adicionada. Salve o título para confirmar.") }
                        else {
                            val c = requireNotNull(repository.collections().firstOrNull { it.id == id })
                            repository.saveTitleCover(c.copy(cover = cover));repository.books().firstOrNull { it.collectionId==c.id && it.sequenceNumber==1 }?.let { catalog?.saved(it) }; post { toast("Capa do volume 1 salva") }
                        }
                    }
                    COVER -> {
                        val cover = images.save(requireNotNull(activity.contentResolver.openInputStream(uri)))
                        post { editor = editor?.copy(cover = cover); fields.clear(); toast("Capa adicionada. Salve o livro para confirmar.") }
                    }
                    IMPORT -> {
                        val snapshot = json.read(requireNotNull(activity.contentResolver.openInputStream(uri)))
                        val n = repository.importSnapshot(snapshot.books, snapshot.sessions, snapshot.currentId, snapshot.dates, snapshot.collections)
                        post { selected = null; selectedCollection = null; screen = "library"; toast("$n itens e ${snapshot.collections.size} títulos importados.") }
                    }
                    EXPORT -> {
                        val output = json.write(if(usePersonalArchive)repository.books(true) else books, sessions, currentId, dates, repository.collections(usePersonalArchive))
                        requireNotNull(activity.contentResolver.openOutputStream(uri, "wt")).use { it.write(output.toByteArray(Charsets.UTF_8)) }
                        post { toast("Backup salvo com livros, capas, sessões e datas de leitura. Uma sessão ativa só é exportada depois de finalizar.") }
                    }
                }
            } catch (e: Exception) { post { toast(e.message ?: "Não foi possível processar o arquivo.") } }
        }
        return true
    }
    /** Applies a cropped cover/spine photo only to the same profile and the same, still present book. */
    private fun acceptPhoto(request: Int, result: Int, data: Intent?) {
        if (result != Activity.RESULT_OK || data == null) return
        val name = data.getStringExtra(br.com.viblio.camera.BookPhotoCropActivity.EXTRA_SAVED_IMAGE).orEmpty()
        val id = data.getStringExtra(br.com.viblio.camera.BookPhotoCropActivity.EXTRA_BOOK_ID).orEmpty()
        val profile = data.getStringExtra(br.com.viblio.camera.BookPhotoCropActivity.EXTRA_PROFILE)
        if (!name.matches(Regex("[a-f0-9]{64}\\.jpg")) || id.isBlank() || profile != LibraryProfiles.database(activity) || images.file(name) == null) { toast("A foto não pôde ser aplicada. Tente novamente."); return }
        if (request == COVER_PHOTO && screen == "editor" && editor?.id == id) {
            attempt { captureEditor(); editor = editor?.copy(cover = name); fields.clear(); toast("Capa adicionada. Salve o livro para confirmar."); changed() }
            return
        }
        val latest = repository.book(id)?.takeUnless { it.archived } ?: run { toast("O livro mudou. Tire a foto novamente."); return }
        attempt {
            if (request == COVER_PHOTO) {
                val updated = latest.copy(cover = name, updatedAt = System.currentTimeMillis())
                repository.save(updated); repository.book(id)?.let { catalog?.saved(it) }; toast("Capa atualizada")
            } else {
                repository.save(latest.copy(spineImage = name, updatedAt = System.currentTimeMillis()))
                if (screen == "editor" && editor?.id == id) editor = editor?.copy(spineImage = name)
                toast("Foto da lombada salva")
            }
            changed()
        }
    }
    private fun post(action: () -> Unit) {
        handler.post {
            if (!destroyed) {
                try { action() } catch (e: Exception) { toast(e.message ?: "Não foi possível concluir.") }
                busy = false
                changed()
            }
        }
    }
    companion object { private const val COLLECTION_COVER = 814; private const val COVER = 810; private const val IMPORT = 811; private const val EXPORT = 812; private const val WEB = 813; private const val COVER_PHOTO = 815; private const val SPINE_PHOTO = 816 }
}
