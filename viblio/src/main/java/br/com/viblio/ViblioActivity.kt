package br.com.viblio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.Gravity
import android.widget.ImageView
import android.widget.ProgressBar
import br.com.viblio.ui.SearchPanel
import br.com.viblio.ui.AccountPanel
import br.com.centralcasa.R
import br.com.centralcasa.ui.BooksPanel
import br.com.centralcasa.ui.DisplayUi
import br.com.centralcasa.ui.ReadingScrollView

/** Standalone collection host: no home services, cameras or media integrations. */
class ViblioActivity : Activity() {
    private lateinit var accounts:br.com.viblio.account.AccountManager
    private lateinit var homeAssistant:br.com.viblio.homeassistant.HomeAssistantBridge
    private lateinit var catalog:br.com.viblio.catalog.ViblioCatalog
    private lateinit var library: BooksPanel
    private lateinit var ui: DisplayUi
    private lateinit var root: FrameLayout
    private lateinit var scroll: ReadingScrollView
    private lateinit var content: LinearLayout
    private var statistics:BooksPanel?=null
    private lateinit var navigation:LinearLayout
    private lateinit var composerDock:FrameLayout
    private lateinit var search:SearchPanel
    private lateinit var account:AccountPanel
    private lateinit var friends:br.com.viblio.ui.SocialPanel
    private var tab="shelf"
    private var offline=false
    private var scanTarget:String?=null
    private var scanProfile:String?=null
    private var scanEditor:String?=null
    private fun launchIsbnScanner(target:String){
        if(needsLogin())return
        scanTarget=target;scanProfile=br.com.centralcasa.books.LibraryProfiles.database(this);scanEditor=library.editingId
        startActivityForResult(Intent(this,br.com.viblio.scanner.BarcodeScannerActivity::class.java),920)
    }
    private fun needsLogin()= !accounts.signedIn && !offline
    private val navigationCovers=java.util.concurrent.Executors.newSingleThreadExecutor()
    private var stopped=false
    private var needsRender=false
    private var started=false
    private var themeBookKey:String?=null
    private fun cachedIntroCoverColor():Int {
        if(needsLogin())return ui.accent
        val prefs=getSharedPreferences("viblio_intro",MODE_PRIVATE)
        return if(prefs.contains("cover_color"))prefs.getInt("cover_color",ui.accent) else ui.accent
    }
    private fun saveIntroCoverColor(color:Int?) {
        val editor=getSharedPreferences("viblio_intro",MODE_PRIVATE).edit()
        if(color==null)editor.remove("cover_color") else editor.putInt("cover_color",color)
        editor.apply()
    }
    private fun updateReadingTheme(){
        if(needsLogin()){themeBookKey=null;ui.readingColor(null);saveIntroCoverColor(null);return}
        val source=library;val key=source.currentAppearanceKey()
        if(key==themeBookKey)return
        themeBookKey=key
        navigationCovers.execute {
            val color=runCatching {source.currentReadingColor()}.getOrNull()
            root.post {
                if(!isDestroyed && library===source && themeBookKey==key && !needsLogin()){
                    val changed=ui.readingColor(color)
                    saveIntroCoverColor(ui.accent)
                    introView?.updateCoverColor(ui.accent)
                    if(changed)render()
                }
            }
        }
    }
    private val positions=mutableMapOf<String,Int>()

    companion object {
        private var introPlayed = false
    }
    private var introView: br.com.viblio.ui.ViblioIntroView? = null
    private lateinit var pullBadge: FrameLayout
    private lateinit var pullIcon: ImageView
    private lateinit var pullSpinner: ProgressBar
    private var pullSpinning = false
    private val pullTimeout = Runnable { stopPullSpinner() }

    private fun stopPullSpinner() {
        pullSpinning = false
        if (::pullBadge.isInitialized) {
            pullBadge.animate().translationY(-ui.dp(48).toFloat()).alpha(0f).setDuration(160).withEndAction {
                if (!pullSpinning) pullBadge.visibility = android.view.View.GONE
            }.start()
        }
    }

    private fun updatePullTheme(theme: DisplayUi) {
        if (!::pullBadge.isInitialized) return
        (pullBadge.background as? android.graphics.drawable.GradientDrawable)?.apply {
            setColor(theme.galleryPill(242))
            setStroke(theme.dp(1).coerceAtLeast(1), theme.lineColor)
        }
        pullIcon.setImageDrawable(theme.symbol("refresh", theme.accent, 18))
        pullSpinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(theme.accent)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(android.os.Build.VERSION.SDK_INT >= 31){
            splashScreen.setOnExitAnimationListener { it.remove() }
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
        ui = DisplayUi(this).apply {readingColor(null)}
        root = FrameLayout(this)
        content = ui.column().apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(24)) }
        scroll = ReadingScrollView(this).apply {
            id = R.id.content_scroll; isFillViewport = true
            clipToPadding = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        pullIcon = ImageView(this).apply {
            setImageDrawable(ui.symbol("refresh", ui.accent, 18))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        pullSpinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            visibility = android.view.View.GONE
            contentDescription = "Atualizando"
        }
        pullBadge = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(ui.galleryPill(242))
                setStroke(ui.dp(1).coerceAtLeast(1), ui.lineColor)
            }
            elevation = ui.dp(8).toFloat()
            visibility = android.view.View.GONE
            alpha = 0f
            translationY = -ui.dp(48).toFloat()
            addView(pullIcon, FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER))
            addView(pullSpinner, FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER))
        }
        val scrollHost = FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            addView(pullBadge, FrameLayout.LayoutParams(ui.dp(38), ui.dp(38), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        scroll.canPullRefresh = {
            !pullSpinning && !needsLogin() && (tab == "friends" || (tab == "shelf" && accounts.signedIn && !library.isEditor))
        }
        scroll.onPullProgress = { progress, dragging ->
            if (!pullSpinning) {
                if (dragging && progress > 0.04f) {
                    pullBadge.animate().cancel()
                    pullBadge.visibility = android.view.View.VISIBLE
                    pullIcon.visibility = android.view.View.VISIBLE
                    pullSpinner.visibility = android.view.View.GONE
                    pullBadge.alpha = (progress * 1.4f).coerceIn(0f, 1f)
                    pullBadge.translationY = -ui.dp(32) + progress.coerceAtMost(1.3f) * ui.dp(52)
                    pullIcon.rotation = progress * 240f
                    val scale = if (progress >= 1f) 1.08f else 0.9f + progress * 0.1f
                    pullBadge.scaleX = scale; pullBadge.scaleY = scale
                } else if (!dragging) {
                    stopPullSpinner()
                }
            }
        }
        scroll.onPullRefresh = {
            pullSpinning = true
            pullBadge.animate().cancel()
            pullBadge.visibility = android.view.View.VISIBLE
            pullIcon.visibility = android.view.View.GONE
            pullSpinner.visibility = android.view.View.VISIBLE
            pullBadge.scaleX = 1f; pullBadge.scaleY = 1f
            pullBadge.animate().translationY(ui.dp(14).toFloat()).alpha(1f).setDuration(140).start()
            root.removeCallbacks(pullTimeout)
            if (tab == "friends") {
                friends.triggerRefresh()
                root.postDelayed(pullTimeout, 10000L)
            } else if (tab == "shelf" && accounts.signedIn) {
                accounts.syncSilently()
                root.postDelayed(pullTimeout, 900L)
            } else {
                stopPullSpinner()
            }
        }
        val host=ui.column()
        root.addView(host,FrameLayout.LayoutParams(-1,-1))
        host.addView(scrollHost,LinearLayout.LayoutParams(-1,0,1f))
        composerDock=FrameLayout(this).apply { visibility=android.view.View.GONE }
        host.addView(composerDock,LinearLayout.LayoutParams(-1,-2))
        navigation=ui.row().apply { setPadding(ui.dp(12),ui.dp(6),ui.dp(12),ui.dp(6));background=ui.shape(ui.background,0) }
        host.addView(navigation,LinearLayout.LayoutParams(-1,ui.dp(72)))
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.ime())
                host.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                if (insets.isVisible(android.view.WindowInsets.Type.ime()) && composerDock.visibility == android.view.View.VISIBLE) {
                    scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                }
                android.view.WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                host.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                @Suppress("DEPRECATION")
                insets.consumeSystemWindowInsets()
            }
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(root)
        accounts=br.com.viblio.account.AccountManager(this,changed={if(!accounts.signedIn)offline=false;if(needsLogin() || navigation.visibility==android.view.View.GONE || tab=="account")render()},reloaded=::reloadProfile,canReplace={::library.isInitialized && library.canReplaceLibrary && (statistics?.canReplaceLibrary ?: true) && (!::friends.isInitialized || friends.canReplaceLibrary)})
        homeAssistant=br.com.viblio.homeassistant.HomeAssistantBridge(this)
        library=makeLibrary()
        catalog=br.com.viblio.catalog.ViblioCatalog(this,changed={force->accounts.localChanged();if(force || tab=="shelf" && !library.isEditor)render()},personalToken=accounts::catalogToken,personalMode=accounts::personalMode,scanner={launchIsbnScanner("editor")})
        library.catalog=catalog
        search=makeSearch()
        friends=makeFriends()
        account=AccountPanel(this,ui,accounts,changed={accounts.localChanged();render()},shelf={switchTab("shelf")},backup={library.backupSettings()})
        offline=state?.getBoolean("viblio.offline",false) ?: false
        scanTarget=state?.getString("scan.target");scanProfile=state?.getString("scan.profile");scanEditor=state?.getString("scan.editor")
        library.restoreState(state)
        search.restore(state);friends.restore(state)
        tab=state?.getString("viblio.tab").takeIf { it in listOf("shelf","search","stats","account","friends") } ?: "shelf"
        listOf("shelf","search","stats","account","friends").forEach { positions[it]=state?.getInt("viblio.position.$it",0) ?: 0 }
        render()
        val isDeepLink=intent?.action==Intent.ACTION_VIEW && intent.data?.scheme=="viblio"
        if(state==null && !needsLogin() && !isDeepLink && tab=="shelf" && library.repository.current()!=null){
            library.openCurrent()
        } else {
            scroll.post { scroll.scrollTo(0,positions[tab] ?: 0) }
        }
        root.requestApplyInsets()
        openIncomingReading(intent)
        if(state==null && !introPlayed && !isDeepLink && br.com.viblio.ui.ViblioIntroView.animatorsEnabled(this)){
            introPlayed=true
            val overlay=br.com.viblio.ui.ViblioIntroView(this,cachedIntroCoverColor()){
                introView?.let {view->root.removeView(view)}
                introView=null
            }
            introView=overlay
            root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
            overlay.start()
        }
    }
    private fun makeSearch()=SearchPanel(this,ui,catalog,manual=::registerManual,scan={launchIsbnScanner("search")}) { record ->
            val open={ switchTab("shelf");library.addFromCatalog(record) }
            if(library.isEditor) AlertDialog.Builder(this).setTitle("Substituir cadastro?")
                .setMessage("Há um formulário em edição. Abrir outro cadastro descarta as alterações ainda não salvas.")
                .setPositiveButton("Abrir cadastro") { _,_->open() }.setNegativeButton("Continuar editando") { _,_->switchTab("shelf") }.show()
            else open()
        }
    private fun makeFriends()=br.com.viblio.ui.SocialPanel(this,ui,accounts){if(tab=="friends")render()}.apply {
        composerDock=this@ViblioActivity.composerDock
        onBackdrop={if(tab=="friends" && !needsLogin())applyBackground(displayTheme)}
    }
    private var suppressIconSwitch = false
    override fun startActivity(intent: Intent?, options: Bundle?) {
        suppressIconSwitch = true
        super.startActivity(intent, options)
    }
    @Deprecated("Activity result API")
    override fun startActivityForResult(intent: Intent?, requestCode: Int, options: Bundle?) {
        suppressIconSwitch = true
        super.startActivityForResult(intent, requestCode, options)
    }
    private fun refreshWidgetsAsync(captureLastBook:Boolean=false) {
        val ctx = applicationContext
        br.com.viblio.widget.BaseViblioReadingWidget.refreshAsync(ctx)
        if(captureLastBook && !needsLogin() && !suppressIconSwitch && !isChangingConfigurations) {
            val coverName = runCatching { library.currentReadingCover() }.getOrNull()
            br.com.viblio.ui.DynamicAppIcon.applyForBookAsync(ctx) {
                coverName?.let { br.com.centralcasa.books.BookImages(ctx).coverColor(it) }
            }
        }
    }
    private fun makeLibrary()=BooksPanel(this,ui,{homeAssistant.changed();accounts.localChanged();refreshWidgetsAsync();if(tab=="shelf")render()}).apply {
        onAccount={switchTab("account")}
        accountMenuBadge={
            val s=accounts.syncStatus()
            val initial=accounts.username.ifBlank {accounts.email}.firstOrNull()?.uppercaseChar()?.toString() ?: "V"
            BooksPanel.AccountMenuBadge(
                title="Sua conta",
                initial=initial,
                statusLabel=s.label,
                isError=s.isError,
                isPending=s.isPending,
                isOffline=s.isOffline
            )
        }
        jsonAllowed={accounts.signedIn && accounts.admin};useGroupedReadingHistory=true;useContrastCoverBorders=true;useSpineLayout=true;useCollectionInventory=true;useAsyncShelfImages=true;useCoverProgressColor=true;useManualShelfOrder=true;usePersonalArchive=true;separateShelfStatistics=true;pinchStep=1.4
    }
    private fun reloadProfile() {
        themeBookKey=null;ui.readingColor(null);saveIntroCoverColor(null);br.com.viblio.ui.DynamicAppIcon.applyForColorAsync(this,null)
        if(!accounts.signedIn)offline=false
        library.onStop();library.onDestroy();statistics?.onDestroy();statistics=null
        search.close();catalog.close();friends.close();friends=makeFriends()
        catalog=br.com.viblio.catalog.ViblioCatalog(this,changed={force->accounts.localChanged();refreshWidgetsAsync();if(force || tab=="shelf" && !library.isEditor)render()},personalToken=accounts::catalogToken,personalMode=accounts::personalMode,scanner={launchIsbnScanner("editor")})
        search=makeSearch();homeAssistant.close();homeAssistant=br.com.viblio.homeassistant.HomeAssistantBridge(this)
        library=makeLibrary().apply {catalog=this@ViblioActivity.catalog}
        refreshWidgetsAsync()
        if(started){homeAssistant.start();if(tab=="shelf" && !needsLogin())library.onStart()};render()
    }
    public override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent);openIncomingReading(intent)
    }
    private fun openIncomingReading(incoming:Intent?) {
        if(incoming?.action!=Intent.ACTION_VIEW || incoming.data?.scheme!="viblio" || incoming.data?.host!="current") return
        if(needsLogin())return
        // Consume deep link so configuration changes (rotation) never replay it.
        if(intent===incoming) setIntent(Intent(this, ViblioActivity::class.java))
        val open={ switchTab("shelf");library.openCurrent();scroll.post { scroll.scrollTo(0,0) } }
        if(library.isEditor) AlertDialog.Builder(this).setTitle("Abrir livro atual?")
            .setMessage("Há um formulário em edição. Abrir a leitura descarta as alterações ainda não salvas.")
            .setPositiveButton("Abrir leitura") { _,_->open() }.setNegativeButton("Continuar editando",null).show()
        else if(library.canReplaceLibrary) open()
    }
    private fun switchTab(destination:String) {
        if(tab==destination) return
        positions[tab]=scroll.scrollY
        if(tab=="shelf") { library.clearPlanSelection(); library.onStop() }
        if(tab=="search") search.hide()
        if(tab=="friends") {friends.hide(resetSelection=true);positions["friends"]=0}
        tab=destination
        if(tab=="shelf" && started) library.onStart()
        render()
        scroll.post { scroll.scrollTo(0,positions[tab] ?: 0) }
    }
    private fun render() {
        if(stopped){needsRender=true;return}
        needsRender=false
        updateReadingTheme()
        content.setPadding(ui.dp(20),ui.dp(12),ui.dp(20),ui.dp(24))
        content.removeAllViews()
        composerDock.removeAllViews()
        composerDock.visibility=android.view.View.GONE
        if(needsLogin()){
            navigation.visibility=android.view.View.GONE
            applyBackground(ui)
            scroll.moveBook=null;scroll.pinchColumns=null;scroll.canPinchAt={_,_->false}
            account.renderLogin(content){offline=true;tab="shelf";if(library.repository.current()!=null)library.openCurrent() else render();if(started)library.onStart();scroll.scrollTo(0,0);openIncomingReading(intent)}
            return
        }
        when(tab) {
            "shelf"->library.render(content)
            "search"->search.render(content)
            "friends"->friends.render(content)
            "stats"->{
                val panel=statistics ?: BooksPanel(this,ui) { accounts.localChanged();if(tab=="stats") render() }.apply {
                    useRedesignedStatistics=true
                    separateShelfStatistics=true
                    planCatalogProvider={ catalog }
                    onOpenPlannedBook={ id -> switchTab("shelf");library.openBook(id) }
                    onAddCatalogToShelf={ rec -> switchTab("shelf");library.addFromCatalog(rec) }
                    onRequestShelfBookForPlan={ year, month ->
                        val label="${br.com.centralcasa.books.AnnualPlanCalculator.fullName(month)} $year"
                        library.planSelectionTitle=label
                        library.onPlanBookSelected={ picked ->
                            library.clearPlanSelection()
                            statistics?.openPlanTab(year,month)
                            switchTab("stats")
                            statistics?.completePlanBookSelection(year,month,picked)
                        }
                        library.onCancelPlanSelection={
                            library.clearPlanSelection()
                            statistics?.openPlanTab(year,month)
                            switchTab("stats")
                        }
                        switchTab("shelf")
                        library.openShelf()
                        scroll.post { scroll.scrollTo(0,0) }
                    }
                    prepareStatistics()
                    onStatisticsBack={switchTab("shelf")}
                }.also { statistics=it }
                panel.render(content)
            }
            else->account.render(content)
        }
        val hideNav=tab=="friends" && friends.hideNavigation
        navigation.visibility=if(hideNav)android.view.View.GONE else android.view.View.VISIBLE
        val navUi=if(tab=="friends")friends.displayTheme else if(tab=="shelf" && (library.isEditor || library.isDetail || library.isCollection))library.displayTheme() else ui
        applyBackground(navUi)
        updatePullTheme(navUi)
        if (pullSpinning && (tab != "friends" || !friends.isRefreshing)) {
            if (tab == "friends") { root.removeCallbacks(pullTimeout); stopPullSpinner() }
        }
        scroll.pinchStep=1.4
        scroll.moveBook = if(tab=="shelf" && (library.isDetail || library.isCollection)) library::moveBook else null
        scroll.pinchColumns = when {tab=="shelf" && (library.isShelf || library.isCollection)->library::pinchColumns;tab=="friends"->friends::pinchColumns;else->null}
        scroll.canPinchAt = { x,y->when(tab){"shelf"->library.canPinchColumnsAt(x,y);"friends"->friends.canPinchAt(x,y);else->false} }
        navigation.removeAllViews()
        listOf(Triple("current","Livro atual","books"),Triple("shelf","Estante","book_nav"),Triple("search","Pesquisa","search"),Triple("stats","Estatísticas","stats"),Triple("friends","Amigos","people")).forEach { (key,label,icon)->
            val selectedTab=if(tab=="shelf" && library.isCurrentReading)"current" else tab
            val item=navUi.column().apply { gravity=Gravity.CENTER;background=navUi.ripple(if(!navUi.rectilinear && selectedTab==key) navUi.activeSurface else Color.TRANSPARENT,14)
                setPadding(navUi.dp(4),navUi.dp(6),navUi.dp(4),navUi.dp(6));contentDescription=label;isFocusable=true;isSelected=selectedTab==key
            }
            navigation.addView(item,LinearLayout.LayoutParams(0,-1,1f).apply { marginStart=navUi.dp(2);marginEnd=navUi.dp(2) })
            val image=ImageView(this).apply {
                when(key){
                    "current","shelf" -> setImageDrawable(if(selectedTab==key) br.com.viblio.ui.ViblioBookMarkDrawable(navUi.accent,Color.WHITE) else br.com.viblio.ui.ViblioBookMarkDrawable(navUi.muted,navUi.muted))
                    else -> setImageDrawable(navUi.symbol(icon,if(selectedTab==key) navUi.accent else navUi.muted,25))
                }
                importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            item.addView(image,LinearLayout.LayoutParams(navUi.dp(28),navUi.dp(28)))
            if(key=="current"){
                val source=library
                // Decode from the profile's local cover cache outside the UI thread.
                navigationCovers.execute {
                    // Synchronization may retire this profile while this queued job waits.
                    val cover=runCatching {source.currentReadingThumbnail()}.getOrNull()
                    image.post {
                        if(!isDestroyed && library===source && image.isAttachedToWindow && cover!=null){
                            image.clearColorFilter()
                            image.scaleType=ImageView.ScaleType.CENTER_CROP
                            image.layoutParams=LinearLayout.LayoutParams(navUi.dp(20),navUi.dp(28))
                            image.background=navUi.shape(Color.TRANSPARENT,3)
                            image.clipToOutline=true
                            image.setImageBitmap(cover)
                        }
                    }
                }
            }
            navUi.label(item,label,10,if(selectedTab==key) navUi.accent else navUi.muted).apply { gravity=Gravity.CENTER;importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            item.setOnClickListener { when(key){"current"->openCurrentReading();"shelf"->openShelfTab();else->switchTab(key)} }
        }
    }
    private fun applyBackground(theme:DisplayUi){
        val libraryVisible=tab=="shelf" && !needsLogin()
        val friendsVisible=tab=="friends" && !needsLogin()
        val topGlow=(tab=="stats" || tab=="search") && !needsLogin() && br.com.centralcasa.data.LocalSettings(this).galleryUiStyle()
        val color=if(libraryVisible)library.detailBackgroundColor() ?: theme.background else theme.background
        val backdrop=when {
            libraryVisible->library.screenBackground()
            friendsVisible->friends.screenBackground()
            topGlow->object:android.graphics.drawable.Drawable() {
                private val basePaint=android.graphics.Paint().apply {this.color=color}
                private val glowPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                private var lastW=0;private var lastH=0;private var radius=0f
                override fun draw(canvas:android.graphics.Canvas){
                    val b=bounds;val w=b.width();val h=b.height()
                    canvas.drawRect(b,basePaint)
                    if(w<=0 || h<=0)return
                    val cx=b.left+w*0.5f;val top=b.top.toFloat()
                    if(w!=lastW || h!=lastH){
                        lastW=w;lastH=h
                        radius=(w*0.78f).coerceAtLeast(theme.dp(260).toFloat())
                        glowPaint.shader=android.graphics.RadialGradient(
                            cx, top, radius,
                            intArrayOf(theme.tint(theme.accent,44), theme.tint(theme.accent,18), Color.TRANSPARENT),
                            floatArrayOf(0f,0.48f,1f),
                            android.graphics.Shader.TileMode.CLAMP,
                        )
                    }
                    canvas.save()
                    canvas.scale(1f,0.78f,cx,top)
                    canvas.drawCircle(cx,top,radius,glowPaint)
                    canvas.restore()
                }
                override fun setAlpha(alpha:Int){}
                override fun setColorFilter(colorFilter:android.graphics.ColorFilter?){}
                @Deprecated("Deprecated in Java")
                override fun getOpacity()=android.graphics.PixelFormat.OPAQUE
            }
            else->null
        }
        root.background=backdrop ?: theme.shape(color,0)
        navigation.setBackgroundColor(color)
        window.statusBarColor=Color.TRANSPARENT
        window.navigationBarColor=Color.TRANSPARENT
    }
    private fun openShelfTab(){
        if(library.isShelf){switchTab("shelf");return}
        val open={switchTab("shelf");library.openShelf()}
        if(library.isEditor)AlertDialog.Builder(this).setTitle("Voltar à Estante?")
            .setMessage("Há alterações não salvas neste formulário.")
            .setPositiveButton("Voltar à Estante") {_,_->open()}.setNegativeButton("Continuar editando",null).show()
        else if(library.canReplaceLibrary)open()
        else android.widget.Toast.makeText(this,"Aguarde a operação atual.",android.widget.Toast.LENGTH_LONG).show()
    }
    private fun openCurrentReading(){
        if(library.repository.current()==null){android.widget.Toast.makeText(this,"Nenhum livro atual. Escolha um livro na Estante.",android.widget.Toast.LENGTH_LONG).show();return}
        val open={switchTab("shelf");library.openCurrent();scroll.post {scroll.scrollTo(0,0)}}
        if(library.isEditor)AlertDialog.Builder(this).setTitle("Abrir livro atual?")
            .setMessage("Há alterações não salvas neste formulário.")
            .setPositiveButton("Abrir leitura") {_,_->open()}.setNegativeButton("Continuar editando",null).show()
        else if(library.canReplaceLibrary)open()
        else android.widget.Toast.makeText(this,"Conclua a organização ou aguarde a operação atual.",android.widget.Toast.LENGTH_LONG).show()
    }
    private fun registerManual(kind:String) {
        val open={ switchTab("shelf");library.addItem(kind) }
        if(library.isEditor) AlertDialog.Builder(this).setTitle("Substituir cadastro?")
            .setMessage("Há um formulário em edição. Abrir outro cadastro descarta as alterações ainda não salvas.")
            .setPositiveButton("Abrir cadastro") { _,_->open() }.setNegativeButton("Continuar editando") { _,_->switchTab("shelf") }.show()
        else open()
    }
    override fun onStart() { super.onStart();suppressIconSwitch=false;stopped=false;accounts.start();homeAssistant.start();started=true;if(tab=="shelf" && !needsLogin()) library.onStart();catalog.retry();if(br.com.viblio.reminder.ReadingReminder.isEnabled(this)) br.com.viblio.reminder.ReadingReminder.scheduleNext(this);if(needsRender)render() }
    override fun onStop() { stopped=true;accounts.stop();homeAssistant.stop();started=false;library.onStop();if(tab=="search") search.hide();if(tab=="friends")friends.hide();refreshWidgetsAsync(captureLastBook=true);super.onStop() }
    override fun onResume() { super.onResume();if(tab=="search" || tab=="friends") render() }
    override fun onDestroy() { root.removeCallbacks(pullTimeout);introView?.cancel();introView=null;navigationCovers.shutdownNow();accounts.close();homeAssistant.close();search.close();catalog.close();friends.close();statistics?.onDestroy();library.onDestroy();super.onDestroy() }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("scan.target",scanTarget);outState.putString("scan.profile",scanProfile);outState.putString("scan.editor",scanEditor)
        outState.putBoolean("viblio.offline",offline)
        positions[tab]=scroll.scrollY;outState.putString("viblio.tab",tab)
        positions.forEach { (key,value)->outState.putInt("viblio.position.$key",value) }
        search.save(outState);friends.save(outState);library.saveState(outState);super.onSaveInstanceState(outState)
    }
    @Deprecated("Native collection navigation")
    override fun onBackPressed() {
        if(needsLogin()){super.onBackPressed();return}
        if(tab=="friends" && friends.back())return
        if(tab!="shelf") switchTab("shelf") else if(!library.back()) super.onBackPressed()
    }
    @Deprecated("Document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode==920){
            val target=scanTarget;val profile=scanProfile;val editor=scanEditor
            scanTarget=null;scanProfile=null;scanEditor=null
            val isbn=data?.getStringExtra("isbn")?.let(br.com.centralcasa.books.Isbn::normalize)
            if(resultCode==RESULT_OK && isbn!=null && !needsLogin() && profile==br.com.centralcasa.books.LibraryProfiles.database(this)){
                if(target=="search" && tab=="search"){search.acceptIsbn(isbn);render()}
                else if(target=="editor" && tab=="shelf" && editor==library.editingId)library.acceptScannedIsbn(isbn)
            }
            return
        }
        library.onActivityResult(requestCode, resultCode, data)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 935) {
            val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
            statistics?.onReminderPermissionResult(granted)
            library.onReminderPermissionResult(granted)
        }
    }
}
