package br.com.viblio.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import br.com.centralcasa.books.LibraryProfiles
import br.com.centralcasa.books.LibraryBook
import br.com.centralcasa.ui.ShelfCoverFrame
import br.com.centralcasa.ui.ShelfCoverView
import br.com.centralcasa.ui.DisplayUi
import br.com.viblio.account.*
import com.google.gson.*
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors

internal object FriendCommonBooks {
    private fun norm(v: String): String =
        br.com.centralcasa.books.ShelfFilters.key(v).replace(Regex("\\s+"), " ").trim()

    fun findMatch(friendItem: JsonObject, ownBooks: List<LibraryBook>): LibraryBook? {
        if (friendItem.get("item_type")?.takeUnless { it.isJsonNull }?.asString == "collection") return null
        val fTitle = norm(friendItem.get("title")?.takeUnless { it.isJsonNull }?.asString.orEmpty())
        if (fTitle.isBlank()) return null
        val fAuthor = norm(friendItem.get("author")?.takeUnless { it.isJsonNull }?.asString.orEmpty())
        val active = ownBooks.filter { !it.archived }
        if (fAuthor.isNotBlank()) {
            active.firstOrNull { norm(it.title) == fTitle && norm(it.author) == fAuthor }?.let { return it }
        }
        return active.firstOrNull { norm(it.title) == fTitle && (fAuthor.isBlank() || it.author.isBlank()) }
    }

    fun ownSummary(book: LibraryBook): String {
        val status = when {
            book.complete -> "Concluído"
            book.currentPage > 0 -> if (book.pagesKnown) "Lendo ${book.percent}%" else "Lendo"
            else -> "Na estante"
        }
        val rating = book.rating?.let { " · ★ ${FeedGrouping.formatRating(it)}" }.orEmpty()
        return "$status$rating"
    }

    fun friendSummary(item: JsonObject): String {
        val complete = item.get("complete")?.takeUnless { it.isJsonNull }?.runCatching { asBoolean }?.getOrDefault(false) == true
        val pages = item.get("pages")?.takeUnless { it.isJsonNull }?.runCatching { asInt }?.getOrNull()
        val page = item.get("page")?.takeUnless { it.isJsonNull }?.runCatching { asInt }?.getOrDefault(0) ?: 0
        val rating = item.get("rating")?.takeUnless { it.isJsonNull }?.runCatching { asDouble }?.getOrNull()
        val status = when {
            complete -> "Concluído"
            page > 0 && pages != null && pages > 0 -> "Lendo ${(page.toLong() * 100L / pages).coerceIn(0, 100)}%"
            page > 0 -> "Lendo"
            else -> "Na estante"
        }
        val rStr = rating?.let { " · ★ ${FeedGrouping.formatRating(it)}" }.orEmpty()
        return "$status$rStr"
    }
}

/** An independent read-only view: never writes into LibraryRepository or local cover files. */
class FriendsPanel(private val activity:Activity,private val baseUi:DisplayUi,private val accounts:AccountManager,private val onDiscover:(()->Unit)?=null,private val embedded:Boolean=false,private val sharedImages:(()->FriendCoverLoader?)?=null,private val ownBooks:(()->List<LibraryBook>)?=null,private val onActivityChanged:(()->Unit)?=null,private val changed:()->Unit) {
    private val ui=DisplayUi(activity)
    val displayTheme get()=ui
    val inDetail get()=friend!=null
    val isLoading get()=busy
    fun refresh(){readingCover="";readingResolved=false;readingCursor=null;readingPages=0;reset(preserve=true);load()}
    private val friendColors=mutableMapOf<String,Int?>()
    private var shelfGrid:View?=null;private var columns=3
    private var readingBookId=""
    private var readingCover="";private var readingResolved=false;private var readingCursor:String?=null;private var readingPages=0
    private val handler=Handler(Looper.getMainLooper());private val worker=Executors.newSingleThreadExecutor()
    private var requestConnection:AccountConnection?=null;private var refreshConnection:AccountConnection?=null
    private var client:AccountApi?=null;private var clientIdentity="";private var images:FriendCoverLoader?=null
    private fun coverLoader():FriendCoverLoader? = sharedImages?.invoke() ?: images
    private var identity="";private var active=false;private var closed=false;private var generation=0
    private var busy=false;private var loaded=false;private var scheduled=false;private var refreshWanted=false;private var colorRedrawScheduled=false
    private var refreshing=false;private var refreshEpoch=-1;private var refreshScheduled=false;private var refreshDelay=30000L;private var lastEditAt=0L
    private val refreshTick=Runnable {refreshScheduled=false;refreshVisible();armRefresh()}
    private val colorRedraw=Runnable {colorRedrawScheduled=false;if(active && !closed)changed()}
    private fun armRefresh(){if(active && !closed && !refreshScheduled){refreshScheduled=true;handler.postDelayed(refreshTick,refreshDelay)}}
    private var error="";private var friend:String?=null;private var friendName="";private var collection="";private var collectionName=""
    private var kind="all";private var query="";private var username="";private var revision=0L;private var commonOnly=false
    private var cursor:String?=null;private var libraryCursor:Int?=null
    private var items=mutableListOf<JsonObject>();private var requests=emptyList<JsonObject>()
    private var searchTask:Runnable?=null
    private var focusedInput:String?=null
    private var infoDialog:AlertDialog?=null
    private var conflictRetried=false
    private var retryMore=false
    private fun keepFocus(field:EditText,key:String){field.addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){lastEditAt=android.os.SystemClock.elapsedRealtime()};override fun afterTextChanged(s:Editable?){} });field.setOnFocusChangeListener {_,hasFocus->if(hasFocus)focusedInput=key};if(focusedInput==key)field.post {if(field.isAttachedToWindow){field.requestFocus();field.setSelection(field.text.length)}}}
    private fun JsonObject.str(key:String)=get(key)?.takeUnless {it.isJsonNull}?.runCatching {asString}?.getOrNull().orEmpty()
    private fun array(value:JsonObject,key:String,limit:Int)=value.getAsJsonArray(key)?.take(limit)?.mapNotNull {it.takeIf {it.isJsonObject}?.asJsonObject}.orEmpty()
    private fun ownIdentity()=LibraryProfiles.database(activity)+":"+accounts.catalogToken().orEmpty()
    private fun uuid(value:String)=runCatching {UUID.fromString(value).toString()}.getOrNull()
    private fun encode(value:String)=URLEncoder.encode(value,"UTF-8")
    fun save(out:Bundle){out.putInt("friends.columns",columns);out.putString("friends.selected",friend);out.putString("friends.kind",kind);out.putString("friends.collection",collection);out.putString("friends.query",query);out.putBoolean("friends.commonOnly",commonOnly)}
    fun restore(state:Bundle?){columns=(state?.getInt("friends.columns",3) ?: 3).coerceIn(2,6);friend=state?.getString("friends.selected")?.let(::uuid);kind=state?.getString("friends.kind").takeIf {it in listOf("all","book","manga","comic")} ?: "all";collection=state?.getString("friends.collection").orEmpty().take(200);query=state?.getString("friends.query").orEmpty().take(80);commonOnly=state?.getBoolean("friends.commonOnly",false)==true}
    fun hide(resetSelection:Boolean=false){infoDialog?.dismiss();infoDialog=null;if(resetSelection){friend=null;collection="";kind="all";query="";commonOnly=false};active=false;requestConnection?.cancel();requestConnection=null;refreshConnection?.cancel();refreshConnection=null;refreshing=false;refreshEpoch=-1;refreshScheduled=false;refreshDelay=30000L;colorRedrawScheduled=false;shelfGrid=null;readingBookId="";readingCover="";readingResolved=false;readingCursor=null;readingPages=0;generation++;busy=false;loaded=false;scheduled=false;refreshWanted=false;client?.cancel();if(sharedImages!=null)sharedImages.invoke()?.detach() else images?.detach();items.clear();requests=emptyList();friendName="";collectionName="";focusedInput=null;error="";handler.removeCallbacksAndMessages(null)}
    fun close(){hide();closed=true;friendColors.clear();identity="";images?.close();images=null;client?.close();client=null;clientIdentity="";worker.shutdownNow()}
    fun back():Boolean {
        if(friend==null)return false
        focusedInput=null
        if(collection.isNotBlank()){collection="";collectionName=""}else {friend=null;friendName="";kind="all";query="";commonOnly=false;readingCover="";readingResolved=false;readingCursor=null;readingPages=0}
        reset();changed();return true
    }
    fun openFriend(id:String,name:String){
        val valid=uuid(id) ?: return
        focusedInput=null;friend=valid;friendName=name.take(32);readingCover="";readingBookId="";readingResolved=false;readingCursor=null;readingPages=0;collection="";kind="all";query="";commonOnly=false
        reset()
    }
    private fun reset(preserve:Boolean=false){generation++;requestConnection?.cancel();requestConnection=null;refreshConnection?.cancel();refreshConnection=null;busy=false;refreshWanted=false;refreshing=false;refreshEpoch=-1;loaded=false;error="";conflictRetried=false;if(!preserve){items.clear();requests=emptyList()};cursor=null;libraryCursor=null;revision=0;if(sharedImages==null && images==null){val api=client;images=api?.let {FriendCoverLoader(it::thumbnail)}}}
    private fun later(){if(scheduled || busy || closed || !active)return;scheduled=true;handler.post {scheduled=false;if(active && !loaded && !busy)load()}}
    private fun failure(error:Throwable,path:String,method:String):String {
        if(error is AccountFailure && error.code==404 && path.substringBefore('?')=="/v1/friends" && method=="GET")return "Atualize a API do servidor para habilitar Amigos."
        return SocialRequests.message(error,accounts.address)
    }
    private fun request(path:String,method:String="GET",body:JsonObject?=null,done:(JsonObject)->Unit){
        if(busy){if(method=="GET")refreshWanted=true else Toast.makeText(activity,"Aguarde a operação atual.",Toast.LENGTH_SHORT).show();return}
        refreshConnection?.cancel();refreshConnection=null;refreshing=false;refreshEpoch=-1
        val api=client ?: return;val epoch=++generation;val profile=identity;val connection=AccountConnection();requestConnection=connection
        busy=true;error="";changed()
        worker.execute {
            val response=runCatching {SocialRequests.call(api,path,method,body,connection)}
            handler.post {
                if(closed || !active || generation!=epoch || identity!=profile || ownIdentity()!=profile)return@post
                busy=false;requestConnection=null
                if(refreshWanted){refreshWanted=false;loaded=false;items.clear();load();return@post}
                response.fold({conflictRetried=false;done(it)},{problem->
                    if(problem is AccountFailure && problem.code==409 && method=="GET" && path.contains("/library") && !conflictRetried){
                        conflictRetried=true;libraryCursor=null;revision=0;load();return@post
                    }
                    error=failure(problem,path,method)
                    if(problem is AccountFailure && problem.code in listOf(401,403,404) && (path.contains("/library") || problem.code==401)){
                        infoDialog?.dismiss();infoDialog=null;items.clear();requests=emptyList();if(sharedImages==null){images?.close();images=FriendCoverLoader(api::thumbnail)}
                    }
                    loaded=true;changed()
                })
            }
        }
    }
    private fun load(more:Boolean=false){
        if(!busy)retryMore=more
        if(busy){refreshWanted=true;return}
        val selected=friend
        val path=if(selected==null)"/v1/friends"+(if(more && cursor!=null)"?cursor=${encode(cursor!!)}" else "") else buildString {
            append("/v1/friends/$selected/library?kind=$kind&q=${encode(query)}&collection_id=${encode(collection)}")
            if(more && libraryCursor!=null)append("&cursor=$libraryCursor&revision=$revision")
        }
        request(path){response->
            if(!more)items.clear()
            items.addAll(array(response,"items",if(selected==null)30 else 40));items=items.distinctBy {it.str("id")}.take(if(selected==null)200 else 10000).toMutableList()
            if(selected==null){requests=array(response,"requests",100);cursor=response.str("next_cursor").takeIf(String::isNotBlank)}
            else {
                friendName=response.getAsJsonObject("friend")?.str("username").orEmpty().take(32)
                revision=response.get("revision")?.asLong ?: 0L;libraryCursor=response.get("next_cursor")?.takeUnless {it.isJsonNull}?.asInt
                collectionName=response.get("collection")?.takeUnless {it.isJsonNull}?.asJsonObject?.str("title").orEmpty()
            }
            loaded=true;changed()
            // Resolve current reading highlight & theme in background without delaying shelf display.
            if(selected!=null && !readingResolved && !more) handler.post { if(active && !closed && friend==selected) refreshVisible() }
        }
    }
    private data class Fresh(val rows:List<JsonObject>,val requests:List<JsonObject>,val cursor:String?,val current:JsonObject?,val shelf:JsonObject?)
    /** Poll current progress without throwing away loaded pages, filters, typing or scroll position. */
    private fun refreshVisible(){
        if(!active || closed || busy || refreshing || !loaded || infoDialog?.isShowing==true || android.os.SystemClock.elapsedRealtime()-lastEditAt<3000)return
        val api=client ?: return
        val epoch=generation;val profile=identity;val selected=friend;val filter=Triple(kind,query,collection)
        val wanted=items.size.coerceIn(30,200);val singleShelfPage=selected!=null && items.size<=40
        refreshing=true;refreshEpoch=epoch;val connection=AccountConnection();refreshConnection=connection
        worker.execute {
            val response=runCatching {
                val fresh=mutableListOf<JsonObject>();var next:String?=null;var pending=emptyList<JsonObject>();var current:JsonObject?=null
                for(page in 0 until 7){
                    val part=SocialRequests.call(api,"/v1/friends"+(next?.let {"?cursor=${encode(it)}"} ?: ""),connection=connection)
                    val rows=array(part,"items",30);fresh.addAll(rows);if(page==0)pending=array(part,"requests",100)
                    current=rows.firstOrNull {it.str("id")==selected}?.get("current")?.takeUnless {it.isJsonNull}?.asJsonObject ?: current
                    next=part.str("next_cursor").takeIf(String::isNotBlank)
                    if(selected==null && fresh.size>=wanted || selected!=null && rows.any {it.str("id")==selected} || next==null)break
                }
                if(selected!=null && fresh.none {it.str("id")==selected})throw AccountFailure(404,JsonObject(),"Amizade encerrada ou indisponível. Volte à lista.")
                val shelf=if(singleShelfPage)SocialRequests.call(api,"/v1/friends/$selected/library?kind=${filter.first}&q=${encode(filter.second)}&collection_id=${encode(filter.third)}",connection=connection)else null
                Fresh(fresh.take(200),pending,next,current,shelf)
            }
            handler.post {
                if(refreshEpoch==epoch){refreshing=false;refreshConnection=null}
                if(closed || !active || generation!=epoch || identity!=profile || ownIdentity()!=profile || friend!=selected || Triple(kind,query,collection)!=filter)return@post
                val previousItems=items;val previousRequests=requests;val previousMeta=listOf(readingBookId,readingCover,friendName,collectionName,cursor,libraryCursor,error)
                response.fold({fresh->
                    refreshDelay=30000L;error=""
                    if(selected==null){items=fresh.rows.toMutableList();requests=fresh.requests;cursor=fresh.cursor}
                    else {
                        readingBookId=fresh.current?.str("id").orEmpty();readingCover=fresh.current?.str("cover").orEmpty();readingResolved=true
                        if(fresh.shelf!=null){
                            items=array(fresh.shelf,"items",40).toMutableList();revision=fresh.shelf.get("revision")?.asLong ?: 0L
                            libraryCursor=fresh.shelf.get("next_cursor")?.takeUnless {it.isJsonNull}?.asInt
                            friendName=fresh.shelf.getAsJsonObject("friend")?.str("username").orEmpty().take(32)
                            collectionName=fresh.shelf.get("collection")?.takeUnless {it.isJsonNull}?.asJsonObject?.str("title").orEmpty()
                        }else fresh.current?.let {current->items=items.map {old->if(old.str("id")==current.str("id"))old.deepCopy().apply {current.entrySet().forEach {(key,value)->add(key,value.deepCopy())}}else old}.toMutableList()}
                    }
                    val paths=if(selected==null)items.mapNotNull {it.get("current")?.takeUnless {it.isJsonNull}?.asJsonObject?.str("cover")}else listOf(readingCover)
                    friendColors.keys.retainAll(paths.toSet())
                },{problem->
                    refreshDelay=(refreshDelay*2).coerceIn(60000,300000);error=failure(problem,if(selected==null)"/v1/friends" else "/v1/friends/$selected/library","GET")
                    if(problem is AccountFailure && problem.code in listOf(401,403,404)){infoDialog?.dismiss();infoDialog=null;items.clear();requests=emptyList();readingBookId="";readingCover="";if(sharedImages==null){images?.close();images=FriendCoverLoader(api::thumbnail)}}
                })
                if(previousItems!=items || previousRequests!=requests || previousMeta!=listOf(readingBookId,readingCover,friendName,collectionName,cursor,libraryCursor,error)){
                    val scroll=activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll);val position=scroll?.scrollY ?: 0
                    changed();scroll?.post {if(active)scroll.scrollTo(0,position)}
                }
                // Replace the earlier timer when retry backoff changed.
                handler.removeCallbacks(refreshTick);refreshScheduled=false;armRefresh()
            }
        }
    }
    private fun action(path:String,method:String="POST",body:JsonObject?=null){request(path,method,body){onActivityChanged?.invoke();reset();load()}}
    fun render(parent:LinearLayout){
        if(closed)return
        active=true
        if(!accounts.signedIn){
            hide(resetSelection=true);friendColors.clear();identity="";images?.close();images=null
            ui.label(parent,if(ui.rectilinear)"AMIGOS" else "Amigos",30,bold=true).typeface=Typeface.create("serif",Typeface.BOLD)
            ui.label(parent,"Entre para ver seus amigos.",14,ui.muted);return
        }
        val currentWho=ownIdentity()
        if(identity!=currentWho){
            val switching=identity.isNotBlank()
            // Invalidate old replies and release loading state before attaching the new session.
            // Preserve a selection restored at initial construction, never one from another account.
            hide(resetSelection=switching);friendColors.clear();active=true;identity=currentWho
            if(client==null || clientIdentity!=currentWho){client?.close();client=AccountApi(accounts.address,accounts.catalogToken().orEmpty());clientIdentity=currentWho;images?.close();images=null}
            if(sharedImages==null && images==null)images=FriendCoverLoader(client!!::thumbnail)
        }else if(sharedImages==null && images==null && client!=null){
            images=FriendCoverLoader(client!!::thumbnail)
        }
        armRefresh()
        if(friend!=null)ui.scopedReadingColor(friendColors[readingCover])else ui.inheritReadingPalette(baseUi)
        if(friend!=null && readingCover.isNotBlank()){val path=readingCover;coverLoader()?.color(path,eager=true){color->friendColors[path]=color;if(active && readingCover==path && ui.scopedReadingColor(color))changed()}}
        val gallery = br.com.centralcasa.data.LocalSettings(activity).galleryUiStyle()
        val myBooks=if(friend!=null && collection.isBlank()) ownBooks?.invoke().orEmpty() else emptyList()
        val commonMatches=if(friend!=null && collection.isBlank() && myBooks.isNotEmpty()) items.mapNotNull {it->FriendCommonBooks.findMatch(it,myBooks)?.let {mine->it to mine}} else emptyList()
        val header=ui.row();if(!embedded || friend!=null)parent.addView(header)
        if(friend!=null)ui.icon(header,"back","Voltar aos amigos",44){back()}
        ui.label(header,if(friend==null)(if(ui.rectilinear)"AMIGOS" else "Amigos") else if(collection.isNotBlank())collectionName.ifBlank {"Volumes"} else "@${friendName.ifBlank {"amigo"}}",30,bold=true).apply {typeface=Typeface.create("serif",if(ui.rectilinear)Typeface.NORMAL else Typeface.BOLD);layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
        if(friend!=null)ui.icon(header,"more","Opções da amizade",40){
            AlertDialog.Builder(activity).setItems(arrayOf("Remover amizade")){_,_->AlertDialog.Builder(activity).setTitle("Remover amizade?").setMessage("Vocês deixarão de ter acesso às estantes um do outro.").setPositiveButton("Remover"){_,_->val id=friend ?: return@setPositiveButton;request("/v1/friends/$id","DELETE"){friend=null;collection="";query="";kind="all";commonOnly=false;onActivityChanged?.invoke();reset();load()}}.setNegativeButton("Cancelar",null).show()}.show()
        }
        if(friend==null){
            if(onDiscover!=null){if(!embedded)ui.action(parent,"Adicionar amigos"){onDiscover.invoke()}.tag="friends-discover"}
            else {
            ui.label(parent,accounts.username.takeIf(String::isNotBlank)?.let {"Seu nome: @$it"} ?: "Defina seu nome de usuário em Conta para adicionar amigos.",12,ui.muted)
            val row=ui.row();parent.addView(row)
            val field=EditText(activity).apply {hint="Nome de usuário";isSingleLine=true;setText(username);setTextColor(ui.text);setHintTextColor(ui.muted);textSize=14f;setPadding(ui.dp(12),0,ui.dp(12),0);background=ui.outlined();filters=arrayOf(android.text.InputFilter.LengthFilter(33));tag="friend-username"}
            row.addView(field,LinearLayout.LayoutParams(0,ui.dp(48),1f));keepFocus(field,"username")
            ui.action(row,"Adicionar"){
                username=field.text.toString().trim().removePrefix("@").lowercase()
                if(!username.matches(Regex("[a-z0-9][a-z0-9_.]{2,31}"))){Toast.makeText(activity,"Confira o nome de usuário.",Toast.LENGTH_LONG).show();return@action}
                action("/v1/friends/requests",body=JsonObject().apply {addProperty("username",username)})
            }.apply {tag="friend-add";isEnabled=!busy;layoutParams=LinearLayout.LayoutParams(-2,ui.dp(48)).apply {marginStart=ui.dp(8)}}
            field.addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){username=s.toString()};override fun afterTextChanged(s:Editable?){} })
            }
        }else {
            val search=EditText(activity).apply {hint="Buscar nesta estante";setText(query);isSingleLine=true;setTextColor(ui.text);setHintTextColor(ui.muted);textSize=14f;setPadding(ui.dp(12),0,ui.dp(12),0);background=ui.outlined();filters=arrayOf(android.text.InputFilter.LengthFilter(80));tag="friend-shelf-query"}
            parent.addView(search,LinearLayout.LayoutParams(-1,ui.dp(48)));keepFocus(search,"query")
            search.addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun afterTextChanged(s:Editable?){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){query=s.toString();searchTask?.let(handler::removeCallbacks);searchTask=Runnable {reset();load()}.also {handler.postDelayed(it,500)}}
            })
            val tabs=ui.row();parent.addView(tabs)
            listOf("all" to "Todos","book" to "Livros","manga" to "Mangás","comic" to "Quadrinhos").forEach {(key,label)->ui.action(tabs,label){focusedInput=null;kind=key;reset();load()}.apply {isSelected=kind==key;textSize=11f;maxLines=1;setPadding(ui.dp(2),ui.dp(8),ui.dp(2),ui.dp(8));setTextColor(if(isSelected)ui.accent else ui.muted);background=null;layoutParams=LinearLayout.LayoutParams(0,-2,1f)}}
            if(collection.isBlank() && ownBooks!=null){
                val filterRow=ui.row().apply {gravity=Gravity.CENTER_VERTICAL}
                parent.addView(filterRow,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(10)})
                val commonBtn=TextView(activity).apply {
                    tag="friend-common-filter";isSelected=commonOnly;isFocusable=true
                    text="Em comum (${commonMatches.size})"
                    textSize=11f;gravity=Gravity.CENTER;includeFontPadding=false
                    setTextColor(if(commonOnly)ui.accent else ui.muted)
                    typeface=Typeface.create(if(commonOnly)"sans-serif-medium" else "sans-serif",Typeface.NORMAL)
                    setPadding(ui.dp(12),ui.dp(6),ui.dp(12),ui.dp(6))
                    background=if(gallery) ui.pillRipple(
                        if(commonOnly) ui.tint(ui.accent,34) else ui.gallerySurface(175),
                        16,
                        if(commonOnly) ui.tint(ui.accent,120) else ui.galleryHairline(115),
                    ) else ui.outlined(if(commonOnly)ui.activeSurface else ui.surface,if(commonOnly)ui.accent else ui.lineColor,0)
                    setOnClickListener {commonOnly=!commonOnly;changed()}
                }
                filterRow.addView(commonBtn,LinearLayout.LayoutParams(-2,-2))
            }
        }
        if(error.isNotBlank()){ui.label(parent,error,14,ui.muted);ui.action(parent,"Tentar novamente"){load(retryMore)}}
        val rows=ui.column();parent.addView(rows);coverLoader()?.attach(rows)
        fun gText(value:String,sp:Float,color:Int,serif:Boolean=false,medium:Boolean=false)=TextView(activity).apply {
            text=value;textSize=sp;setTextColor(color);includeFontPadding=false
            typeface=when {
                serif -> Typeface.create("serif",Typeface.NORMAL)
                medium -> Typeface.create("sans-serif-medium",Typeface.NORMAL)
                else -> Typeface.create("sans-serif",Typeface.NORMAL)
            }
        }
        if(friend==null){
            if(requests.isNotEmpty()){
                if(gallery){
                    rows.addView(
                        gText("PEDIDOS DE AMIZADE",10f,ui.muted).apply {letterSpacing=0.14f},
                        LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(8);bottomMargin=ui.dp(8)},
                    )
                } else ui.label(rows,"Pedidos de amizade",18,bold=true)
            }
            requests.forEach {pending->val id=uuid(pending.str("id")) ?: return@forEach;val incoming=pending.get("incoming")?.asBoolean==true
                val card=if(gallery) ui.column().apply {
                    background=ui.pillShape(ui.surface,16,ui.galleryHairline(145))
                    setPadding(ui.dp(16),ui.dp(14),ui.dp(16),ui.dp(14))
                    rows.addView(this,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(12)})
                } else ui.panel(rows,12)
                ui.label(card,"@${pending.str("username").take(32)}",16,bold=true)
                val controls=ui.row();card.addView(controls)
                if(incoming)ui.action(controls,"Aceitar"){action("/v1/friends/$id/accept")}.apply {isEnabled=!busy;layoutParams=LinearLayout.LayoutParams(0,-2,1f);tag="friend-accept:$id"}
                ui.action(controls,if(incoming)"Recusar" else "Cancelar pedido"){action("/v1/friends/$id","DELETE")}.apply {isEnabled=!busy;layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
            }
            if(items.isNotEmpty() && !gallery) ui.label(rows,"Meus amigos",18,bold=true)
            items.forEachIndexed {idx,entry->val id=uuid(entry.str("id")) ?: return@forEachIndexed;val reading=entry.get("current")?.takeUnless {it.isJsonNull}?.asJsonObject
                val path=reading?.str("cover").orEmpty()
                val rowUi=DisplayUi(activity).apply {scopedReadingColor(friendColors[path])}
                coverLoader()?.color(path){color->if(!friendColors.containsKey(path) || friendColors[path]!=color){friendColors[path]=color;if(active && !colorRedrawScheduled){colorRedrawScheduled=true;handler.postDelayed(colorRedraw,180)}}}
                if(gallery){
                    val card=ui.row().apply {
                        gravity=Gravity.CENTER_VERTICAL
                        setPadding(0,if(idx==0) ui.dp(6) else ui.dp(14),0,ui.dp(14))
                        isFocusable=true;contentDescription="Estante de @${entry.str("username")}";tag="friend:$id"
                    }
                    rows.addView(card,LinearLayout.LayoutParams(-1,-2))
                    val img=ImageView(activity).apply {
                        setImageDrawable(ViblioBookMarkDrawable(rowUi.accent,android.graphics.Color.WHITE,scale=0.46f,backgroundColor=rowUi.activeSurface))
                        scaleType=ImageView.ScaleType.CENTER_CROP
                        background=ui.pillShape(rowUi.activeSurface,5)
                        clipToOutline=true
                        elevation=ui.dp(6).toFloat()
                        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    card.addView(img,LinearLayout.LayoutParams(ui.dp(48),ui.dp(72)).apply {marginEnd=ui.dp(14)})
                    coverLoader()?.bind(img,path)
                    val labels=ui.column()
                    card.addView(labels,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(10)})
                    labels.addView(
                        gText("@${entry.str("username").take(32)}",14f,ui.text,medium=true).apply {
                            maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                        },
                        LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(3)},
                    )
                    val bookTitle=reading?.str("title")?.take(500)?.takeIf(String::isNotBlank)
                    labels.addView(
                        gText(bookTitle ?: "Sem livro atual",12.5f,if(bookTitle!=null) ui.tint(ui.text,215) else ui.muted,serif=bookTitle!=null).apply {
                            maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                        },
                        LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(3)},
                    )
                    reading?.get("page")?.takeUnless {it.isJsonNull}?.asInt?.let {page->
                        val total=reading.get("pages")?.takeUnless {it.isJsonNull}?.asInt
                        val progText=when {
                            total!=null && total>0 && page>=total -> "Concluído · 100%"
                            total!=null && total>0 -> "$page / $total págs · ${(page.toLong()*100/total).coerceIn(0,100)}%"
                            else -> "$page págs"
                        }
                        labels.addView(
                            gText(progText,11f,rowUi.accent).apply {
                                maxLines=1;tag="friend-current-page:$id"
                            },
                            LinearLayout.LayoutParams(-1,-2),
                        )
                    }
                    card.addView(
                        gText("›",18f,ui.muted),
                        LinearLayout.LayoutParams(-2,-2),
                    )
                    card.setOnClickListener {focusedInput=null;friend=id;friendName=entry.str("username");readingCover=path;readingBookId=reading?.str("id").orEmpty();readingResolved=true;collection="";kind="all";query="";commonOnly=false;reset();load()}
                    if(idx<items.lastIndex){
                        rows.addView(View(activity).apply {setBackgroundColor(ui.galleryHairline(105))},LinearLayout.LayoutParams(-1,1))
                    }
                }else {
                    val card=ui.row().apply {background=rowUi.outlined();setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12));isFocusable=true;contentDescription="Estante de @${entry.str("username")}";tag="friend:$id"};rows.addView(card,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(10)})
                    cover(card,reading?.str("cover").orEmpty(),46,66)
                    val labels=ui.column();card.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
                    rowUi.label(labels,"@${entry.str("username").take(32)}",19,rowUi.accent,bold=true)
                    ui.label(labels,reading?.str("title")?.take(500)?.takeIf(String::isNotBlank)?.let {"Lendo · $it"} ?: "Sem livro atual",12,ui.muted).apply {maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
                    reading?.get("page")?.takeUnless {it.isJsonNull}?.asInt?.let {page->
                        val total=reading.get("pages")?.takeUnless {it.isJsonNull}?.asInt
                        ui.label(labels,if(total!=null && total>0)"$page / $total páginas · ${(page.toLong()*100/total).coerceIn(0,100)}%" else "$page páginas",12,rowUi.accent).tag="friend-current-page:$id"
                    }
                    card.setOnClickListener {focusedInput=null;friend=id;friendName=entry.str("username");readingCover=path;readingBookId=reading?.str("id").orEmpty();readingResolved=true;collection="";kind="all";query="";commonOnly=false;reset();load()}
                }
            }
            if(loaded && !busy && error.isBlank() && items.isEmpty())ui.label(rows,"Nenhum amigo.",14,ui.muted)
        }else if(commonOnly && collection.isBlank()){
            shelfGrid=null
            if(commonMatches.isEmpty()){
                if(loaded && !busy && error.isBlank()) ui.label(rows,"Nenhum livro em comum nos itens carregados.",14,ui.muted)
            }else {
                commonMatches.forEachIndexed { idx, (book, own) ->
                    val pages=book.get("pages")?.takeUnless {it.isJsonNull}?.asInt
                    val title=book.str("title").take(500)
                    val author=book.str("author").take(200)
                    val ownSummary="Você: ${FriendCommonBooks.ownSummary(own)}"
                    val friendSummary="@${friendName.take(24)}: ${FriendCommonBooks.friendSummary(book)}"
                    val openInfo={
                        if(infoDialog?.isShowing!=true){
                            coverLoader()?.let { loader ->
                                infoDialog=BookInfoDialog.show(activity,ui,BookInfoDialog.Info(title,author,book.str("publisher"),book.str("genre"),pages,page=book.get("page")?.asInt ?: 0,owner=friendName),
                                    bind={img,_->loader.bind(img,book.str("cover"))},onClose={if(infoDialog?.isShowing!=true)infoDialog=null})
                            }
                        }
                    }
                    if(gallery){
                        val card=ui.row().apply {
                            gravity=Gravity.CENTER_VERTICAL
                            setPadding(0,if(idx==0) ui.dp(6) else ui.dp(12),0,ui.dp(12))
                            isFocusable=true;tag="friend-common:${book.str("id")}"
                            contentDescription="$title. $ownSummary. $friendSummary"
                            setOnClickListener { openInfo() }
                        }
                        rows.addView(card,LinearLayout.LayoutParams(-1,-2))
                        val img=ImageView(activity).apply {
                            setImageDrawable(ViblioBookMarkDrawable(ui.accent,android.graphics.Color.WHITE,scale=0.45f,backgroundColor=ui.activeSurface))
                            scaleType=ImageView.ScaleType.CENTER_CROP
                            background=ui.pillShape(ui.activeSurface,5);clipToOutline=true;elevation=ui.dp(4).toFloat()
                            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }
                        card.addView(img,LinearLayout.LayoutParams(ui.dp(46),ui.dp(68)).apply {marginEnd=ui.dp(12)})
                        coverLoader()?.bind(img,book.str("cover"))
                        val labels=ui.column()
                        card.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
                        labels.addView(gText(title,14f,ui.text,serif=true).apply {maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(2)})
                        if(author.isNotBlank()) labels.addView(gText(author,11.5f,ui.muted).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(4)})
                        labels.addView(gText(ownSummary,11.5f,ui.accent,medium=true).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(2)})
                        labels.addView(gText(friendSummary,11.5f,ui.muted).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,-2))
                        if(idx<commonMatches.lastIndex) rows.addView(View(activity).apply {setBackgroundColor(ui.galleryHairline(105))},LinearLayout.LayoutParams(-1,1))
                    }else {
                        val card=ui.row().apply {
                            background=ui.outlined();setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12))
                            isFocusable=true;tag="friend-common:${book.str("id")}"
                            contentDescription="$title. $ownSummary. $friendSummary"
                            setOnClickListener { openInfo() }
                        }
                        rows.addView(card,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(10)})
                        cover(card,book.str("cover"),46,66)
                        val labels=ui.column();card.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
                        ui.label(labels,title,15,bold=true).apply {maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
                        if(author.isNotBlank()) ui.label(labels,author,12,ui.muted).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
                        ui.label(labels,ownSummary,12,ui.accent,bold=true).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
                        ui.label(labels,friendSummary,12,ui.muted).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
                    }
                }
            }
        }else {
            val grid=ui.column().apply {tag="friend-shelf-grid"};shelfGrid=grid;rows.addView(grid)
            items.chunked(columns).forEach {books->
                val line=ui.row().apply {gravity=Gravity.TOP};grid.addView(line,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(12)})
                repeat(columns){index->
                    val card=ui.column();line.addView(card,LinearLayout.LayoutParams(0,-2,1f).apply {if(index>0)marginStart=ui.dp(if(columns>=5)4 else 8)})
                    val book=books.getOrNull(index) ?: return@repeat
                    val group=book.str("item_type")=="collection"
                    val pages=book.get("pages")?.takeUnless {it.isJsonNull}?.asInt
                    val complete=book.get("complete")?.asBoolean==true
                    val total=pages?.coerceIn(1,100000) ?: 1
                    val model=LibraryBook(id=book.str("id"),title=book.str("title").take(500),totalPages=total,currentPage=if(complete)total else (book.get("page")?.asInt ?: 0).coerceIn(0,if(pages==null)0 else total),pagesKnown=pages!=null,readFinished=complete)
                    card.tag="friend-book:${book.str("id")}";card.isFocusable=true
                    card.contentDescription=listOf(model.title,book.str("author"),if(group)"${book.get("volumes")?.asInt ?: 0} volumes" else "${model.percent}%").joinToString(", ")
                    val frame=ShelfCoverFrame(activity,model,!group && model.id==readingBookId,ui.lineColor).apply {background=ui.shape(ui.surface,0)}
                    card.addView(frame,LinearLayout.LayoutParams(-1,-2))
                    val image=ShelfCoverView(activity).apply {setImageDrawable(ViblioBookMarkDrawable(ui.accent,android.graphics.Color.WHITE,scale=0.45f,backgroundColor=ui.activeSurface));scaleType=ImageView.ScaleType.FIT_CENTER;background=ui.shape(ui.activeSurface,0);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
                    frame.addView(image,FrameLayout.LayoutParams(-1,-2));coverLoader()?.bind(image,book.str("cover"))
                    ui.label(card,model.title,11).apply {maxLines=2;minLines=2;gravity=Gravity.CENTER_HORIZONTAL;ellipsize=android.text.TextUtils.TruncateAt.END;visibility=View.GONE}
                    if(group)ui.label(card,"${book.get("volumes")?.asInt ?: 0} volumes",10,ui.muted).gravity=Gravity.CENTER_HORIZONTAL
                    card.setOnClickListener {if(group){focusedInput=null;collection=book.str("id").take(200);collectionName=book.str("title");commonOnly=false;reset();load()}else {
                        if(infoDialog?.isShowing==true)return@setOnClickListener
                        val loader=coverLoader() ?: return@setOnClickListener
                        infoDialog=BookInfoDialog.show(activity,ui,BookInfoDialog.Info(book.str("title"),book.str("author"),book.str("publisher"),book.str("genre"),pages,page=book.get("page")?.asInt ?: 0,owner=friendName),
                            bind={img,_->loader.bind(img,book.str("cover"))},onClose={if(infoDialog?.isShowing!=true)infoDialog=null})
                    }}
                }
            }
        }
        if(friend!=null && !commonOnly && loaded && !busy && error.isBlank() && items.isEmpty())ui.label(rows,"Nenhum livro encontrado.",14,ui.muted)
        if((friend==null && cursor!=null || friend!=null && libraryCursor!=null) && !busy)ui.action(rows,"Carregar mais"){load(true)}
        if(!loaded && !busy && error.isBlank())later()
    }
    fun canPinchAt(x:Float,y:Float):Boolean {
        if(!active || friend==null)return false
        val rect=android.graphics.Rect()
        return shelfGrid?.getGlobalVisibleRect(rect)==true && rect.contains(x.toInt(),y.toInt())
    }
    fun pinchColumns(scale:Double):Boolean {
        if(!active || closed || friend==null || busy)return false
        val next=(columns+if(scale>1) -1 else 1).coerceIn(2,6)
        if(next==columns)return false
        columns=next;changed();return true
    }
    private fun cover(row:LinearLayout,path:String,width:Int,height:Int){
        val image=ImageView(activity).apply {setImageDrawable(ViblioBookMarkDrawable(ui.accent,android.graphics.Color.WHITE,scale=0.48f,backgroundColor=ui.activeSurface));scaleType=ImageView.ScaleType.FIT_CENTER;background=ui.shape(ui.surface,4);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        row.addView(image,LinearLayout.LayoutParams(ui.dp(width),ui.dp(height)).apply {marginEnd=ui.dp(12)});coverLoader()?.bind(image,path)
    }
}
