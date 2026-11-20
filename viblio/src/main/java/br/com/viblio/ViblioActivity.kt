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

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(android.os.Build.VERSION.SDK_INT >= 31){
            splashScreen.setOnExitAnimationListener { it.remove() }
        }
        ui = DisplayUi(this).apply {readingColor(null)}
        root = FrameLayout(this)
        content = ui.column().apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(24)) }
        scroll = ReadingScrollView(this).apply {
            id = R.id.content_scroll; isFillViewport = true
            clipToPadding = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        val host=ui.column()
        root.addView(host,FrameLayout.LayoutParams(-1,-1))
        host.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        composerDock=FrameLayout(this).apply { visibility=android.view.View.GONE }
        host.addView(composerDock,LinearLayout.LayoutParams(-1,-2))
        navigation=ui.row().apply { setPadding(ui.dp(12),ui.dp(6),ui.dp(12),ui.dp(6));background=ui.shape(ui.background,0) }
        host.addView(navigation,LinearLayout.LayoutParams(-1,ui.dp(72)))
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                if (insets.isVisible(android.view.WindowInsets.Type.ime()) && composerDock.visibility == android.view.View.VISIBLE) {
                    scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                }
                android.view.WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                @Suppress("DEPRECATION")
                insets.consumeSystemWindowInsets()
            }
        }
        window.statusBarColor = ui.background
        window.navigationBarColor = ui.background
        setContentView(root)
        accounts=br.com.viblio.account.AccountManager(this,changed={if(!accounts.signedIn)offline=false;if(needsLogin() || navigation.visibility==android.view.View.GONE || tab=="account")render()},reloaded=::reloadProfile,canReplace={::library.isInitialized && library.canReplaceLibrary && (statistics?.canReplaceLibrary ?: true) && (!::friends.isInitialized || friends.canReplaceLibrary)})
        homeAssistant=br.com.viblio.homeassistant.HomeAssistantBridge(this)
        library=makeLibrary()
        catalog=br.com.viblio.catalog.ViblioCatalog(this,changed={force->accounts.localChanged();if(force || tab=="shelf" && !library.isEditor)render()},personalToken=accounts::catalogToken,personalMode=accounts::personalMode,scanner={launchIsbnScanner("editor")})
        library.catalog=catalog
        search=makeSearch()
        friends=makeFriends()
        account=AccountPanel(this,ui,accounts,changed={render()},shelf={switchTab("shelf")})
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
    private fun makeLibrary()=BooksPanel(this,ui,{homeAssistant.changed();accounts.localChanged();if(tab=="shelf")render()}).apply {
        onAccount={switchTab("account")}
        jsonAllowed={accounts.signedIn && accounts.admin};useGroupedReadingHistory=true;useContrastCoverBorders=true;useSpineLayout=true;useCollectionInventory=true;useAsyncShelfImages=true;useCoverProgressColor=true;useManualShelfOrder=true;usePersonalArchive=true;separateShelfStatistics=true;pinchStep=1.4
    }
    private fun reloadProfile() {
        themeBookKey=null;ui.readingColor(null);saveIntroCoverColor(null)
        if(!accounts.signedIn)offline=false
        library.onStop();library.onDestroy();statistics?.onDestroy();statistics=null
        search.close();catalog.close();friends.close();friends=makeFriends()
        catalog=br.com.viblio.catalog.ViblioCatalog(this,changed={force->accounts.localChanged();if(force || tab=="shelf" && !library.isEditor)render()},personalToken=accounts::catalogToken,personalMode=accounts::personalMode,scanner={launchIsbnScanner("editor")})
        search=makeSearch();homeAssistant.close();homeAssistant=br.com.viblio.homeassistant.HomeAssistantBridge(this)
        library=makeLibrary().apply {catalog=this@ViblioActivity.catalog}
        if(started){homeAssistant.start();if(tab=="shelf" && !needsLogin())library.onStart()};render()
    }
    public override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent);openIncomingReading(intent)
    }
    private fun openIncomingReading(incoming:Intent?) {
        if(incoming?.action!=Intent.ACTION_VIEW || incoming.data?.scheme!="viblio" || incoming.data?.host!="current") return
        if(needsLogin())return
        val open={ switchTab("shelf");library.openCurrent();scroll.post { scroll.scrollTo(0,0) } }
        if(library.isEditor) AlertDialog.Builder(this).setTitle("Abrir livro atual?")
            .setMessage("Há um formulário em edição. Abrir a leitura descarta as alterações ainda não salvas.")
            .setPositiveButton("Abrir leitura") { _,_->open() }.setNegativeButton("Continuar editando",null).show()
        else open()
    }
    private fun switchTab(destination:String) {
        if(tab==destination) return
        positions[tab]=scroll.scrollY
        if(tab=="shelf") library.onStop()
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
                    prepareStatistics()
                    onStatisticsBack={switchTab("shelf")}
                }.also { statistics=it }
                panel.render(content)
            }
            else->account.render(content)
        }
        val hideNav=tab=="friends" && friends.hideNavigation
        navigation.visibility=if(hideNav)android.view.View.GONE else android.view.View.VISIBLE
        val navUi=if(tab=="friends")friends.displayTheme else ui
        applyBackground(navUi)
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
        val color=if(libraryVisible)library.detailBackgroundColor() ?: theme.background else theme.background
        val backdrop=when {
            libraryVisible->library.screenBackground()
            friendsVisible->friends.screenBackground()
            else->null
        }
        root.background=backdrop ?: theme.shape(color,0)
        navigation.setBackgroundColor(color)
        window.statusBarColor=color
        window.navigationBarColor=color
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
    override fun onStart() { super.onStart();stopped=false;accounts.start();homeAssistant.start();started=true;if(tab=="shelf" && !needsLogin()) library.onStart();catalog.retry();if(needsRender)render() }
    override fun onStop() { stopped=true;accounts.stop();homeAssistant.stop();started=false;library.onStop();if(tab=="search") search.hide();if(tab=="friends")friends.hide();super.onStop() }
    override fun onResume() { super.onResume();if(tab=="search" || tab=="friends") render() }
    override fun onDestroy() { introView?.cancel();introView=null;navigationCovers.shutdownNow();accounts.close();homeAssistant.close();search.close();catalog.close();friends.close();statistics?.onDestroy();library.onDestroy();super.onDestroy() }
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
}
