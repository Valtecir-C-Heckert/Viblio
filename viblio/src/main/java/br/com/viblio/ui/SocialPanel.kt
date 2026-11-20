package br.com.viblio.ui

import android.app.*
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.*
import android.text.*
import android.view.*
import android.widget.*
import br.com.centralcasa.books.*
import br.com.centralcasa.ui.CoverBlur
import br.com.centralcasa.ui.DisplayUi
import br.com.viblio.account.*
import com.google.gson.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/** Feed, directory and club views share no private repository state with other accounts. */
class SocialPanel(private val activity:Activity,private val baseUi:DisplayUi,private val accounts:AccountManager,private val changed:()->Unit){
    var onBackdrop:()->Unit={}
    var composerDock:FrameLayout?=null
    private val ui=DisplayUi(activity)
    private val feedColors=linkedMapOf<String,Int?>()
    private var clubPage="home";private var clubFeed=emptyList<JsonObject>();private var clubFeedCursor:String?=null;private var booksQuery="";private var stageInfo=JsonObject()
    private var themeClub:String?=null;private var themeCover="";private var themeResolved=false
    private var backdropPath="";private var backdropRequest="";private var backdropBitmap:Bitmap?=null
    private val handler=Handler(Looper.getMainLooper());private val worker=Executors.newSingleThreadExecutor()
    private val backdropWorker=Executors.newSingleThreadExecutor()
    private val clubFeedWorker=Executors.newSingleThreadExecutor()
    private var requestConnection:AccountConnection?=null;private var clubFeedConnection:AccountConnection?=null
    private var api:AccountApi?=null;private var apiIdentity="";private var images:FriendCoverLoader?=null
    private var identity="";private var active=false;private var closed=false;private var generation=0;private var busy=false;private var loaded=false;private var error=""
    private var clubFeedEpoch=0;private var clubFeedBusy=false;private var coverRedrawScheduled=false
    private var tab="feed";private var feedFilter="all";private var people=false;private var inviteClub:String?=null;private var club:String?=null;private var round:String?=null;private var stage:String?=null
    private var data=JsonObject();private var rows=emptyList<JsonObject>();private var cursor:String?=null;private var messageBefore:String?=null
    private var draft="";private var replyToId:Long?=null;private var replyToUser="";private var editedAt=0L;private var scheduled=false;private var delay=30000L
    private val liveWorker=Executors.newSingleThreadExecutor()
    private val messagesWorker=Executors.newSingleThreadExecutor()
    private var liveTag:DiscussionConnection?=null;private var liveEpoch=0;private var livePath="";private var liveRetry=1000L
    private var eventsSeen=0L;private var eventsApplied=0L;private var messagesFetching=false
    private var messageList:LinearLayout?=null;private var liveStatus:TextView?=null
    private var replyBanner:LinearLayout?=null;private var replyBannerLabel:TextView?=null
    private val fetchTag=Any()
    private val liveRefresh=Runnable {refreshMessages()}
    private val liveReconnect=Runnable {startLive()}
    private val coverRedraw=Runnable {
        coverRedrawScheduled=false
        if(shouldRedrawCoverCards()){
            val scroll=activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll);val y=scroll?.scrollY ?: 0
            changed()
            scroll?.post {if(active && shouldRedrawCoverCards())scroll.scrollTo(0,y)}
        }
    }
    private val dialogs=mutableSetOf<Dialog>();private val invited=mutableSetOf<String>()
    private val friends=FriendsPanel(activity,ui,accounts,onDiscover=::discover,embedded=true){if(active && tab=="friends" && !people)changed()}
    private fun discover(){friends.hide(resetSelection=true);people=true;inviteClub=null;reset();changed()}
    val canReplaceLibrary get()=draft.isBlank() && dialogs.isEmpty() && !busy
    val displayTheme get()=if(tab=="friends" && !people)friends.displayTheme else ui
    val hideNavigation get()=active && !closed && !people && tab=="clubs" && club!=null && stage!=null
    fun screenBackground():Drawable? {
        if(!hideNavigation || themeCover.isBlank() || backdropPath!=themeCover)return null
        val bitmap=backdropBitmap?.takeIf {!it.isRecycled} ?: return null
        return ClubCoverBackdrop(bitmap,ui.background)
    }
    private fun who()=LibraryProfiles.database(activity)+":"+accounts.catalogToken().orEmpty()
    private fun JsonObject.s(k:String)=get(k)?.takeUnless {it.isJsonNull}?.runCatching {asString}?.getOrNull().orEmpty()
    private fun JsonObject.n(k:String)=get(k)?.runCatching {asLong}?.getOrNull() ?: 0L
    private fun JsonObject.y(k:String)=get(k)?.runCatching {asBoolean}?.getOrNull()==true
    private fun JsonObject.o(k:String)=get(k)?.takeIf {it.isJsonObject}?.asJsonObject ?: JsonObject()
    private fun JsonObject.list(k:String,max:Int=200)=get(k)?.takeIf {it.isJsonArray}?.asJsonArray?.take(max)?.filter {it.isJsonObject}?.map {it.asJsonObject}.orEmpty()
    private fun id(s:String)=runCatching {UUID.fromString(s).toString()}.getOrNull()
    private fun alphaColor(color:Int,alpha:Int)=Color.argb(alpha.coerceIn(0,255),Color.red(color),Color.green(color),Color.blue(color))
    private fun text(parent:LinearLayout,value:String,size:Int=13,color:Int=ui.text,bold:Boolean=false)=ui.label(parent,value,size,color,bold)
    private fun title(parent:LinearLayout,value:String,size:Int=21,color:Int=ui.text)=text(parent,value,size,color).apply {typeface=Typeface.create("serif",Typeface.NORMAL)}
    private fun line(parent:LinearLayout,color:Int=ui.lineColor,margin:Int=10){parent.addView(View(activity).apply {setBackgroundColor(color)},LinearLayout.LayoutParams(-1,ui.dp(1).coerceAtLeast(1)).apply {topMargin=ui.dp(margin);bottomMargin=ui.dp(margin)})}
    private fun card(parent:LinearLayout,tagName:String="",palette:DisplayUi=ui):LinearLayout=ui.column().apply {
        tag=tagName;setPadding(ui.dp(14),ui.dp(12),ui.dp(14),ui.dp(12));background=palette.outlined(palette.surface,palette.lineColor,0)
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(12)})
    }
    private fun stripeCard(parent:LinearLayout,tagName:String="",palette:DisplayUi=ui,stripeDp:Int=3,bgColor:Int=palette.surface,borderColor:Int=palette.lineColor,padH:Int=14,padV:Int=12):LinearLayout=ui.column().apply {
        tag=tagName;setPadding(ui.dp(padH),ui.dp(padV),ui.dp(padH),ui.dp(padV))
        background=StripeCardDrawable(bgColor,borderColor,palette.accent,ui.dp(1).coerceAtLeast(1),ui.dp(stripeDp).coerceAtLeast(1))
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(12)})
    }
    private fun section(parent:LinearLayout,label:String,palette:DisplayUi=ui){
        val row=ui.row();parent.addView(row,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(8);bottomMargin=ui.dp(10)})
        palette.label(row,label,10,palette.accent).apply {letterSpacing=0.08f;layoutParams=LinearLayout.LayoutParams(-2,-2).apply {marginEnd=ui.dp(10)}}
        row.addView(View(activity).apply {setBackgroundColor(palette.lineColor)},LinearLayout.LayoutParams(0,ui.dp(1).coerceAtLeast(1),1f))
    }
    private fun action(parent:LinearLayout,label:String,primary:Boolean=false,palette:DisplayUi=ui,onClick:()->Unit)=palette.action(parent,label,primary,onClick).apply {
        textSize=11f;minHeight=ui.dp(40);setPadding(ui.dp(12),ui.dp(7),ui.dp(12),ui.dp(7))
        if(!primary)background=palette.outlined(palette.surface,palette.lineColor,0)
        layoutParams=LinearLayout.LayoutParams(-2,-2).apply {topMargin=ui.dp(4);bottomMargin=ui.dp(4)}
    }
    private fun monogramBox(parent:LinearLayout,label:String,sizeDp:Int,palette:DisplayUi=ui,textSizeSp:Float=13f,marginEndDp:Int=10):TextView {
        val box=TextView(activity).apply {
            text=label;gravity=Gravity.CENTER;textSize=textSizeSp;setTextColor(palette.accent)
            typeface=Typeface.create("serif",Typeface.NORMAL)
            background=palette.outlined(palette.activeSurface,palette.lineColor,0)
        }
        parent.addView(box,LinearLayout.LayoutParams(ui.dp(sizeDp),ui.dp(sizeDp)).apply {marginEnd=ui.dp(marginEndDp)})
        return box
    }
    private fun clearBackdrop(){
        backdropPath="";backdropRequest=""
        backdropBitmap=null
    }
    private fun ensureBackdrop(){
        if(!hideNavigation || themeCover.isBlank())return
        val path=themeCover
        if((backdropPath==path && backdropBitmap?.isRecycled==false) || backdropRequest==path)return
        backdropRequest=path
        val profile=identity;val selectedClub=club;val selectedStage=stage
        images?.bitmap(path,eager=true){source->
            if(!active || closed || identity!=profile || who()!=profile || club!=selectedClub || stage!=selectedStage || themeCover!=path){
                if(backdropRequest==path)backdropRequest="";return@bitmap
            }
            if(source==null || source.isRecycled){if(backdropRequest==path)backdropRequest="";return@bitmap}
            val copy=runCatching {source.copy(Bitmap.Config.ARGB_8888,false)}.getOrNull() ?: run {if(backdropRequest==path)backdropRequest="";return@bitmap}
            backdropWorker.execute {
                val rendered=runCatching {CoverBlur.render(copy,0xB3000000.toInt())}.getOrNull()
                copy.recycle()
                handler.post {
                    if(backdropRequest==path)backdropRequest=""
                    if(active && !closed && identity==profile && who()==profile && club==selectedClub && stage==selectedStage && themeCover==path && rendered!=null){
                        backdropBitmap=rendered;backdropPath=path
                        onBackdrop()
                    }else rendered?.recycle()
                }
            }
        }
    }
    private fun updateTheme(){
        if(club==null){
            themeClub=null;themeCover="";themeResolved=false;clearBackdrop()
            ui.inheritReadingPalette(baseUi);return
        }
        if(themeClub!=club){themeClub=club;themeCover="";themeResolved=false;clearBackdrop();ui.scopedReadingColor(null)}
        if(data.s("id")==club){
            if(data.o("round").s("state")=="voting"){
                if(themeCover.isNotEmpty()){themeCover="";themeResolved=false;clearBackdrop()}
                ui.inheritReadingPalette(baseUi);return
            }
            val selected=data.list("proposals",30).firstOrNull {it.s("id")==data.o("round").s("selected_id")}
            val cover=selected?.s("cover").orEmpty()
            if(cover!=themeCover){themeCover=cover;themeResolved=false;clearBackdrop();ui.scopedReadingColor(null)}
        }
        if(themeCover.isNotBlank() && !themeResolved){
            val path=themeCover;val selectedClub=club;val profile=identity
            images?.color(path,eager=true){color->
                if(active && who()==profile && identity==profile && club==selectedClub && themeCover==path){
                    themeResolved=true
                    if(ui.scopedReadingColor(color) && (stage==null || composer?.hasFocus()!=true))changed()
                }
            }
        }
        if(stage!=null && themeCover.isNotBlank())ensureBackdrop()
    }
    private fun date(ms:Long,pattern:String="dd/MM 'às' HH'h'mm")=SimpleDateFormat(pattern,Locale("pt","BR")).format(Date(ms))
    private fun shortDate(ms:Long)=SimpleDateFormat("dd MMM",Locale("pt","BR")).format(Date(ms)).replace(".","")
    private fun instant(value:String)=runCatching {SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",Locale.US).apply {timeZone=TimeZone.getTimeZone("UTC")}.parse(value.take(19))!!.time}.getOrDefault(0L)
    private fun iso(ms:Long)=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US).apply {timeZone=TimeZone.getTimeZone("UTC")}.format(Date(ms))
    private fun duration(ms:Long):String {val minutes=ms.coerceAtLeast(0)/60000;return if(minutes>=60)"${minutes/60}h${(minutes%60).toString().padStart(2,'0')}min" else if(minutes>0)"$minutes min" else "${ms.coerceAtLeast(0)/1000} s"}
    private fun tracked(d:Dialog){dialogs.add(d);d.setOnDismissListener {dialogs.remove(d)};d.show();d.window?.setBackgroundDrawable(ui.shape(ui.surface,0))}
    private val tick=Runnable {scheduled=false;if(active && !closed && tab!="friends" && !people && clubPage!="books" && !busy && loaded && dialogs.isEmpty() && stage==null && SystemClock.elapsedRealtime()-editedAt>=3000){load(poll=true)};arm()}
    private fun arm(){if(active && !closed && !scheduled){scheduled=true;handler.postDelayed(tick,delay)}}
    private fun reset(preserveClub:Boolean=false){stopLive();generation++;clubFeedEpoch++;requestConnection?.cancel();requestConnection=null;clubFeedConnection?.cancel();clubFeedConnection=null;clubFeedBusy=false;busy=false;loaded=false;error="";rows=emptyList();cursor=null;messageBefore=null;if(!preserveClub){data=JsonObject();clubFeed=emptyList();clubFeedCursor=null};if(images==null)images=api?.let {FriendCoverLoader(it::thumbnail)}}
    fun hide(resetSelection:Boolean=false){active=false;stopLive();clearBackdrop();generation++;clubFeedEpoch++;requestConnection?.cancel();requestConnection=null;clubFeedConnection?.cancel();clubFeedConnection=null;clubFeedBusy=false;busy=false;loaded=false;scheduled=false;coverRedrawScheduled=false;handler.removeCallbacksAndMessages(null);api?.cancel();images?.close();images=null;feedColors.clear();dialogs.toList().forEach {it.dismiss()};dialogs.clear();rows=emptyList();data=JsonObject();identity="";friends.hide(resetSelection);if(resetSelection){club=null;stage=null;round=null;clubPage="home";people=false;inviteClub=null;draft="";replyToId=null;replyToUser="";invited.clear()}}
    fun close(){hide();closed=true;api?.close();api=null;apiIdentity="";friends.close();worker.shutdownNow();liveWorker.shutdownNow();messagesWorker.shutdownNow();backdropWorker.shutdownNow();clubFeedWorker.shutdownNow()}
    private fun draftOwner()=java.security.MessageDigest.getInstance("SHA-256").digest(who().toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun save(out:Bundle){friends.save(out);out.putString("social.tab",tab);out.putString("social.club",club);out.putString("social.round",round);out.putString("social.stage",stage);out.putString("social.clubPage",clubPage);out.putString("social.draft",draft.take(2000));out.putString("social.draftOwner",draftOwner())}
    fun restore(state:Bundle?){friends.restore(state);tab=state?.getString("social.tab").takeIf {it in listOf("feed","friends","clubs")} ?: "feed";club=state?.getString("social.club")?.let(::id);round=state?.getString("social.round")?.let(::id);stage=state?.getString("social.stage")?.let(::id);clubPage=state?.getString("social.clubPage").takeIf {it in listOf("home","history","settings","members")} ?: "home";draft=if(state?.getString("social.draftOwner")==draftOwner())state.getString("social.draft").orEmpty().take(2000) else "";replyToId=null;replyToUser=""}
    fun back():Boolean {if(stage!=null && draft.isNotBlank()){confirm("Descartar comentário não publicado?"){draft="";replyToId=null;replyToUser="";back()};return true};when {people->{people=false;inviteClub=null};stage!=null->{stage=null;draft="";replyToId=null;replyToUser=""};club!=null && clubPage!="home"->{clubPage="home"};club!=null && round!=null->{round=null;clubPage="history"};tab=="friends"->return friends.back();club!=null->{club=null;round=null;clubPage="home"};else->return false};reset(preserveClub=club!=null);changed();scrollTop();return true}
    fun canPinchAt(x:Float,y:Float)=tab=="friends" && !people && friends.canPinchAt(x,y)
    fun pinchColumns(scale:Double)=tab=="friends" && !people && friends.pinchColumns(scale)
    private fun request(path:String,method:String="GET",body:JsonObject?=null,done:(JsonObject)->Unit){
        if(closed || !active || busy || who()!=identity)return
        val client=api ?: return;val epoch=++generation;val profile=identity;val connection=AccountConnection();requestConnection=connection;busy=true;error="";if(stage==null)changed() else discussionControls()
        worker.execute {val result=runCatching {SocialRequests.call(client,path,method,body,connection)};handler.post {
            if(closed || !active || generation!=epoch || identity!=profile || who()!=profile)return@post
            busy=false;requestConnection=null
            result.fold({delay=30000;done(it)},{failure->
                delay=(delay*2).coerceIn(60000,300000);error=if(failure is AccountFailure && failure.code==404 && (path=="/v1/clubs" || path.startsWith("/v1/friends/feed") || path.startsWith("/v1/friends/people")))"Atualize a API para usar Feed, Pessoas e Clubes." else SocialRequests.message(failure,accounts.address)
                if(method=="GET" && failure is AccountFailure && failure.code in listOf(401,403,404)){rows=emptyList();data=JsonObject();dialogs.toList().forEach {it.dismiss()}}
                loaded=true;changed()
            })
        }}
    }
    private fun mutate(path:String,method:String="POST",body:JsonObject?=null,after:()->Unit={}){
        request(path,method,body){
            after()
            if(stage!=null && path.contains("/messages")){
                composer?.takeIf {draft.isEmpty()}?.setText("")
                updateReplyBanner()
                eventsSeen++;discussionControls();refreshMessages()
            }else {loaded=false;load()}
        }
    }
    private fun enc(v:String)=java.net.URLEncoder.encode(v,"UTF-8")
    private fun load(more:Boolean=false,poll:Boolean=false){
        if(tab=="friends" && !people)return
        if(club!=null && !people && stage!=null && data.s("id").isBlank()){
            request("/v1/clubs/$club"+(round?.let {"?round_id=${enc(it)}"} ?: "")){response->data=response;stageInfo=data.list("stages",20).firstOrNull {it.s("id")==stage} ?: JsonObject();load()};return
        }
        val path=when {people->"/v1/friends/people"+(if(more && cursor!=null)"?cursor=${enc(cursor!!)}" else "");tab=="feed"->"/v1/friends/feed"+(if(more && cursor!=null)"?cursor=${enc(cursor!!)}" else "");club==null->"/v1/clubs";clubPage=="books"->"/v1/clubs/$club/books?q=${enc(booksQuery)}"+(if(more && cursor!=null)"&cursor=${enc(cursor!!)}" else "");stage!=null->"/v1/clubs/$club/stages/$stage/messages"+(messageBefore?.let {"?before=${enc(it)}"} ?: "");else->"/v1/clubs/$club"+(round?.let {"?round_id=${enc(it)}"} ?: "")}
        request(path){response->
            val scroll=activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll);val position=scroll?.scrollY ?: 0
            if(tab=="feed" && !people){
                val allowed=response.getAsJsonArray("visible_actor_ids")?.mapNotNull {it.runCatching {asString}.getOrNull()}?.toSet().orEmpty()
                if(poll && position>ui.dp(50)){rows=rows.filter {it.o("actor").s("id") in allowed}}
                else {rows=(if(more)rows+response.list("items",40) else response.list("items",40)).distinctBy {it.s("id")}.take(400);cursor=response.s("next_cursor").ifBlank {null}}
            }else if(people || club==null || clubPage=="books"){rows=(if(more)rows+response.list("items",50) else response.list("items",50)).distinctBy {if(clubPage=="books" && !people)it.o("owner").s("id")+":"+it.s("id") else it.s("id")}.take(400);cursor=response.s("next_cursor").ifBlank {null}}
            else if(stage!=null){rows=response.list("items",40);cursor=response.s("next_cursor").ifBlank {null};if(response.has("stage"))stageInfo=response.o("stage")}
            else {if(data.o("round").s("id")!=response.o("round").s("id")){clubFeed=emptyList();clubFeedCursor=null};data=response}
            loaded=true;changed();if(club!=null && !people && stage==null && clubPage=="home")loadClubFeed(poll=poll);if(poll)scroll?.post {if(active)scroll.scrollTo(0,position)}
        }
    }
    private fun loadClubFeed(more:Boolean=false,poll:Boolean=false){
        val currentClub=club ?: return
        val roundId=data.o("round").s("id")
        if(roundId.isBlank() || data.o("round").s("state")=="voting" || clubFeedBusy || closed || !active || who()!=identity)return
        val client=api ?: return
        val epoch=++clubFeedEpoch;val profile=identity
        val connection=AccountConnection();clubFeedConnection?.cancel();clubFeedConnection=connection;clubFeedBusy=true
        val path="/v1/clubs/$currentClub/feed?round_id=${enc(roundId)}"+(if(more && clubFeedCursor!=null)"&cursor=${enc(clubFeedCursor!!)}" else "")
        clubFeedWorker.execute {
            val result=runCatching {SocialRequests.call(client,path,"GET",null,connection)}
            handler.post {
                if(clubFeedEpoch==epoch){clubFeedBusy=false;clubFeedConnection=null}
                if(closed || !active || clubFeedEpoch!=epoch || identity!=profile || who()!=profile || club!=currentClub || stage!=null || clubPage!="home")return@post
                result.onSuccess {response->
                    val scroll=activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll);val position=scroll?.scrollY ?: 0
                    clubFeed=(if(more)clubFeed+response.list("items",40) else if(poll && position>ui.dp(50))clubFeed else response.list("items",40)).distinctBy {it.s("id")}.take(200)
                    if(!poll || position<=ui.dp(50))clubFeedCursor=response.s("next_cursor").ifBlank {null}
                    changed()
                    scroll?.post {if(active && club==currentClub && stage==null && clubPage=="home")scroll.scrollTo(0,position)}
                }
            }
        }
    }
    fun render(parent:LinearLayout){
        if(closed)return
        active=true
        val currentWho=who()
        if(identity!=currentWho) {
            if(identity.isNotBlank()){dialogs.toList().forEach {it.dismiss()};friends.hide(resetSelection=true);club=null;round=null;stage=null;people=false;inviteClub=null;draft="";replyToId=null;replyToUser="";invited.clear()}
            reset()
            identity=currentWho
            if(api==null || apiIdentity!=currentWho){api?.close();api=AccountApi(accounts.address,accounts.catalogToken().orEmpty());apiIdentity=currentWho;images?.close();images=null}
            if(images==null)images=FriendCoverLoader(api!!::thumbnail)
        }else if(images==null && api!=null){
            images=FriendCoverLoader(api!!::thumbnail)
        }
        if(!accounts.signedIn){title(parent,"AMIGOS",30);text(parent,"Entre para ver seus amigos.",14,ui.muted);return}
        updateTheme()
        arm()
        if(tab=="friends" && !people && friends.inDetail){friends.render(parent);return}
        if(tab!="friends" || people)images?.attach(parent)
        if(club!=null && stage!=null && !people){
            renderDiscussionHeader(parent)
        }else {
            val header=ui.row().apply {gravity=Gravity.CENTER_VERTICAL};parent.addView(header,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(14)})
            if(people || club!=null)ui.icon(header,"back","Voltar",40){back()}.apply {layoutParams=LinearLayout.LayoutParams(ui.dp(40),ui.dp(40)).apply {marginEnd=ui.dp(4)}}
            title(header,if(people)"ADICIONAR PESSOAS" else if(club!=null)data.s("name").ifBlank {"CLUBE"} else "AMIGOS",if(club!=null)25 else 28).apply {
                maxLines=1;ellipsize=TextUtils.TruncateAt.END
                layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(8)}
            }
            if(tab=="clubs" && club==null && !people){
                ui.icon(header,"plus","Criar clube",44){createClub()}.apply {
                    tag="club-create";background=ui.outlined(ui.surface,ui.lineColor,0)
                    setPadding(ui.dp(10),ui.dp(10),ui.dp(10),ui.dp(10))
                    layoutParams=LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)).apply {marginEnd=ui.dp(6)}
                }
            } else if(tab=="friends" && !people){
                ui.icon(header,"plus","Adicionar amigo",44){discover()}.apply {
                    tag="friends-discover";background=ui.outlined(ui.surface,ui.lineColor,0)
                    setPadding(ui.dp(10),ui.dp(10),ui.dp(10),ui.dp(10))
                    layoutParams=LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)).apply {marginEnd=ui.dp(6)}
                }
            }
            SocialRefresh.button(activity,ui,header,if(tab=="friends" && !people)friends.isLoading else busy,"Atualizar"){if(tab=="friends" && !people)friends.refresh()else if(stage!=null){eventsSeen++;refreshMessages()}else {load()}}
            if(club!=null && !people)ui.icon(header,"more","Opções do clube",44){clubOptions()}.apply {
                tag="club-options";background=ui.outlined(ui.surface,ui.lineColor,0)
                setPadding(ui.dp(10),ui.dp(10),ui.dp(10),ui.dp(10))
                layoutParams=LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)).apply {marginStart=ui.dp(6)}
            }
        }
        if(!people && club==null){
            val tabs=ui.row();parent.addView(tabs,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(4)})
            listOf("feed" to "Feed","clubs" to "Clubes","friends" to "Meus amigos").forEach {(key,label)->
                val cell=ui.column().apply {tag="social-tab:$key";isSelected=tab==key;isFocusable=true;setOnClickListener {friends.hide(resetSelection=true);tab=key;club=null;stage=null;round=null;clubPage="home";draft="";replyToId=null;replyToUser="";reset();if(key=="friends")changed() else load()}}
                tabs.addView(cell,LinearLayout.LayoutParams(0,-2,1f));text(cell,label,12,if(tab==key)ui.accent else ui.muted).apply {gravity=Gravity.CENTER;minHeight=ui.dp(40)}
                cell.addView(View(activity).apply {setBackgroundColor(if(tab==key)ui.accent else ui.lineColor)},LinearLayout.LayoutParams(-1,ui.dp(if(tab==key)2 else 1)))
            }
        }
        if(tab=="friends" && !people){friends.render(parent);return}
        if(error.isNotBlank() && stage==null){text(parent,error,13,ui.muted);action(parent,"Tentar novamente"){load()}}
        val body=ui.column();parent.addView(body)
        when {people->peopleView(body);tab=="feed"->feedView(body);club==null->clubsView(body);clubPage=="books"->clubBooks(body);stage!=null->discussionView(body);else->clubView(body)}
        if(!loaded && !busy && error.isBlank())handler.post {if(active && !loaded && !busy)load()}
    }
    private fun renderDiscussionHeader(parent:LinearLayout){
        val header=ui.row().apply {
            gravity=Gravity.CENTER_VERTICAL
            setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(6))
            background=ui.outlined(alphaColor(ui.background,204),alphaColor(ui.lineColor,190),0)
        }
        parent.addView(header,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(8)})
        ui.icon(header,"back","Voltar",40){back()}.apply {layoutParams=LinearLayout.LayoutParams(ui.dp(40),ui.dp(40)).apply {marginEnd=ui.dp(4)}}
        val selected=data.list("proposals",30).firstOrNull {it.s("id")==data.o("round").s("selected_id")}
        val cover=selected?.s("cover").orEmpty()
        if(cover.isNotBlank()){
            val mini=ImageView(activity).apply {scaleType=ImageView.ScaleType.CENTER_CROP;background=ui.outlined(ui.activeSurface,ui.lineColor,0)}
            header.addView(mini,LinearLayout.LayoutParams(ui.dp(28),ui.dp(42)).apply {marginEnd=ui.dp(10)})
            images?.bind(mini,cover)
        }
        val stages=data.list("stages",20)
        val stageIndex=(stages.indexOfFirst {it.s("id")==stage}.takeIf {it>=0} ?: 0)+1
        val meta=ui.column();header.addView(meta,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(6)})
        title(meta,selected?.s("title")?.ifBlank {null} ?: data.s("name").ifBlank {"Conversa"},17).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END}
        val limitLabel=if(stageInfo.n("page_limit")>0)"Etapa $stageIndex · até pág. ${stageInfo.n("page_limit")}" else data.s("name")
        text(meta,limitLabel,11,ui.accent).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END}
        SocialRefresh.button(activity,ui,header,busy,"Atualizar"){eventsSeen++;refreshMessages()}
        ui.icon(header,"more","Opções do clube",44){clubOptions()}.apply {
            tag="club-options";background=ui.outlined(ui.surface,ui.lineColor,0)
            setPadding(ui.dp(10),ui.dp(10),ui.dp(10),ui.dp(10))
            layoutParams=LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)).apply {marginStart=ui.dp(6)}
        }
    }
    private fun picture(parent:LinearLayout,path:String,width:Int=55,height:Int=82,palette:DisplayUi=ui,marginEndDp:Int=12):ImageView {
        val image=ImageView(activity).apply {scaleType=ImageView.ScaleType.FIT_CENTER;background=palette.outlined(palette.activeSurface,palette.lineColor,0);setImageDrawable(palette.symbol("books",palette.muted,28))}
        parent.addView(image,LinearLayout.LayoutParams(ui.dp(width),ui.dp(height)).apply {marginEnd=ui.dp(marginEndDp)});images?.bind(image,path);return image
    }
    private fun consult(book:JsonObject,owner:String){
        val client=api ?: return;val loader=FriendCoverLoader(client::thumbnail)
        val dialog=BookInfoDialog.show(activity,ui,BookInfoDialog.Info(book.s("title"),book.s("author"),book.s("publisher"),book.s("genre"),book.n("pages").takeIf {it>0}?.toInt(),page=book.n("page").toInt(),owner=owner),bind={image,root->loader.attach(root);loader.bind(image,book.s("cover"))},onClose={loader.close()})
        dialogs.add(dialog);dialog.setOnDismissListener {loader.close();dialogs.remove(dialog)}
    }
    private fun shouldRedrawCoverCards():Boolean {
        if(!active || people || stage!=null)return false
        if(tab=="feed")return true
        if(tab=="clubs"){
            if(club==null)return true
            if(clubPage=="history")return true
            if(clubPage=="home" && data.o("round").s("state")=="voting")return true
        }
        return false
    }
    private fun coverPalette(path:String,fallbackToBase:Boolean=false):DisplayUi {
        val palette=DisplayUi(activity).apply {
            if(feedColors.containsKey(path))scopedReadingColor(feedColors[path])
            else if(fallbackToBase)inheritReadingPalette(baseUi)
            else scopedReadingColor(null)
        }
        if(path.isNotBlank() && !feedColors.containsKey(path)){
            val profile=identity
            images?.color(path){color->if(active && who()==profile && identity==profile){
                if(feedColors.size>=400)feedColors.remove(feedColors.keys.first())
                feedColors[path]=color
                if(shouldRedrawCoverCards() && !coverRedrawScheduled){
                    coverRedrawScheduled=true
                    handler.postDelayed(coverRedraw,60)
                }
            }}
        }
        return palette
    }
    private fun feedView(parent:LinearLayout){
        // Quick filter chips row (no redundant "Lendo agora" strip — already in Meus amigos).
        val filterScroll=HorizontalScrollView(activity).apply {isHorizontalScrollBarEnabled=false}
        val filterRow=ui.row();filterScroll.addView(filterRow)
        parent.addView(filterScroll,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(6);bottomMargin=ui.dp(4)})
        listOf("all" to "Tudo","read" to "Leituras","finished" to "Concluídos","rated" to "Notas","added" to "Adicionados").forEach {(key,label)->
            val on=feedFilter==key
            val chip=TextView(activity).apply {
                tag="feed-filter:$key";text=label;textSize=11f;gravity=Gravity.CENTER;maxLines=1
                setTextColor(if(on)ui.accent else ui.muted)
                typeface=Typeface.create(if(on)"sans-serif-medium" else "sans-serif",Typeface.NORMAL)
                background=ui.outlined(if(on)alphaColor(ui.accent,30) else ui.surface,if(on)ui.accent else ui.lineColor,0)
                setPadding(ui.dp(11),ui.dp(6),ui.dp(11),ui.dp(6))
                setOnClickListener {if(feedFilter!=key){feedFilter=key;changed()}}
            }
            filterRow.addView(chip,LinearLayout.LayoutParams(-2,-2).apply {marginEnd=ui.dp(6)})
        }

        val consolidated=FeedGrouping.consolidate(rows)
        val shown=consolidated.filter {FeedGrouping.matchesFilter(it,feedFilter)}
        val today=ReadingDays.at(System.currentTimeMillis())
        val yesterday=ReadingDays.at(System.currentTimeMillis()-86400000L)
        var day=""
        shown.forEach {entry->
            if(entry.dayKey!=day){
                day=entry.dayKey
                section(parent,when(day){today->"HOJE";yesterday->"ONTEM";else->ReadingDays.label(day).uppercase()})
            }
            val book=entry.book
            val palette=coverPalette(book.s("cover"))
            val bgTint=alphaColor(palette.surface,235)
            val card=stripeCard(parent,"feed-event:${entry.id}",palette,stripeDp=3,bgColor=bgTint,borderColor=palette.lineColor,padH=10,padV=8).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin=ui.dp(8)
            }
            val row=ui.row().apply {gravity=Gravity.CENTER_VERTICAL}
            card.addView(row,LinearLayout.LayoutParams(-1,-2))

            // Compact 2:3 cover on the left (42x63dp).
            picture(row,book.s("cover"),42,63,palette,marginEndDp=10)

            // 3 compact aligned lines on the right (~55% less vertical height than before).
            val col=ui.column()
            row.addView(col,LinearLayout.LayoutParams(0,-2,1f))

            // Row 1: micro-avatar + @username (tap opens friend shelf) + action badge + time.
            val r1=ui.row().apply {gravity=Gravity.CENTER_VERTICAL}
            col.addView(r1,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(3)})
            val userTap=ui.row().apply {
                gravity=Gravity.CENTER_VERTICAL
                isFocusable=true
                setOnClickListener {
                    if(entry.actorId.isNotBlank()){
                        tab="friends";people=false;club=null;stage=null;round=null
                        friends.openFriend(entry.actorId,entry.actorUsername)
                        changed()
                    }
                }
            }
            r1.addView(userTap,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(6)})
            val microAv=TextView(activity).apply {
                text=entry.actorUsername.take(1).uppercase();gravity=Gravity.CENTER;textSize=9f
                setTextColor(palette.accent);typeface=Typeface.create("serif",Typeface.NORMAL)
                background=android.graphics.drawable.GradientDrawable().apply {
                    shape=android.graphics.drawable.GradientDrawable.OVAL
                    setColor(palette.activeSurface)
                    setStroke(ui.dp(1).coerceAtLeast(1),palette.lineColor)
                }
            }
            userTap.addView(microAv,LinearLayout.LayoutParams(ui.dp(18),ui.dp(18)).apply {marginEnd=ui.dp(5)})
            palette.label(userTap,"@${entry.actorUsername}",11,palette.text).apply {
                typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                maxLines=1;ellipsize=TextUtils.TruncateAt.END
                layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            }
            val badge=TextView(activity).apply {
                text=FeedGrouping.actionBadge(entry).uppercase();textSize=8.5f;maxLines=1
                setTextColor(palette.accent);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                background=palette.outlined(alphaColor(palette.accent,34),alphaColor(palette.accent,95),0)
                setPadding(ui.dp(5),ui.dp(2),ui.dp(5),ui.dp(2))
            }
            r1.addView(badge,LinearLayout.LayoutParams(-2,-2).apply {marginEnd=ui.dp(6)})
            palette.label(r1,date(entry.occurredAt,"HH'h'mm"),9,palette.muted).apply {
                maxLines=1
                (layoutParams as LinearLayout.LayoutParams).bottomMargin=0
            }

            // Row 2: book title (serif) + muted author on 1 compact line.
            val titleRow=ui.row().apply {gravity=Gravity.BOTTOM}
            col.addView(titleRow,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(4)})
            palette.label(titleRow,book.s("title"),15,palette.text).apply {
                typeface=Typeface.create("serif",Typeface.NORMAL)
                maxLines=1;ellipsize=TextUtils.TruncateAt.END
                layoutParams=LinearLayout.LayoutParams(-2,-2).apply {marginEnd=ui.dp(6)}
            }
            val author=book.s("author")
            if(author.isNotBlank()){
                palette.label(titleRow,author,10,palette.muted).apply {
                    maxLines=1;ellipsize=TextUtils.TruncateAt.END
                    layoutParams=LinearLayout.LayoutParams(0,-2,1f)
                }
            }

            // Row 3: compact metric (stars / duration / page span) + inline thin progress bar.
            val r3=ui.row().apply {gravity=Gravity.CENTER_VERTICAL}
            col.addView(r3,LinearLayout.LayoutParams(-1,-2))
            val primaryMetric=when {
                entry.rating!=null -> FeedGrouping.stars(entry.rating)
                entry.durationMs>0L -> duration(entry.durationMs)
                entry.totalDurationMs>0L -> duration(entry.totalDurationMs)
                entry.hasStarted -> "Iniciou leitura"
                entry.hasAdded -> "Adicionou à estante"
                else -> ""
            }
            val secondaryMetric=when {
                entry.rating!=null && entry.totalDurationMs>0L -> duration(entry.totalDurationMs)
                entry.rating!=null -> FeedGrouping.formatRating(entry.rating)
                entry.pagesKnown && entry.startPage!=null && entry.endPage!=null && entry.endPage>entry.startPage -> {
                    val sess=if(entry.sessionCount>1)" · ${entry.sessionCount}x" else ""
                    "${entry.startPage}→${entry.endPage}$sess"
                }
                entry.pagesKnown && entry.pagesRead>0L -> "${entry.pagesRead} págs"
                entry.primaryType=="added" -> listOfNotNull(
                    book.s("genre").takeIf {it.isNotBlank()},
                    book.n("pages").takeIf {it>0L}?.let {"$it págs"},
                ).joinToString(" · ")
                else -> ""
            }
            if(primaryMetric.isNotBlank()){
                palette.label(r3,primaryMetric,11,palette.accent,bold=true).apply {
                    maxLines=1
                    layoutParams=LinearLayout.LayoutParams(-2,-2).apply {marginEnd=ui.dp(6)}
                }
            }
            if(secondaryMetric.isNotBlank()){
                palette.label(r3,secondaryMetric,10,palette.muted).apply {
                    maxLines=1;ellipsize=TextUtils.TruncateAt.END
                    layoutParams=LinearLayout.LayoutParams(-2,-2).apply {marginEnd=ui.dp(8)}
                }
            }
            val pct=FeedGrouping.progressPercent(entry)
            if(pct!=null){
                val barWrap=FrameLayout(activity).apply {setBackgroundColor(alphaColor(palette.lineColor,140))}
                val barFill=View(activity).apply {setBackgroundColor(palette.accent)}
                val fillW=pct.coerceIn(0,100)
                val barRow=ui.row()
                barRow.addView(barFill,LinearLayout.LayoutParams(0,-1,fillW.toFloat().coerceAtLeast(1f)))
                if(fillW<100)barRow.addView(View(activity),LinearLayout.LayoutParams(0,-1,(100-fillW).toFloat()))
                barWrap.addView(barRow,FrameLayout.LayoutParams(-1,-1))
                r3.addView(barWrap,LinearLayout.LayoutParams(0,ui.dp(3).coerceAtLeast(3),1f).apply {marginEnd=ui.dp(6)})
                palette.label(r3,"$pct%",9,palette.muted).apply {
                    maxLines=1
                    layoutParams=LinearLayout.LayoutParams(-2,-2)
                }
            }

            card.setOnClickListener {consult(book,entry.actorUsername)}
            card.isFocusable=true
        }
        if(loaded && !busy && shown.isEmpty() && error.isBlank()){
            val emptyMsg=if(consolidated.isEmpty())"Nenhuma atividade dos seus amigos." else "Nenhuma atividade neste filtro."
            text(parent,emptyMsg,13,ui.muted)
        }
        if(cursor!=null && rows.size<400)action(parent,"Carregar mais"){load(more=true)}
    }
    private fun peopleView(parent:LinearLayout){
        rows.forEach {person->val personId=id(person.s("id")) ?: return@forEach
            val row=ui.row();parent.addView(row);title(row,"@${person.s("username")}",18).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            val relation=person.s("relationship");val sent=personId in invited || data.list("members",100).any {it.s("id")==personId}
            val label=if(inviteClub!=null)if(sent)"Convidado" else "Convidar" else when(relation){"accepted"->"Amigo";"outgoing"->"Pedido enviado";"incoming"->"Aceitar";else->"Adicionar"}
            action(row,label){
                if(inviteClub!=null)mutate("/v1/clubs/$inviteClub/invite",body=JsonObject().apply {addProperty("user_id",personId)}){invited.add(personId)}
                else if(relation=="incoming")mutate("/v1/friends/$personId/accept")
                else mutate("/v1/friends/requests",body=JsonObject().apply {addProperty("username",person.s("username"))})
            }.apply {tag="person-add:$personId";isEnabled=!busy && if(inviteClub!=null)!sent else relation !in listOf("accepted","outgoing");layoutParams=LinearLayout.LayoutParams(-2,-2)};line(parent)
        }
        if(loaded && !busy && rows.isEmpty() && error.isBlank())text(parent,"Nenhuma pessoa encontrada.",13,ui.muted)
        if(cursor!=null && rows.size<400)action(parent,"Mais pessoas"){load(more=true)}
    }
    private fun input(parent:LinearLayout,hint:String,value:String="",numeric:Boolean=false,limit:Int=2000)=EditText(activity).apply {this.hint=hint;setText(value);setTextColor(ui.text);setHintTextColor(ui.muted);isSingleLine=numeric;inputType=if(numeric)android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;filters=arrayOf(InputFilter.LengthFilter(limit));parent.addView(this,LinearLayout.LayoutParams(-1,-2))}
    private fun confirm(title:String,action:()->Unit){tracked(AlertDialog.Builder(activity).setTitle(title).setPositiveButton("Confirmar"){_,_->if(active && who()==identity)action()}.setNegativeButton("Cancelar",null).create())}
    private fun createClub(){val body=ui.column().apply {setPadding(ui.dp(20),0,ui.dp(20),0)};val name=input(body,"Nome do clube",limit=80);tracked(AlertDialog.Builder(activity).setTitle("Novo clube").setView(body).setPositiveButton("Criar"){_,_->request("/v1/clubs","POST",JsonObject().apply {addProperty("name",name.text.toString().trim())}){club=id(it.s("id"));reset();load()}}.setNegativeButton("Cancelar",null).create())}
    private fun openClub(identifier:String){
        club=identifier;round=null;stage=null;clubPage="home";draft="";replyToId=null;replyToUser=""
        reset();load();scrollTop()
    }
    private fun clubsView(parent:LinearLayout){
        val activeClubs=rows.filter {it.s("status")!="invited"}
        val invites=rows.filter {it.s("status")=="invited"}
        if(activeClubs.isNotEmpty() || invites.isEmpty())section(parent,"SEUS CLUBES")
        activeClubs.forEach {entry->
            val identifier=id(entry.s("id")) ?: return@forEach
            val book=entry.o("book");val bookTitle=book.s("title");val cover=book.s("cover")
            val state=entry.s("state")
            val isVoting=state=="voting" || (state!="reading" && bookTitle.isBlank())
            val palette=if(!isVoting && cover.isNotBlank())coverPalette(cover,fallbackToBase=true) else ui
            val block=if(!isVoting)stripeCard(parent,"club-card:$identifier",palette,padH=0,padV=0)
            else ui.column().apply {
                tag="club-card:$identifier";background=palette.outlined(palette.surface,palette.lineColor,0)
                parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(12)})
            }
            block.isFocusable=true;block.setOnClickListener {openClub(identifier)}

            val top=ui.row().apply {gravity=Gravity.TOP;setPadding(ui.dp(14),ui.dp(12),ui.dp(14),ui.dp(10))}
            block.addView(top,LinearLayout.LayoutParams(-1,-2))
            if(!isVoting){
                picture(top,cover,48,72,palette,marginEndDp=12)
            }else {
                val frame=TextView(activity).apply {
                    text="VOTOS";gravity=Gravity.CENTER;textSize=9f;letterSpacing=0.10f
                    setTextColor(palette.accent);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                    background=palette.outlined(palette.activeSurface,palette.lineColor,0)
                }
                top.addView(frame,LinearLayout.LayoutParams(ui.dp(48),ui.dp(72)).apply {marginEnd=ui.dp(12)})
            }
            val info=ui.column();top.addView(info,LinearLayout.LayoutParams(0,-2,1f))
            val titleRow=ui.row().apply {gravity=Gravity.CENTER_VERTICAL};info.addView(titleRow,LinearLayout.LayoutParams(-1,-2))
            palette.label(titleRow,entry.s("name"),20,palette.text).apply {
                typeface=Typeface.create("serif",Typeface.NORMAL);maxLines=1;ellipsize=TextUtils.TruncateAt.END
                layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(8)}
            }
            val roleBadge=TextView(activity).apply {
                text=if(entry.s("role")=="admin")"Admin" else "Membro";textSize=9f;setTextColor(palette.muted)
                setPadding(ui.dp(6),ui.dp(2),ui.dp(6),ui.dp(2));background=palette.outlined(palette.activeSurface,palette.lineColor,0)
            }
            titleRow.addView(roleBadge,LinearLayout.LayoutParams(-2,-2))

            if(!isVoting && bookTitle.isNotBlank()){
                palette.label(info,bookTitle,13,palette.accent,bold=true).apply {
                    maxLines=1;ellipsize=TextUtils.TruncateAt.END
                    layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(3);bottomMargin=ui.dp(2)}
                }
                val parts=mutableListOf<String>()
                val membersCount=entry.n("member_count")
                if(membersCount>0)parts.add(ClubFormatting.countLabel(membersCount,"participante","participantes"))
                val dueAt=entry.s("due_at")
                if(dueAt.isNotBlank()){val ms=instant(dueAt);if(ms>0)parts.add("Prazo ${shortDate(ms)}")}
                if(parts.isNotEmpty())palette.label(info,parts.joinToString(" · "),10,palette.muted).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END}
            }else {
                palette.label(info,"Escolhendo a próxima leitura",12,palette.accent).apply {
                    layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(3);bottomMargin=ui.dp(2)}
                }
                val voting=entry.o("voting");val proposals=voting.n("proposals");val votes=voting.n("votes")
                val parts=mutableListOf<String>()
                if(proposals>0)parts.add(ClubFormatting.countLabel(proposals,"indicação","indicações"))
                if(votes>0)parts.add(ClubFormatting.countLabel(votes,"voto","votos"))
                else if(entry.n("member_count")>0)parts.add(ClubFormatting.countLabel(entry.n("member_count"),"participante","participantes"))
                if(parts.isNotEmpty())palette.label(info,parts.joinToString(" · "),10,palette.muted).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END}
            }

            block.addView(View(activity).apply {setBackgroundColor(palette.lineColor)},LinearLayout.LayoutParams(-1,ui.dp(1).coerceAtLeast(1)))
            val footer=ui.row().apply {
                gravity=Gravity.CENTER_VERTICAL
                setPadding(ui.dp(14),ui.dp(8),ui.dp(14),ui.dp(8))
                setBackgroundColor(alphaColor(palette.activeSurface,110))
            }
            block.addView(footer,LinearLayout.LayoutParams(-1,-2))
            val stageObj=entry.o("stage")
            val leftStatus=when {
                isVoting->"Escolha aberta ao grupo"
                stageObj.n("page_limit")>0->"Etapa ${stageObj.n("index").coerceAtLeast(1)} · até pág. ${stageObj.n("page_limit")}"
                state=="completed"->"Leitura concluída"
                else->"Leitura em andamento"
            }
            palette.label(footer,leftStatus,10,palette.muted).apply {
                maxLines=1;ellipsize=TextUtils.TruncateAt.END
                layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(8)}
            }
            val unread=entry.n("unread_count")
            val ctaText=when {
                !isVoting && unread>0->"${ClubFormatting.countLabel(unread,"nova","novas")} →"
                isVoting->"Votar →"
                else->"Abrir →"
            }
            TextView(activity).apply {
                text=ctaText;textSize=11f;setTextColor(palette.accent)
                typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                tag="club-open:$identifier";isFocusable=true
                setPadding(ui.dp(6),ui.dp(4),ui.dp(2),ui.dp(4))
                setOnClickListener {openClub(identifier)}
                footer.addView(this,LinearLayout.LayoutParams(-2,-2))
            }
        }
        if(invites.isNotEmpty()){
            section(parent,"CONVITES")
            invites.forEach {entry->
                val identifier=id(entry.s("id")) ?: return@forEach
                val block=card(parent,"club-card:$identifier",ui)
                val row=ui.row().apply {gravity=Gravity.CENTER_VERTICAL};block.addView(row,LinearLayout.LayoutParams(-1,-2))
                monogramBox(row,ClubFormatting.monogram(entry.s("name")),40,ui,14f,12)
                val info=ui.column();row.addView(info,LinearLayout.LayoutParams(0,-2,1f))
                title(info,entry.s("name"),19).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END}
                val inviter=entry.s("invited_by")
                text(info,if(inviter.isNotBlank())"Convite de @$inviter" else "Convite pendente",11,ui.muted)
                val buttons=ui.row().apply {gravity=Gravity.END};block.addView(buttons,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(8)})
                action(buttons,"Recusar"){mutate("/v1/clubs/$identifier/leave")}.apply {layoutParams=LinearLayout.LayoutParams(-2,-2).apply {marginEnd=ui.dp(8)}}
                action(buttons,"Entrar",primary=true){mutate("/v1/clubs/$identifier/join")}.apply {tag="club-join:$identifier";layoutParams=LinearLayout.LayoutParams(-2,-2)}
            }
        }
        if(loaded && !busy && rows.isEmpty() && error.isBlank())text(parent,"Nenhum clube ainda.",13,ui.muted)
    }
    private fun pickDate(initial:Long=System.currentTimeMillis()+7*86400000L,chosen:(Long)->Unit){
        val profile=identity;val epoch=generation
        val cal=Calendar.getInstance().apply {timeInMillis=initial}
        tracked(DatePickerDialog(activity,{_,y,m,d->
            if(active && identity==profile && who()==profile && generation==epoch){
                cal.set(y,m,d)
                tracked(TimePickerDialog(activity,{_,h,min->
                    cal.set(Calendar.HOUR_OF_DAY,h);cal.set(Calendar.MINUTE,min);cal.set(Calendar.SECOND,0);cal.set(Calendar.MILLISECOND,0)
                    if(active && identity==profile && who()==profile && generation==epoch)chosen(cal.timeInMillis)
                },cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),true))
            }
        },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)))
    }
    private fun clubOptions(){
        val choices=mutableListOf("Página principal","Histórico de leituras","Participantes")
        if(data.s("role")=="admin")choices.add("Configurações do clube")
        choices.add("Sair do clube")
        tracked(AlertDialog.Builder(activity).setTitle(data.s("name")).setItems(choices.toTypedArray()){_,i->
            when(choices[i]){
                "Configurações do clube"->{round=null;clubNavigate("settings")}
                "Histórico de leituras"->{round=null;clubNavigate("history")}
                "Participantes"->clubNavigate("members")
                "Página principal"->{round=null;clubNavigate("home")}
                else->confirm("Sair deste clube?"){mutate("/v1/clubs/$club/leave"){club=null;round=null;clubPage="home"}}
            }
        }.setNegativeButton("Cancelar",null).create())
    }
    private fun scrollTop(){
        val context=Triple(club,clubPage,stage);val profile=identity
        activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll)?.post {if(active && identity==profile && who()==profile && context==Triple(club,clubPage,stage))activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll)?.scrollTo(0,0)}
    }
    private fun clubNavigate(page:String){clubPage=page;stage=null;draft="";replyToId=null;replyToUser="";reset(preserveClub=true);load();scrollTop()}
    private fun openDiscussion(s:JsonObject){stage=id(s.s("id"));stageInfo=s;draft="";replyToId=null;replyToUser="";scrollToBottom=true;reset(preserveClub=true);load()}
    private fun chooseBook(){booksQuery="";clubNavigate("books")}
    private fun clubBooks(parent:LinearLayout){
        title(parent,"Livros dos participantes",23)
        val search=ui.row();parent.addView(search,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(10)})
        val field=EditText(activity).apply {hint="Título ou autor";setText(booksQuery);setTextColor(ui.text);setHintTextColor(ui.muted);isSingleLine=true;filters=arrayOf(InputFilter.LengthFilter(100));tag="club-books-query"}
        search.addView(field,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(8)})
        action(search,"Buscar"){booksQuery=field.text.toString().trim();cursor=null;load()}
        rows.forEach {book->val owner=book.o("owner");val item=card(parent,"club-candidate:${owner.s("id")}:${book.s("id")}")
            val row=ui.row().apply {gravity=Gravity.TOP};item.addView(row);picture(row,book.s("cover"),48,72)
            val labels=ui.column();row.addView(labels,LinearLayout.LayoutParams(0,-2,1f));title(labels,book.s("title"),21);text(labels,book.s("author"),11,ui.muted);text(labels,"${book.n("pages")} páginas · @${owner.s("username")}",10,ui.muted)
            val footer=ui.row().apply {gravity=Gravity.END};item.addView(footer,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(6)})
            action(footer,"Indicar para votação"){
                mutate("/v1/clubs/$club/proposals",body=JsonObject().apply {addProperty("book_id",book.s("id"));addProperty("source_user_id",owner.s("id"))}){clubPage="home";rows=emptyList()}
            }.tag="club-nominate:${owner.s("id")}:${book.s("id")}"
        }
        if(loaded && rows.isEmpty() && !busy && error.isBlank())text(parent,"Nenhum livro encontrado nas estantes sincronizadas.",12,ui.muted)
        if(cursor!=null && rows.size<400)action(parent,"Mais livros"){load(more=true)}
    }
    private fun renderRhythmBar(parent:LinearLayout,percent:Int){
        val filled=ClubFormatting.filledTicks(percent)
        val strip=ui.row();parent.addView(strip,LinearLayout.LayoutParams(-1,ui.dp(14)).apply {topMargin=ui.dp(6);bottomMargin=ui.dp(4)})
        for(i in 0 until ClubFormatting.RHYTHM_TICKS){
            val tickView=View(activity).apply {
                background=if(i<filled)ui.shape(ui.accent,0) else ui.outlined(ui.activeSurface,ui.lineColor,0)
            }
            strip.addView(tickView,LinearLayout.LayoutParams(0,-1,1f).apply {if(i<ClubFormatting.RHYTHM_TICKS-1)marginEnd=ui.dp(2)})
        }
    }
    private fun clubView(root:LinearLayout){
        if(data.s("id").isBlank())return
        when(clubPage){"settings"->{clubSettings(root);return};"history"->{clubHistory(root);return};"members"->{clubMembers(root);return}}
        val r=data.o("round");val state=r.s("state");val selected=data.list("proposals",30).firstOrNull {it.s("id")==r.s("selected_id")}
        if(state=="voting"){clubVoting(root);return}
        if(state=="completed" && round==null){
            val panel=card(root,"club-completed");title(panel,"Leitura encerrada",24);action(panel,"Histórico de leituras"){clubNavigate("history")}
            return
        }
        if(selected==null)return
        val members=data.list("members",100).filter {it.s("status")=="active"}
        val pages=selected.n("pages").coerceAtLeast(1)
        val average=if(members.isEmpty())0 else (members.sumOf {it.n("page").coerceAtMost(pages)}*100/(members.size*pages)).toInt()
        val myPage=members.firstOrNull {it.s("username")==accounts.username}?.n("page") ?: 0
        val myPct=((myPage.coerceAtMost(pages)*100)/pages).toInt()

        val parent=card(root,"club-reading-card")
        val hero=ui.row().apply {gravity=Gravity.TOP};parent.addView(hero,LinearLayout.LayoutParams(-1,-2))
        picture(hero,selected.s("cover"),68,102,marginEndDp=14)
        val labels=ui.column();hero.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        text(labels,if(state=="completed")"LEITURA CONCLUÍDA" else "LEITURA ATUAL",9,ui.accent).apply {letterSpacing=0.08f}
        title(labels,selected.s("title"),24).apply {maxLines=2;ellipsize=TextUtils.TruncateAt.END}
        text(labels,selected.s("author"),12,ui.muted).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END}
        text(labels,"${selected.n("pages")} páginas · ${ClubFormatting.countLabel(members.size.toLong(),"participante","participantes")}",10,ui.muted).apply {
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(4)}
        }

        line(parent)
        val dueMs=instant(r.s("due_at"))
        val remainingMs=dueMs-System.currentTimeMillis()
        val metrics=ui.row().apply {gravity=Gravity.TOP};parent.addView(metrics,LinearLayout.LayoutParams(-1,-2))
        val dueCol=ui.column();metrics.addView(dueCol,LinearLayout.LayoutParams(0,-2,1f))
        text(dueCol,"PRAZO FINAL",9,ui.muted).apply {letterSpacing=0.08f}
        title(dueCol,date(dueMs,"dd/MM/yyyy"),20).apply {tag="club-final-deadline"}
        text(dueCol,date(dueMs,"HH'h'mm"),10,ui.muted)
        metrics.addView(View(activity).apply {setBackgroundColor(ui.lineColor)},LinearLayout.LayoutParams(ui.dp(1).coerceAtLeast(1),-1).apply {marginStart=ui.dp(12);marginEnd=ui.dp(12)})
        val remCol=ui.column();metrics.addView(remCol,LinearLayout.LayoutParams(0,-2,1f))
        text(remCol,"RESTAM",9,ui.muted).apply {letterSpacing=0.08f}
        title(remCol,ClubFormatting.remainingLabel(state,remainingMs),20,ui.accent)
        text(remCol,"${members.count {it.n("page")>=pages}}/${members.size} concluíram",10,ui.muted)

        if(state=="reading"){
            line(parent)
            val mine=ui.row().apply {gravity=Gravity.CENTER_VERTICAL};parent.addView(mine,LinearLayout.LayoutParams(-1,-2))
            val mineInfo=ui.column();mine.addView(mineInfo,LinearLayout.LayoutParams(0,-2,1f))
            text(mineInfo,"SUA LEITURA",9,ui.muted).apply {letterSpacing=0.08f}
            text(mineInfo,"Pág. $myPage / $pages ($myPct%)",13,ui.text,bold=true)
            action(mine,"Atualizar"){
                val body=ui.column().apply {setPadding(ui.dp(20),0,ui.dp(20),0)}
                val page=input(body,"Página atual",myPage.toString(),numeric=true,limit=6)
                tracked(AlertDialog.Builder(activity).setTitle("Meu progresso · $pages páginas").setView(body).setPositiveButton("Salvar"){_,_->mutate("/v1/clubs/$club/progress","PATCH",JsonObject().apply {addProperty("page",page.text.toString().toIntOrNull() ?: -1)})}.setNegativeButton("Cancelar",null).create())
            }
        }

        line(parent)
        val rhythmHeader=ui.row().apply {gravity=Gravity.CENTER_VERTICAL};parent.addView(rhythmHeader,LinearLayout.LayoutParams(-1,-2))
        text(rhythmHeader,"RITMO DO GRUPO",9,ui.muted).apply {letterSpacing=0.08f;layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
        title(rhythmHeader,"$average%",18,ui.accent).layoutParams=LinearLayout.LayoutParams(-2,-2)
        renderRhythmBar(parent,average)
        val membersStrip=ui.row().apply {gravity=Gravity.CENTER_VERTICAL};parent.addView(membersStrip,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(8)})
        members.take(5).forEach {m->monogramBox(membersStrip,ClubFormatting.monogram(m.s("username"),1),26,ui,11f,4)}
        if(members.size>5)text(membersStrip,"+${members.size-5}",10,ui.muted).layoutParams=LinearLayout.LayoutParams(-2,-2).apply {marginStart=ui.dp(4)}
        membersStrip.addView(View(activity),LinearLayout.LayoutParams(0,1,1f))
        TextView(activity).apply {
            text="Participantes →";textSize=11f;setTextColor(ui.accent);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setPadding(ui.dp(6),ui.dp(6),ui.dp(2),ui.dp(6));isFocusable=true;setOnClickListener {clubNavigate("members")}
            membersStrip.addView(this,LinearLayout.LayoutParams(-2,-2))
        }

        section(root,"ETAPAS")
        val stages=data.list("stages",20)
        if(stages.isEmpty())text(root,"Nenhuma etapa definida.",12,ui.muted)
        val currentIdx=ClubFormatting.activeStageIndex(stages.map {it.y("is_open")})
        stages.forEachIndexed {index,s->
            val isOpen=s.y("is_open")
            val isCurrent=index==currentIdx
            val isPast=isOpen && index<currentIdx
            val reached=members.count {it.n("page")>=s.n("page_limit")}
            val comments=s.n("comments")
            val unread=s.n("unread")
            if(isCurrent){
                val panel=stripeCard(root,"club-stage:${s.s("id")}",ui)
                val topRow=ui.row().apply {gravity=Gravity.CENTER_VERTICAL};panel.addView(topRow,LinearLayout.LayoutParams(-1,-2))
                text(topRow,"ETAPA ${index+1} · ATUAL",9,ui.accent).apply {letterSpacing=0.08f;layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
                text(topRow,if(isOpen)"Conversa aberta" else "Agendada",10,if(isOpen)ui.accent else ui.muted).layoutParams=LinearLayout.LayoutParams(-2,-2)
                title(panel,"Até a página ${s.n("page_limit")}",22).apply {layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(2);bottomMargin=ui.dp(4)}}
                val meta=ui.row();panel.addView(meta,LinearLayout.LayoutParams(-1,-2))
                text(meta,"Discussão · ${date(instant(s.s("discuss_at")),"dd/MM, HH'h'mm")}",10,ui.muted).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
                text(meta,"$reached/${members.size} chegaram",10,ui.muted).layoutParams=LinearLayout.LayoutParams(-2,-2)
                line(panel,margin=8)
                val footer=ui.row().apply {gravity=Gravity.CENTER_VERTICAL};panel.addView(footer,LinearLayout.LayoutParams(-1,-2))
                val stats=ClubFormatting.countLabel(comments,"comentário","comentários")+(if(unread>0)" · ${ClubFormatting.countLabel(unread,"nova","novas")}" else "")
                text(footer,stats,11,if(unread>0)ui.accent else ui.muted).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
                action(footer,"Conversar →",primary=isOpen){openDiscussion(s)}.apply {isEnabled=isOpen && !busy;tag="club-discussion:${s.s("id")}"}
            }else if(isPast){
                val rowCard=ui.row().apply {
                    tag="club-stage:${s.s("id")}";gravity=Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10))
                    background=ui.outlined(ui.surface,ui.lineColor,0)
                    isFocusable=true;setOnClickListener {if(!busy)openDiscussion(s)}
                }
                root.addView(rowCard,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(8)})
                TextView(activity).apply {text="✓";textSize=12f;setTextColor(ui.accent);rowCard.addView(this,LinearLayout.LayoutParams(-2,-2).apply {marginEnd=ui.dp(10)})}
                val info=ui.column();rowCard.addView(info,LinearLayout.LayoutParams(0,-2,1f))
                text(info,"Etapa ${index+1} · até pág. ${s.n("page_limit")}",13,ui.text)
                text(info,"$reached/${members.size} chegaram · ${date(instant(s.s("discuss_at")),"dd/MM")}",10,ui.muted)
                val badgeText=if(unread>0)"$comments · $unread novas ›" else "$comments ›"
                TextView(activity).apply {
                    text=badgeText;textSize=11f;setTextColor(ui.accent);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                    tag="club-discussion:${s.s("id")}";isEnabled=!busy;isFocusable=true
                    setPadding(ui.dp(8),ui.dp(6),ui.dp(2),ui.dp(6))
                    setOnClickListener {openDiscussion(s)}
                    rowCard.addView(this,LinearLayout.LayoutParams(-2,-2))
                }
            }else {
                val rowCard=ui.row().apply {
                    tag="club-stage:${s.s("id")}";gravity=Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10))
                    background=ui.outlined(ui.background,ui.lineColor,0)
                }
                root.addView(rowCard,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(8)})
                val lockIcon=ImageView(activity).apply {setImageDrawable(ui.symbol("lock",ui.muted,14))}
                rowCard.addView(lockIcon,LinearLayout.LayoutParams(ui.dp(16),ui.dp(16)).apply {marginEnd=ui.dp(10)})
                val info=ui.column();rowCard.addView(info,LinearLayout.LayoutParams(0,-2,1f))
                text(info,"Etapa ${index+1} · até pág. ${s.n("page_limit")}",13,ui.muted)
                text(info,"$reached/${members.size} chegaram",10,ui.muted)
                TextView(activity).apply {
                    text=date(instant(s.s("discuss_at")),"dd/MM");textSize=11f;setTextColor(ui.muted)
                    tag="club-discussion:${s.s("id")}";isEnabled=false
                    rowCard.addView(this,LinearLayout.LayoutParams(-2,-2))
                }
            }
        }

        section(root,"NESTA LEITURA")
        if(clubFeed.isNotEmpty()){
            val feedContainer=ui.column().apply {background=ui.outlined(ui.surface,ui.lineColor,0)}
            root.addView(feedContainer,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(12)})
            clubFeed.forEachIndexed {idx,event->
                if(idx>0)feedContainer.addView(View(activity).apply {setBackgroundColor(ui.lineColor)},LinearLayout.LayoutParams(-1,ui.dp(1).coerceAtLeast(1)))
                val panel=ui.row().apply {
                    tag="club-feed:${event.s("id")}";gravity=Gravity.TOP
                    setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10))
                }
                feedContainer.addView(panel,LinearLayout.LayoutParams(-1,-2))
                val actorName=event.o("actor").s("username")
                monogramBox(panel,ClubFormatting.monogram(actorName,1),28,ui,11f,10)
                val bodyCol=ui.column();panel.addView(bodyCol,LinearLayout.LayoutParams(0,-2,1f))
                val head=ui.row();bodyCol.addView(head,LinearLayout.LayoutParams(-1,-2))
                text(head,"@$actorName",12,ui.text,bold=true).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
                text(head,date(event.n("occurred_at"),"dd/MM HH'h'mm"),9,ui.muted).layoutParams=LinearLayout.LayoutParams(-2,-2)
                val body=event.o("data")
                when(event.s("kind")){
                    "progress"->{title(bodyCol,"Página ${body.n("page")} de $pages",19);text(bodyCol,if(body.n("page")>=body.n("previous_page"))"Atualizou a leitura" else "Corrigiu o progresso",10,ui.muted)}
                    "comment"->{
                        text(bodyCol,"Comentou · até a página ${body.n("page_limit")}",10,ui.accent)
                        text(bodyCol,body.s("body"),13).maxLines=3
                        TextView(activity).apply {
                            text="Ver conversa →";textSize=10f;setTextColor(ui.accent);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                            setPadding(0,ui.dp(4),0,ui.dp(2));isFocusable=true
                            setOnClickListener {data.list("stages",20).firstOrNull {it.s("id")==event.s("stage_id")}?.let(::openDiscussion)}
                            bodyCol.addView(this,LinearLayout.LayoutParams(-2,-2))
                        }
                    }
                    "read"->{val rowMetrics=ui.row();bodyCol.addView(rowMetrics,LinearLayout.LayoutParams(-1,-2))
                        val pagesRead=ui.column();rowMetrics.addView(pagesRead,LinearLayout.LayoutParams(0,-2,1f));title(pagesRead,"${body.n("pages")} páginas",19);text(pagesRead,"${body.n("start_page")} → ${body.n("end_page")}",10,ui.muted)
                        val time=ui.column().apply {gravity=Gravity.END};rowMetrics.addView(time,LinearLayout.LayoutParams(-2,-2));title(time,duration(body.n("duration_ms")),19,ui.accent);text(time,"de leitura",10,ui.muted)
                    }
                    "finished"->{title(bodyCol,"Terminou de ler",19);if(body.n("total_duration_ms")>0)text(bodyCol,duration(body.n("total_duration_ms"))+" de leitura",11,ui.accent)}
                    "rated"->{title(bodyCol,"Avaliou a leitura",19);text(bodyCol,"★ "+body.s("rating"),13,ui.accent)}
                    "started"->title(bodyCol,"Começou a leitura",18)
                    "schedule"->{title(bodyCol,"Prazo final atualizado",18);text(bodyCol,date(instant(body.s("due_at")),"dd/MM/yyyy 'às' HH'h'mm"),11,ui.accent)}
                    "stage"->title(bodyCol,"Nova etapa · página ${body.n("page_limit")}",18)
                    "completed"->title(bodyCol,"Leitura concluída pelo clube",18)
                }
            }
        }
        if(clubFeed.isEmpty() && !busy)text(root,"Nenhuma atividade nesta leitura ainda.",12,ui.muted)
        if(clubFeedCursor!=null && clubFeed.size<200)action(root,"Mais atividades"){loadClubFeed(more=true)}
    }
    private fun clubVoting(root:LinearLayout){
        val proposals=data.list("proposals",30)
        val totalVotes=proposals.sumOf {it.n("votes")}
        val headerCard=card(root,"club-voting-header",ui)
        text(headerCard,"VOTAÇÃO ABERTA",9,ui.accent).apply {letterSpacing=0.08f}
        title(headerCard,"Próxima leitura",24)
        text(headerCard,"${ClubFormatting.countLabel(proposals.size.toLong(),"indicação","indicações")} · ${ClubFormatting.countLabel(totalVotes,"voto","votos")}",11,ui.muted)
        val btnRow=ui.row().apply {gravity=Gravity.END};headerCard.addView(btnRow,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(6)})
        action(btnRow,"+ Indicar livro",onClick=::chooseBook).tag="club-propose"

        proposals.forEach {p->
            val palette=if(p.s("cover").isNotBlank())coverPalette(p.s("cover"),fallbackToBase=true) else ui
            val panel=stripeCard(root,"club-proposal:${p.s("id")}",palette)
            val row=ui.row().apply {gravity=Gravity.TOP};panel.addView(row,LinearLayout.LayoutParams(-1,-2))
            picture(row,p.s("cover"),50,75,palette,marginEndDp=12)
            val labels=ui.column();row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            palette.label(labels,p.s("title"),20,palette.text).apply {typeface=Typeface.create("serif",Typeface.NORMAL);maxLines=2;ellipsize=TextUtils.TruncateAt.END}
            palette.label(labels,p.s("author"),11,palette.muted).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END}
            val proposedBy=p.s("proposed_by")
            val metaText="${p.n("pages")} páginas"+(if(proposedBy.isNotBlank())" · indicado por @$proposedBy" else "")
            palette.label(labels,metaText,10,palette.muted).apply {layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(3)}}

            val votes=p.n("votes")
            val share=ClubFormatting.voteShare(votes,totalVotes)
            val barTrack=FrameLayout(activity).apply {background=palette.shape(palette.activeSurface,0)}
            panel.addView(barTrack,LinearLayout.LayoutParams(-1,ui.dp(4)).apply {topMargin=ui.dp(10);bottomMargin=ui.dp(8)})
            if(share>0){
                val barRow=ui.row()
                barRow.addView(View(activity).apply {setBackgroundColor(palette.accent)},LinearLayout.LayoutParams(0,-1,share.toFloat()))
                if(share<100)barRow.addView(View(activity),LinearLayout.LayoutParams(0,-1,(100-share).toFloat()))
                barTrack.addView(barRow,FrameLayout.LayoutParams(-1,-1))
            }

            val footer=ui.row().apply {gravity=Gravity.CENTER_VERTICAL};panel.addView(footer,LinearLayout.LayoutParams(-1,-2))
            val voteText=ClubFormatting.countLabel(votes,"voto","votos")+(if(totalVotes>0)" ($share%)" else "")
            palette.label(footer,voteText,11,palette.accent,bold=true).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            val mine=p.y("mine")
            action(footer,if(mine)"✓ Seu voto" else "Votar",primary=mine,palette=palette){
                mutate("/v1/clubs/$club/vote",body=JsonObject().apply {addProperty("proposal_id",p.s("id"))})
            }.apply {isEnabled=!busy;tag="club-vote:${p.s("id")}"}
        }
        if(loaded && !busy && proposals.isEmpty() && error.isBlank())text(root,"Nenhum livro indicado ainda.",12,ui.muted)
    }
    private fun clubHistory(root:LinearLayout){
        section(root,"HISTÓRICO DE LEITURAS")
        val history=data.list("history",100).filter {it.s("state")=="completed"}
        history.forEach {item->
            val palette=if(item.s("cover").isNotBlank())coverPalette(item.s("cover"),fallbackToBase=true) else ui
            val panel=stripeCard(root,"club-history:${item.s("id")}",palette)
            val row=ui.row().apply {gravity=Gravity.TOP};panel.addView(row);picture(row,item.s("cover"),54,81,palette)
            val labels=ui.column();row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            palette.label(labels,item.s("title"),21,palette.text).apply {typeface=Typeface.create("serif",Typeface.NORMAL);maxLines=2}
            palette.label(labels,item.s("author"),11,palette.muted)
            palette.label(labels,item.s("completed_at").takeIf {it.isNotBlank()}?.let {"Concluído em "+date(instant(it),"dd/MM/yyyy")} ?: "Leitura concluída",10,palette.accent)
            val footer=ui.row().apply {gravity=Gravity.END};panel.addView(footer,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(6)})
            action(footer,"Ver leitura e discussões",palette=palette){round=id(item.s("id"));clubNavigate("home")}.tag="club-history-open:${item.s("id")}"
        }
        if(history.isEmpty())text(root,"Nenhuma leitura concluída ainda.",12,ui.muted)
    }
    private fun clubMembers(root:LinearLayout){
        section(root,"PARTICIPANTES")
        val admin=data.s("role")=="admin"
        val selected=data.list("proposals",30).firstOrNull {it.s("id")==data.o("round").s("selected_id")}
        val pages=selected?.n("pages")?.takeIf {it>0} ?: 0L
        val container=ui.column().apply {background=ui.outlined(ui.surface,ui.lineColor,0)}
        root.addView(container,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(12)})
        val members=data.list("members",100)
        members.forEachIndexed {idx,m->
            if(idx>0)container.addView(View(activity).apply {setBackgroundColor(ui.lineColor)},LinearLayout.LayoutParams(-1,ui.dp(1).coerceAtLeast(1)))
            val row=ui.row().apply {gravity=Gravity.CENTER_VERTICAL;setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10))}
            container.addView(row,LinearLayout.LayoutParams(-1,-2))
            monogramBox(row,ClubFormatting.monogram(m.s("username"),1),32,ui,12f,10)
            val labels=ui.column();row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            text(labels,"@${m.s("username")}",13,ui.text,bold=true)
            val page=m.n("page")
            val roleLabel=if(m.s("status")=="invited")"Convidado" else if(m.s("role")=="admin")"Admin" else "Membro"
            val progressText=if(m.s("status")=="invited")roleLabel else if(pages>0)"$roleLabel · pág. $page de $pages (${((page.coerceAtMost(pages)*100)/pages).toInt()}%)" else "$roleLabel · pág. $page"
            text(labels,progressText,10,ui.muted)
            if(m.s("status")=="active" && pages>0){
                val pct=((page.coerceAtMost(pages)*100)/pages).toInt().coerceIn(0,100)
                val bar=ui.row().apply {background=ui.shape(ui.activeSurface,0)}
                labels.addView(bar,LinearLayout.LayoutParams(-1,ui.dp(3)).apply {topMargin=ui.dp(4)})
                if(pct>0)bar.addView(View(activity).apply {setBackgroundColor(ui.accent)},LinearLayout.LayoutParams(0,-1,pct.toFloat()))
                if(pct<100)bar.addView(View(activity),LinearLayout.LayoutParams(0,-1,(100-pct).toFloat()))
            }
            if(admin && m.s("status")=="active")ui.icon(row,"more","Gerenciar @${m.s("username")}",40){memberMenu(m)}
        }
    }
    private fun startReading(chosen:JsonObject){
        var due=System.currentTimeMillis()+30*86400000L
        val body=ui.column().apply {setPadding(ui.dp(20),ui.dp(8),ui.dp(20),0)};title(body,chosen.s("title"),23);text(body,"PRAZO FINAL DA LEITURA",10,ui.muted)
        val dateButton=action(body,date(due,"dd/MM/yyyy 'às' HH'h'mm")){}
        dateButton.setOnClickListener {pickDate(due){due=it;dateButton.text=date(due,"dd/MM/yyyy 'às' HH'h'mm")}}
        tracked(AlertDialog.Builder(activity).setTitle("Iniciar leitura do clube").setView(body).setPositiveButton("Iniciar leitura"){_,_->mutate("/v1/clubs/$club/start",body=JsonObject().apply {addProperty("proposal_id",chosen.s("id"));addProperty("due_at",iso(due))}){clubPage="home";round=null}}.setNegativeButton("Cancelar",null).create())
    }
    private fun adjustDeadline(r:JsonObject){
        pickDate(instant(r.s("due_at"))){time->val body=ui.column().apply {setPadding(ui.dp(20),0,ui.dp(20),0)};text(body,"PRAZO FINAL",10,ui.muted);title(body,date(time,"dd/MM/yyyy 'às' HH'h'mm"),22)
            val shift=CheckBox(activity).apply {text="Mover próximas discussões junto";isChecked=true;setTextColor(ui.text)};body.addView(shift)
            tracked(AlertDialog.Builder(activity).setTitle("Alterar prazo final?").setView(body).setPositiveButton("Salvar"){_,_->mutate("/v1/clubs/$club/schedule","PATCH",JsonObject().apply {addProperty("due_at",iso(time));addProperty("shift_stages",shift.isChecked)})}.setNegativeButton("Cancelar",null).create())
        }
    }
    private fun clubSettings(root:LinearLayout){
        if(data.s("role")!="admin"){clubPage="home";clubView(root);return}
        section(root,"CONFIGURAÇÕES DO CLUBE")
        val peopleCard=card(root);title(peopleCard,"Participantes",21)
        action(peopleCard,"Adicionar participantes"){inviteClub=club;this.people=true;invited.clear();reset(preserveClub=true);load()}.tag="club-invite"
        action(peopleCard,"Gerenciar participantes"){clubNavigate("members")}
        val r=data.o("round");val state=r.s("state")
        val reading=card(root);title(reading,if(state=="voting")"Votação" else "Leitura",21)
        if(state=="voting"){
            action(reading,"Ver votação"){clubNavigate("home")}
            val proposals=data.list("proposals",30)
            if(proposals.any {it.n("votes")>0})action(reading,"Definir livro e prazo final",primary=true){
                val most=proposals.maxOf {it.n("votes")};val winners=proposals.filter {it.n("votes")==most}
                if(winners.size==1)startReading(winners[0])else tracked(AlertDialog.Builder(activity).setTitle("Desempatar votação").setItems(winners.map {it.s("title")}.toTypedArray()){_,i->startReading(winners[i])}.setNegativeButton("Cancelar",null).create())
            }
        }else if(state=="reading"){
            text(reading,"PRAZO FINAL DA LEITURA",10,ui.muted);title(reading,date(instant(r.s("due_at")),"dd/MM/yyyy 'às' HH'h'mm"),22)
            action(reading,"Alterar prazo final"){adjustDeadline(r)}
            action(reading,"Encerrar leitura e guardar no histórico"){confirm("Concluir esta leitura do clube?"){mutate("/v1/clubs/$club/complete"){clubPage="history";round=null}}}
            val steps=card(root);title(steps,"Etapas e discussões",21);action(steps,"Criar etapa"){stageEditor(null)}
            data.list("stages",20).forEach {s->line(steps);text(steps,"Até página ${s.n("page_limit")} · ${date(instant(s.s("discuss_at")))}",12);action(steps,"Editar etapa / liberar comentários"){stageEditor(s)}}
        }else action(reading,"Abrir nova votação",primary=true){mutate("/v1/clubs/$club/rounds"){round=null;clubPage="home"}}
        val danger=card(root);title(danger,"Clube",21);action(danger,"Excluir clube"){confirm("Excluir o clube e suas discussões?"){mutate("/v1/clubs/$club","DELETE"){club=null;round=null;clubPage="home"}}}
    }
    private fun memberMenu(person:JsonObject){
        val identifier=id(person.s("id")) ?: return
        val labels=arrayOf(if(person.s("role")=="admin")"Remover de administrador" else "Tornar administrador","Transferir minha administração","Remover do clube")
        tracked(AlertDialog.Builder(activity).setTitle("@${person.s("username")}").setItems(labels){_,i->when(i){0->mutate("/v1/clubs/$club/members/$identifier","PATCH",JsonObject().apply {addProperty("role",if(person.s("role")=="admin")"member" else "admin")});1->confirm("Transferir sua administração para @${person.s("username") }?"){mutate("/v1/clubs/$club/transfer",body=JsonObject().apply {addProperty("user_id",identifier)})};else->confirm("Remover @${person.s("username") } do clube?"){mutate("/v1/clubs/$club/members/$identifier","DELETE")}}}.setNegativeButton("Cancelar",null).create())
    }
    private fun stageEditor(old:JsonObject?){
        val body=ui.column().apply {setPadding(ui.dp(20),0,ui.dp(20),0)};val page=input(body,"Parar na página",old?.n("page_limit")?.toString().orEmpty(),true,6).apply {isEnabled=old==null}
        var at=old?.s("discuss_at")?.let(::instant) ?: System.currentTimeMillis()+86400000
        val button=Button(activity).apply {text=date(at);setOnClickListener {pickDate(at){at=it;text=date(at)}}};body.addView(button)
        val open=CheckBox(activity).apply {text="Liberar comentários agora";isChecked=old?.y("is_open")==true;setTextColor(ui.text)};body.addView(open)
        val oldId=old?.let { id(it.s("id")) ?: return }
        tracked(AlertDialog.Builder(activity).setTitle(if(old==null)"Nova etapa" else "Ajustar etapa").setView(body).setPositiveButton("Salvar"){_,_->mutate("/v1/clubs/$club/stages"+(oldId?.let {"/$it"} ?: ""),if(old==null)"POST" else "PATCH",JsonObject().apply {addProperty("page_limit",page.text.toString().toIntOrNull() ?: 0);addProperty("discuss_at",iso(at));addProperty("is_open",open.isChecked)})}.setNegativeButton("Cancelar",null).create())
    }
    private var composer:EditText?=null
    private var sendComment:TextView?=null
    private fun updateReplyBanner(){
        val id=replyToId
        replyBanner?.visibility=if(id!=null && stageInfo.y("is_open"))View.VISIBLE else View.GONE
        replyBannerLabel?.text=if(id!=null)"Respondendo a @$replyToUser" else ""
    }
    private fun discussionControls(){
        composer?.isEnabled=loaded && !busy && stageInfo.y("is_open")
        sendComment?.isEnabled=loaded && !busy && draft.isNotBlank() && stageInfo.y("is_open")
        liveStatus?.text=error
        liveStatus?.visibility=if(error.isBlank())View.GONE else View.VISIBLE
        updateReplyBanner()
    }
    private fun stopLive(){
        liveEpoch++;liveTag?.let {api?.cancel(it)};liveTag=null;livePath=""
        api?.cancel(fetchTag);messagesFetching=false;eventsSeen=0;eventsApplied=0;liveRetry=1000
        handler.removeCallbacks(liveRefresh);handler.removeCallbacks(liveReconnect)
        messageList=null;composer=null;sendComment=null;liveStatus=null;replyBanner=null;replyBannerLabel=null
    }
    private fun validDiscussion(epoch:Int,profile:String,path:String)=active && !closed && epoch==liveEpoch && identity==profile && who()==profile && path=="/v1/clubs/$club/stages/$stage/events" && messageBefore==null
    private fun startLive(){
        if(!active || closed || stage==null || messageBefore!=null || !stageInfo.y("is_open") || tab!="clubs" || people)return
        val path="/v1/clubs/$club/stages/$stage/events"
        if(liveTag!=null)return
        val client=api ?: return;val tag=DiscussionConnection();liveTag=tag;livePath=path
        val epoch=liveEpoch;val profile=identity
        liveWorker.execute {
            val result=runCatching {client.discussionEvents(path,tag){event->handler.post {
                if(!validDiscussion(epoch,profile,path))return@post
                if(event=="revoked"){
                    stopLive();rows=emptyList();stageInfo.addProperty("is_open",false);error="Discussão indisponível para esta conta.";changed()
                }else {liveRetry=1000;eventsSeen++;handler.removeCallbacks(liveRefresh);handler.postDelayed(liveRefresh,250)}
            }}}
            handler.post {
                if(!validDiscussion(epoch,profile,path))return@post
                liveTag=null
                val failure=result.exceptionOrNull()
                if(failure is AccountFailure && failure.code in listOf(401,403,404)){
                    stopLive();rows=emptyList();stageInfo.addProperty("is_open",false);error=failure.message.orEmpty();changed();return@post
                }
                if(failure!=null){error="Comentários sem conexão. Tentando novamente.";discussionControls()}
                if(failure is AccountFailure && failure.code==429)liveRetry=60000
                handler.postDelayed(liveReconnect,liveRetry);liveRetry=(liveRetry*2).coerceAtMost(60000)
            }
        }
    }
    private fun refreshMessages(){
        if(!active || closed || stage==null || messagesFetching || busy || messageBefore!=null)return
        val client=api ?: return;val epoch=liveEpoch;val profile=identity;val path="/v1/clubs/$club/stages/$stage/events";val seen=eventsSeen
        messagesFetching=true
        messagesWorker.execute {val result=runCatching {client.call(path.removeSuffix("events")+"messages",responseLimit=1024*1024,timeoutSeconds=12,tag=fetchTag)};handler.post {
            if(!validDiscussion(epoch,profile,path))return@post
            messagesFetching=false
            result.fold({response->
                rows=response.list("items",40);cursor=response.s("next_cursor").ifBlank {null};stageInfo=response.o("stage");eventsApplied=seen;error=""
                messageList?.let(::renderMessages);discussionControls();startLive()
                if(eventsSeen>eventsApplied){handler.removeCallbacks(liveRefresh);handler.postDelayed(liveRefresh,300)}
            },{failure->
                error=failure.message ?: "Não foi possível atualizar os comentários.";discussionControls()
                if(failure is AccountFailure && failure.code in listOf(401,403,404)){
                    rows=emptyList();messageList?.let(::renderMessages);stageInfo.addProperty("is_open",false);stopLive();changed()
                }else {handler.removeCallbacks(liveRefresh);handler.postDelayed(liveRefresh,if(failure is AccountFailure && failure.code==429)60000 else 15000)}
            })
        }}
    }
    private var scrollToBottom=false
    private fun discussionView(parent:LinearLayout){
        ensureBackdrop()
        if(stageInfo.s("id").isBlank())return
        val barBg=alphaColor(ui.background,212)
        val borderColor=alphaColor(ui.lineColor,196)
        val context=ui.row().apply {
            gravity=Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12),ui.dp(8),ui.dp(12),ui.dp(8))
            background=ui.outlined(barBg,borderColor,0)
        }
        parent.addView(context,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(10)})
        val liveLabel=if(stageInfo.y("is_open"))"● Conversa aberta · Ao vivo" else "Conversa encerrada"
        text(context,liveLabel,10,if(stageInfo.y("is_open"))ui.accent else ui.muted,bold=true).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
        text(context,date(instant(stageInfo.s("discuss_at")),"dd/MM · HH'h'mm"),10,ui.muted).apply {gravity=Gravity.END;layoutParams=LinearLayout.LayoutParams(-2,-2)}

        val list=ui.column();parent.addView(list,LinearLayout.LayoutParams(-1,-2));messageList=list;renderMessages(list)

        val dock=composerDock
        val composerCard=ui.column().apply {
            if(dock!=null){
                setPadding(ui.dp(14),ui.dp(8),ui.dp(14),ui.dp(10))
                setBackgroundColor(alphaColor(ui.surface,246))
            }else {
                setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10))
                background=ui.outlined(alphaColor(ui.surface,236),borderColor,0)
            }
        }
        if(dock!=null){
            dock.removeAllViews()
            val wrapper=ui.column()
            wrapper.addView(View(activity).apply {setBackgroundColor(borderColor)},LinearLayout.LayoutParams(-1,ui.dp(1).coerceAtLeast(1)))
            wrapper.addView(composerCard,LinearLayout.LayoutParams(-1,-2))
            dock.addView(wrapper,FrameLayout.LayoutParams(-1,-2))
            dock.visibility=View.VISIBLE
        }else {
            parent.addView(composerCard,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(10);bottomMargin=ui.dp(8)})
        }

        liveStatus=text(composerCard,error,11,ui.muted).apply {layoutParams=LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(4)}}

        val banner=ui.row().apply {
            gravity=Gravity.CENTER_VERTICAL
            setPadding(ui.dp(10),ui.dp(5),ui.dp(8),ui.dp(5))
            background=StripeCardDrawable(alphaColor(ui.activeSurface,230),borderColor,ui.accent,ui.dp(1).coerceAtLeast(1),ui.dp(2).coerceAtLeast(1))
            visibility=View.GONE
        }
        composerCard.addView(banner,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(6)})
        val bannerText=text(banner,"",11,ui.accent).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END;layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
        TextView(activity).apply {
            text="×";textSize=14f;setTextColor(ui.muted);setPadding(ui.dp(10),ui.dp(2),ui.dp(6),ui.dp(2))
            isFocusable=true;setOnClickListener {replyToId=null;replyToUser="";updateReplyBanner()}
            banner.addView(this,LinearLayout.LayoutParams(-2,-2))
        }
        replyBanner=banner;replyBannerLabel=bannerText

        val inputRow=ui.row().apply {gravity=Gravity.BOTTOM}
        composerCard.addView(inputRow,LinearLayout.LayoutParams(-1,-2))

        val field=EditText(activity).apply {
            hint="Escreva seu comentário"
            setText(draft)
            setTextColor(ui.text)
            setHintTextColor(ui.muted)
            textSize=14f
            isSingleLine=false
            minLines=1
            maxLines=4
            isVerticalScrollBarEnabled=true
            gravity=Gravity.TOP or Gravity.START
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters=arrayOf(InputFilter.LengthFilter(2000))
            tag="club-message"
            isEnabled=stageInfo.y("is_open")
            background=ui.outlined(alphaColor(ui.background,230),borderColor,0)
            setPadding(ui.dp(10),ui.dp(9),ui.dp(10),ui.dp(9))
            minHeight=ui.dp(40)
            setOnFocusChangeListener {_,hasFocus->
                if(hasFocus)activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll)?.post {
                    activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll)?.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
        inputRow.addView(field,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(8)})
        composer=field

        val send=action(inputRow,"Publicar",primary=true){
            val payload=JsonObject().apply {
                addProperty("body",draft)
                replyToId?.let {addProperty("reply_to_id",it)}
            }
            scrollToBottom=true
            mutate("/v1/clubs/$club/stages/$stage/messages",body=payload){draft="";replyToId=null;replyToUser="";messageBefore=null}
        }.apply {
            tag="club-send"
            minHeight=ui.dp(40)
            setPadding(ui.dp(14),ui.dp(9),ui.dp(14),ui.dp(9))
            layoutParams=LinearLayout.LayoutParams(-2,-2)
        }
        sendComment=send
        field.addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,a:Int,c:Int,f:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){draft=s.toString();editedAt=SystemClock.elapsedRealtime();discussionControls()};override fun afterTextChanged(s:Editable?){} })
        discussionControls()
        if(loaded)startLive()
    }
    private fun renderMessages(parent:LinearLayout){
        val scroll=activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll);val position=scroll?.scrollY ?: 0
        val wasNearBottom=scroll!=null && scroll.childCount>0 && (scroll.getChildAt(0).bottom-(scroll.height+position))<=ui.dp(80)
        parent.removeAllViews()
        val cardBg=alphaColor(ui.surface,216)
        val borderColor=alphaColor(ui.lineColor,196)
        if(cursor!=null)action(parent,"Mensagens anteriores"){val before=cursor;stopLive();messageBefore=before;load()}
        var day=""
        rows.asReversed().forEach {m->
            val sentMs=instant(m.s("sent_at"))
            if(sentMs>0){
                val key=ReadingDays.at(sentMs)
                if(key!=day){
                    day=key
                    val today=ReadingDays.at(System.currentTimeMillis());val yesterday=ReadingDays.at(System.currentTimeMillis()-86400000)
                    section(parent,when(key){today->"HOJE";yesterday->"ONTEM";else->ReadingDays.label(key).uppercase()})
                }
            }
            val replyTo=m.o("reply_to");val hasReply=replyTo.n("id")>0
            val message=ui.column().apply {
                tag="club-discussion-message:${m.n("id")}"
                setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10))
                background=if(hasReply)StripeCardDrawable(cardBg,borderColor,ui.accent,ui.dp(1).coerceAtLeast(1),ui.dp(2).coerceAtLeast(1))
                else ui.outlined(cardBg,borderColor,0)
            }
            parent.addView(message,LinearLayout.LayoutParams(-1,-2).apply {
                bottomMargin=ui.dp(8)
                if(hasReply)marginStart=ui.dp(14)
            })
            if(hasReply){
                text(message,"↳ em resposta a @${replyTo.s("username")}",10,ui.accent).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END}
                val snippet=replyTo.s("body")
                if(snippet.isNotBlank())text(message,snippet,11,ui.muted).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END;layoutParams=LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(6)}}
            }
            val header=ui.row().apply {gravity=Gravity.CENTER_VERTICAL};message.addView(header,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(4)})
            val username=m.s("username")
            monogramBox(header,ClubFormatting.monogram(username,1),22,ui,10f,8)
            text(header,"@$username",12,if(username==accounts.username)ui.accent else ui.text,bold=true).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            text(header,if(sentMs>0)date(sentMs,"HH'h'mm") else "",10,ui.muted).apply {gravity=Gravity.END;layoutParams=LinearLayout.LayoutParams(-2,-2)}
            text(message,m.s("body"),14,ui.text).apply {
                tag="club-message:${m.n("id")}"
                setLineSpacing(0f,1.18f)
                if(m.y("can_delete"))setOnLongClickListener {confirm("Apagar esta mensagem?"){mutate("/v1/clubs/$club/stages/$stage/messages/${m.n("id")}","DELETE")};true}
            }
            if(stageInfo.y("is_open") || m.y("can_delete")){
                val footer=ui.row().apply {gravity=Gravity.END};message.addView(footer,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(4)})
                if(stageInfo.y("is_open")){
                    TextView(activity).apply {
                        text="Responder";textSize=10f;setTextColor(ui.muted);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                        setPadding(ui.dp(6),ui.dp(4),ui.dp(6),ui.dp(2));isFocusable=true
                        setOnClickListener {replyToId=m.n("id");replyToUser=username;updateReplyBanner();composer?.requestFocus()}
                        footer.addView(this,LinearLayout.LayoutParams(-2,-2))
                    }
                }
                if(m.y("can_delete")){
                    TextView(activity).apply {
                        text="Apagar";textSize=10f;setTextColor(ui.muted);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                        setPadding(ui.dp(10),ui.dp(4),ui.dp(2),ui.dp(2));isFocusable=true
                        setOnClickListener {confirm("Apagar esta mensagem?"){mutate("/v1/clubs/$club/stages/$stage/messages/${m.n("id")}","DELETE")}}
                        footer.addView(this,LinearLayout.LayoutParams(-2,-2))
                    }
                }
            }
        }
        if(messageBefore!=null)action(parent,"Mensagens recentes"){messageBefore=null;stopLive();load()}
        if(loaded && !busy && rows.isEmpty() && error.isBlank()){
            val emptyBox=ui.column().apply {
                setPadding(ui.dp(14),ui.dp(14),ui.dp(14),ui.dp(14))
                background=ui.outlined(cardBg,borderColor,0)
            }
            parent.addView(emptyBox,LinearLayout.LayoutParams(-1,-2))
            text(emptyBox,"Nenhum comentário ainda.",13,ui.muted)
        }
        val shouldScrollBottom=messageBefore==null && (scrollToBottom || (wasNearBottom && rows.isNotEmpty()))
        if(loaded)scrollToBottom=false
        scroll?.post {
            if(active && messageList===parent){
                if(shouldScrollBottom)scroll.fullScroll(View.FOCUS_DOWN)
                else scroll.scrollTo(0,position)
            }
        }
    }
}

/** Rectilinear surface with 1px hairline border and left accent stripe, safe from API 21+. */
private class StripeCardDrawable(
    private val fill:Int,
    private val border:Int,
    private val stripe:Int,
    private val borderWidth:Int,
    private val stripeWidth:Int,
):Drawable(){
    private val paint=Paint()
    override fun draw(canvas:Canvas){
        if(bounds.isEmpty)return
        val l=bounds.left.toFloat();val t=bounds.top.toFloat();val r=bounds.right.toFloat();val b=bounds.bottom.toFloat()
        paint.style=Paint.Style.FILL;paint.color=fill
        canvas.drawRect(l,t,r,b,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=borderWidth.toFloat();paint.color=border
        val half=borderWidth/2f
        canvas.drawRect(l+half,t+half,r-half,b-half,paint)
        paint.style=Paint.Style.FILL;paint.color=stripe
        canvas.drawRect(l,t,(l+stripeWidth).coerceAtMost(r),b,paint)
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(colorFilter:ColorFilter?){paint.colorFilter=colorFilter;invalidateSelf()}
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** Enlarged CoverBlur backdrop centered across width with a tinted readability scrim. */
private class ClubCoverBackdrop(private val bitmap:Bitmap,private val baseColor:Int):Drawable(){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint=Paint()
    override fun draw(canvas:Canvas){
        if(bounds.isEmpty || bitmap.isRecycled)return
        val scale=bounds.width().toFloat()/bitmap.width.coerceAtLeast(1)
        val halfW=bitmap.width*scale/2f
        val targetH=bitmap.height*scale
        val target=RectF(bounds.exactCenterX()-halfW,bounds.top.toFloat(),bounds.exactCenterX()+halfW,bounds.top+targetH)
        val save=canvas.save();canvas.clipRect(bounds)
        canvas.drawColor(baseColor)
        canvas.drawBitmap(bitmap,null,target,paint)
        val r=Color.red(baseColor);val g=Color.green(baseColor);val b=Color.blue(baseColor)
        scrimPaint.shader=LinearGradient(
            0f,bounds.top.toFloat(),0f,bounds.bottom.toFloat(),
            intArrayOf(Color.argb(66,r,g,b),Color.argb(148,r,g,b),Color.argb(224,r,g,b),Color.argb(248,r,g,b)),
            floatArrayOf(0f,.38f,.72f,1f),Shader.TileMode.CLAMP,
        )
        canvas.drawRect(bounds,scrimPaint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(colorFilter:ColorFilter?){paint.colorFilter=colorFilter;invalidateSelf()}
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
