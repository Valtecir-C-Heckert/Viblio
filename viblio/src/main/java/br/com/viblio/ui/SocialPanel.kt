package br.com.viblio.ui

import android.app.*
import android.graphics.Typeface
import android.os.*
import android.text.*
import android.view.*
import android.widget.*
import br.com.centralcasa.books.*
import br.com.centralcasa.ui.DisplayUi
import br.com.viblio.account.*
import com.google.gson.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/** Feed, directory and club views share no private repository state with other accounts. */
class SocialPanel(private val activity:Activity,private val baseUi:DisplayUi,private val accounts:AccountManager,private val changed:()->Unit){
    private val ui=DisplayUi(activity)
    private val feedColors=linkedMapOf<String,Int?>()
    private var clubPage="home";private var clubFeed=emptyList<JsonObject>();private var clubFeedCursor:String?=null;private var booksQuery="";private var stageInfo=JsonObject()
    private var themeClub:String?=null;private var themeCover="";private var themeResolved=false
    private val handler=Handler(Looper.getMainLooper());private val worker=Executors.newSingleThreadExecutor()
    private var requestConnection:AccountConnection?=null
    private var api:AccountApi?=null;private var images:FriendCoverLoader?=null
    private var identity="";private var active=false;private var closed=false;private var generation=0;private var busy=false;private var loaded=false;private var error=""
    private var tab="feed";private var people=false;private var inviteClub:String?=null;private var club:String?=null;private var round:String?=null;private var stage:String?=null
    private var data=JsonObject();private var rows=emptyList<JsonObject>();private var cursor:String?=null;private var messageBefore:String?=null
    private var draft="";private var editedAt=0L;private var scheduled=false;private var delay=30000L
    private val liveWorker=Executors.newSingleThreadExecutor()
    private val messagesWorker=Executors.newSingleThreadExecutor()
    private var liveTag:DiscussionConnection?=null;private var liveEpoch=0;private var livePath="";private var liveRetry=1000L
    private var eventsSeen=0L;private var eventsApplied=0L;private var messagesFetching=false
    private var messageList:LinearLayout?=null;private var liveStatus:TextView?=null
    private val fetchTag=Any()
    private val liveRefresh=Runnable {refreshMessages()}
    private val liveReconnect=Runnable {startLive()}
    private val dialogs=mutableSetOf<Dialog>();private val invited=mutableSetOf<String>()
    private val friends=FriendsPanel(activity,ui,accounts,onDiscover=::discover,embedded=true){if(active && tab=="friends" && !people)changed()}
    private fun discover(){friends.hide(resetSelection=true);people=true;inviteClub=null;reset();changed()}
    val canReplaceLibrary get()=draft.isBlank() && dialogs.isEmpty() && !busy
    val displayTheme get()=if(tab=="friends" && !people)friends.displayTheme else ui
    private fun who()=LibraryProfiles.database(activity)+":"+accounts.catalogToken().orEmpty()
    private fun JsonObject.s(k:String)=get(k)?.takeUnless {it.isJsonNull}?.runCatching {asString}?.getOrNull().orEmpty()
    private fun JsonObject.n(k:String)=get(k)?.runCatching {asLong}?.getOrNull() ?: 0L
    private fun JsonObject.y(k:String)=get(k)?.runCatching {asBoolean}?.getOrNull()==true
    private fun JsonObject.o(k:String)=get(k)?.takeIf {it.isJsonObject}?.asJsonObject ?: JsonObject()
    private fun JsonObject.list(k:String,max:Int=200)=get(k)?.takeIf {it.isJsonArray}?.asJsonArray?.take(max)?.filter {it.isJsonObject}?.map {it.asJsonObject}.orEmpty()
    private fun id(s:String)=runCatching {UUID.fromString(s).toString()}.getOrNull()
    private fun text(parent:LinearLayout,value:String,size:Int=13,color:Int=ui.text)=ui.label(parent,value,size,color)
    private fun title(parent:LinearLayout,value:String,size:Int=21)=text(parent,value,size).apply {typeface=Typeface.create("serif",Typeface.NORMAL)}
    private fun line(parent:LinearLayout){parent.addView(View(activity).apply {setBackgroundColor(ui.lineColor)},LinearLayout.LayoutParams(-1,ui.dp(1)).apply {topMargin=ui.dp(10);bottomMargin=ui.dp(10)})}
    private fun card(parent:LinearLayout,tagName:String="",palette:DisplayUi=ui):LinearLayout=ui.column().apply {
        tag=tagName;setPadding(ui.dp(14),ui.dp(12),ui.dp(14),ui.dp(12));background=palette.outlined(palette.surface,palette.lineColor,0)
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(12)})
    }
    private fun section(parent:LinearLayout,label:String){
        val row=ui.row();parent.addView(row,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(8);bottomMargin=ui.dp(10)})
        text(row,label,10,ui.accent).layoutParams=LinearLayout.LayoutParams(-2,-2).apply {marginEnd=ui.dp(10)}
        row.addView(View(activity).apply {setBackgroundColor(ui.lineColor)},LinearLayout.LayoutParams(0,ui.dp(1),1f))
    }
    private fun action(parent:LinearLayout,label:String,primary:Boolean=false,onClick:()->Unit)=ui.action(parent,label,primary,onClick).apply {
        textSize=11f;minHeight=ui.dp(40);setPadding(ui.dp(12),ui.dp(7),ui.dp(12),ui.dp(7))
        if(!primary)background=ui.outlined(ui.surface,ui.lineColor,0)
        layoutParams=LinearLayout.LayoutParams(-2,-2).apply {topMargin=ui.dp(5);bottomMargin=ui.dp(5)}
    }
    private fun updateTheme(){
        if(club==null){themeClub=null;themeCover="";themeResolved=false;ui.inheritReadingPalette(baseUi);return}
        if(themeClub!=club){themeClub=club;themeCover="";themeResolved=false;ui.scopedReadingColor(null)}
        if(data.s("id")==club){
            val selected=data.list("proposals",30).firstOrNull {it.s("id")==data.o("round").s("selected_id")}
            val cover=selected?.s("cover").orEmpty()
            if(cover!=themeCover){themeCover=cover;themeResolved=false;ui.scopedReadingColor(null)}
        }
        if(themeCover.isNotBlank() && !themeResolved){
            val path=themeCover;val selectedClub=club;val profile=identity
            images?.color(path,eager=true){color->
                if(active && who()==profile && identity==profile && club==selectedClub && themeCover==path){themeResolved=true;if(ui.scopedReadingColor(color))changed()}
            }
        }
    }
    private fun date(ms:Long,pattern:String="dd/MM 'às' HH'h'mm")=SimpleDateFormat(pattern,Locale("pt","BR")).format(Date(ms))
    private fun instant(value:String)=runCatching {java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",Locale.US).apply {timeZone=TimeZone.getTimeZone("UTC")}.parse(value.take(19))!!.time}.getOrDefault(0L)
    private fun iso(ms:Long)=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US).apply {timeZone=TimeZone.getTimeZone("UTC")}.format(Date(ms))
    private fun duration(ms:Long):String {val minutes=ms.coerceAtLeast(0)/60000;return if(minutes>=60)"${minutes/60}h${(minutes%60).toString().padStart(2,'0')}min" else if(minutes>0)"$minutes min" else "${ms.coerceAtLeast(0)/1000} s"}
    private fun tracked(d:Dialog){dialogs.add(d);d.setOnDismissListener {dialogs.remove(d)};d.show();d.window?.setBackgroundDrawable(ui.shape(ui.surface,0))}
    private val tick=Runnable {scheduled=false;if(active && !closed && tab!="friends" && !people && clubPage!="books" && !busy && loaded && dialogs.isEmpty() && stage==null && SystemClock.elapsedRealtime()-editedAt>=3000){load(poll=true)};arm()}
    private fun arm(){if(active && !closed && !scheduled){scheduled=true;handler.postDelayed(tick,delay)}}
    private fun reset(preserveClub:Boolean=false){stopLive();generation++;requestConnection?.cancel();requestConnection=null;api?.cancel();busy=false;loaded=false;error="";rows=emptyList();cursor=null;messageBefore=null;if(!preserveClub){data=JsonObject();clubFeed=emptyList();clubFeedCursor=null};images?.close();images=api?.let {FriendCoverLoader(it::thumbnail)}}
    fun hide(resetSelection:Boolean=false){active=false;stopLive();generation++;requestConnection?.cancel();requestConnection=null;busy=false;loaded=false;scheduled=false;handler.removeCallbacksAndMessages(null);api?.close();api=null;images?.close();images=null;feedColors.clear();dialogs.toList().forEach {it.dismiss()};dialogs.clear();rows=emptyList();data=JsonObject();identity="";friends.hide(resetSelection);if(resetSelection){club=null;stage=null;round=null;clubPage="home";people=false;inviteClub=null;draft="";invited.clear()}}
    fun close(){hide();closed=true;friends.close();worker.shutdownNow();liveWorker.shutdownNow();messagesWorker.shutdownNow()}
    private fun draftOwner()=java.security.MessageDigest.getInstance("SHA-256").digest(who().toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun save(out:Bundle){friends.save(out);out.putString("social.tab",tab);out.putString("social.club",club);out.putString("social.round",round);out.putString("social.stage",stage);out.putString("social.clubPage",clubPage);out.putString("social.draft",draft.take(2000));out.putString("social.draftOwner",draftOwner())}
    fun restore(state:Bundle?){friends.restore(state);tab=state?.getString("social.tab").takeIf {it in listOf("feed","friends","clubs")} ?: "feed";club=state?.getString("social.club")?.let(::id);round=state?.getString("social.round")?.let(::id);stage=state?.getString("social.stage")?.let(::id);clubPage=state?.getString("social.clubPage").takeIf {it in listOf("home","history","settings","members")} ?: "home";draft=if(state?.getString("social.draftOwner")==draftOwner())state.getString("social.draft").orEmpty().take(2000) else ""}
    fun back():Boolean {if(stage!=null && draft.isNotBlank()){confirm("Descartar comentário não publicado?"){draft="";back()};return true};when {people->{people=false;inviteClub=null};stage!=null->{stage=null;draft=""};club!=null && clubPage!="home"->{clubPage="home"};club!=null && round!=null->{round=null;clubPage="history"};tab=="friends"->return friends.back();club!=null->{club=null;round=null;clubPage="home"};else->return false};reset(preserveClub=club!=null);changed();scrollTop();return true}
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
                eventsSeen++;discussionControls();refreshMessages()
            }else {loaded=false;load()}
        }
    }
    private fun load(more:Boolean=false,poll:Boolean=false){
        if(tab=="friends" && !people)return
        if(club!=null && !people && stage!=null && data.s("id").isBlank()){
            request("/v1/clubs/$club"+(round?.let {"?round_id=$it"} ?: "")){response->data=response;stageInfo=data.list("stages",20).firstOrNull {it.s("id")==stage} ?: JsonObject();load()};return
        }
        val path=when {people->"/v1/friends/people"+(if(more && cursor!=null)"?cursor=$cursor" else "");tab=="feed"->"/v1/friends/feed"+(if(more && cursor!=null)"?cursor=$cursor" else "");club==null->"/v1/clubs";clubPage=="books"->"/v1/clubs/$club/books?q=${java.net.URLEncoder.encode(booksQuery,"UTF-8")}"+(if(more && cursor!=null)"&cursor=$cursor" else "");stage!=null->"/v1/clubs/$club/stages/$stage/messages"+(messageBefore?.let {"?before=$it"} ?: "");else->"/v1/clubs/$club"+(round?.let {"?round_id=$it"} ?: "")}
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
        if(club==null || data.o("round").s("state")=="voting")return
        val position=activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll)?.scrollY ?: 0
        request("/v1/clubs/$club/feed?round_id=${data.o("round").s("id")}"+(if(more && clubFeedCursor!=null)"&cursor=$clubFeedCursor" else "")){response->
            clubFeed=(if(more)clubFeed+response.list("items",40) else if(poll && position>ui.dp(50))clubFeed else response.list("items",40)).distinctBy {it.s("id")}.take(200)
            if(!poll || position<=ui.dp(50))clubFeedCursor=response.s("next_cursor").ifBlank {null}
            changed()
            if(poll)activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll)?.post {if(active)activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll)?.scrollTo(0,position)}
        }
    }
    fun render(parent:LinearLayout){
        if(closed)return
        active=true
        if(identity!=who()) {if(identity.isNotBlank()){dialogs.toList().forEach {it.dismiss()};friends.hide(resetSelection=true);club=null;round=null;stage=null;people=false;inviteClub=null;draft="";invited.clear()};reset();api?.close();identity=who();api=AccountApi(accounts.address,accounts.catalogToken().orEmpty());images=FriendCoverLoader(api!!::thumbnail)}
        if(!accounts.signedIn){title(parent,"AMIGOS",30);text(parent,"Entre para ver seus amigos.",14,ui.muted);return}
        updateTheme()
        arm()
        if(tab=="friends" && !people && friends.inDetail){friends.render(parent);return}
        val header=ui.row().apply {gravity=Gravity.CENTER_VERTICAL};parent.addView(header,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(16)})
        if(people || club!=null)ui.icon(header,"back","Voltar",40){back()}
        title(header,if(people)"ADICIONAR PESSOAS" else if(club!=null)data.s("name").ifBlank {"CLUBE"} else "AMIGOS",28).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
        if(tab=="clubs" && club==null && !people)action(header,"Criar clube",onClick=::createClub).tag="club-create"
        SocialRefresh.button(activity,ui,header,if(tab=="friends" && !people)friends.isLoading else busy,"Atualizar"){if(tab=="friends" && !people)friends.refresh()else if(stage!=null){eventsSeen++;refreshMessages()}else {load()}}
        if(club!=null && !people)ui.icon(header,"more","Opções do clube",44){clubOptions()}.tag="club-options"
        if(!people && club==null){
            val tabs=ui.row();parent.addView(tabs)
            listOf("feed" to "Feed","clubs" to "Clubes","friends" to "Meus amigos").forEach {(key,label)->
                val cell=ui.column().apply {tag="social-tab:$key";isSelected=tab==key;isFocusable=true;setOnClickListener {friends.hide(resetSelection=true);tab=key;club=null;stage=null;round=null;clubPage="home";draft="";reset();changed()}}
                tabs.addView(cell,LinearLayout.LayoutParams(0,-2,1f));text(cell,label,12,if(tab==key)ui.accent else ui.muted).apply {gravity=Gravity.CENTER;minHeight=ui.dp(40)}
                cell.addView(View(activity).apply {setBackgroundColor(if(tab==key)ui.accent else ui.lineColor)},LinearLayout.LayoutParams(-1,ui.dp(if(tab==key)2 else 1)))
            }
        }
        if(tab=="friends" && !people){friends.render(parent);return}
        if(error.isNotBlank()){text(parent,error,13,ui.muted);action(parent,"Tentar novamente"){load()}}
        val body=ui.column();parent.addView(body);images?.attach(body)
        when {people->peopleView(body);tab=="feed"->feedView(body);club==null->clubsView(body);clubPage=="books"->clubBooks(body);stage!=null->discussionView(body);else->clubView(body)}
        if(!loaded && !busy && error.isBlank())handler.post {if(active && !loaded && !busy)load()}
    }
    private fun picture(parent:LinearLayout,path:String,width:Int=55,height:Int=82,palette:DisplayUi=ui):ImageView {
        val image=ImageView(activity).apply {scaleType=ImageView.ScaleType.FIT_CENTER;background=palette.shape(palette.activeSurface,0);setImageDrawable(palette.symbol("books",palette.muted,30))}
        parent.addView(image,LinearLayout.LayoutParams(ui.dp(width),ui.dp(height)).apply {marginEnd=ui.dp(12)});images?.bind(image,path);return image
    }
    private fun consult(book:JsonObject,owner:String){
        val client=api ?: return;val loader=FriendCoverLoader(client::thumbnail)
        val dialog=BookInfoDialog.show(activity,ui,BookInfoDialog.Info(book.s("title"),book.s("author"),book.s("publisher"),book.s("genre"),book.n("pages").takeIf {it>0}?.toInt(),page=book.n("page").toInt(),owner=owner),bind={image,root->loader.attach(root);loader.bind(image,book.s("cover"))},onClose={loader.close()})
        dialogs.add(dialog);dialog.setOnDismissListener {loader.close();dialogs.remove(dialog)}
    }
    private fun feedPalette(path:String):DisplayUi {
        val palette=DisplayUi(activity).apply {scopedReadingColor(feedColors[path])}
        if(path.isNotBlank() && !feedColors.containsKey(path)){
            val profile=identity
            images?.color(path){color->if(active && who()==profile && identity==profile){
                if(feedColors.size>=400)feedColors.remove(feedColors.keys.first())
                feedColors[path]=color
                if(tab=="feed" && !people){val scroll=activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll);val y=scroll?.scrollY ?: 0;changed();scroll?.post {if(active && tab=="feed" && !people)scroll.scrollTo(0,y)}}
            }}
        }
        return palette
    }
    private fun feedView(parent:LinearLayout){
        var day=""
        rows.forEach {event->
            val whenMs=event.n("occurred_at");val key=ReadingDays.at(whenMs)
            if(key!=day){day=key;val today=ReadingDays.at(System.currentTimeMillis());val yesterday=ReadingDays.at(System.currentTimeMillis()-86400000);section(parent,when(key){today->"HOJE";yesterday->"ONTEM";else->ReadingDays.label(key).uppercase()})}
            val actor=event.o("actor");val book=event.o("book");val palette=feedPalette(book.s("cover"));val card=card(parent,"feed-event:${event.s("id")}",palette)
            fun label(target:LinearLayout,value:String,size:Int=11,color:Int=palette.text)=palette.label(target,value,size,color)
            fun number(target:LinearLayout,value:String)=label(target,value,20,palette.accent).apply {typeface=Typeface.create("serif",Typeface.NORMAL)}
            val header=ui.row().apply {gravity=Gravity.TOP};card.addView(header)
            val avatar=TextView(activity).apply {text=actor.s("username").take(1).uppercase();gravity=Gravity.CENTER;textSize=18f;setTextColor(palette.accent);typeface=Typeface.create("serif",Typeface.NORMAL);background=android.graphics.drawable.GradientDrawable().apply {shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(palette.activeSurface);setStroke(ui.dp(1).coerceAtLeast(1),palette.lineColor)}}
            header.addView(avatar,LinearLayout.LayoutParams(ui.dp(32),ui.dp(32)).apply {marginEnd=ui.dp(10)})
            val identity=ui.column();header.addView(identity,LinearLayout.LayoutParams(0,-2,1f))
            label(identity,"@${actor.s("username")}",13).apply {typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);maxLines=1;ellipsize=TextUtils.TruncateAt.END}
            label(identity,when(event.s("kind")){"started"->"Começou a ler";"finished"->"Terminou de ler";"rated"->"Avaliou";else->"Leu"},10,palette.muted)
            label(header,date(whenMs,"HH'h'mm"),10,palette.muted).layoutParams=LinearLayout.LayoutParams(-2,-2)
            val row=ui.row().apply {gravity=Gravity.TOP};card.addView(row,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(10)});picture(row,book.s("cover"),50,75,palette)
            val labels=ui.column();row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            label(labels,book.s("title"),22).apply {typeface=Typeface.create("serif",Typeface.NORMAL);maxLines=2};label(labels,book.s("author"),10,palette.muted).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END}
            val metrics=ui.row().apply {gravity=Gravity.BOTTOM};labels.addView(metrics,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(5)})
            val pages=ui.column();metrics.addView(pages,LinearLayout.LayoutParams(0,-2,1f))
            val time=ui.column();metrics.addView(time,LinearLayout.LayoutParams(0,-2,1f))
            if(event.s("kind")=="read"){
                if(event.y("pages_known")){number(pages,"${event.n("pages")} páginas");label(pages,"Páginas ${event.n("start_page")} → ${event.n("end_page")}",9,palette.muted)}
                number(time,duration(event.n("duration_ms"))).gravity=Gravity.END;label(time,"de leitura",9,palette.muted).gravity=Gravity.END
            }else {
                event.get("rating")?.takeUnless {it.isJsonNull}?.runCatching {asDouble}?.getOrNull()?.let {rating->label(pages,"★ $rating",15,palette.accent)}
                if(event.n("total_duration_ms")>0){number(time,duration(event.n("total_duration_ms"))).gravity=Gravity.END;label(time,"de leitura",9,palette.muted).gravity=Gravity.END}
            }
            card.setOnClickListener {consult(book,actor.s("username"))};card.isFocusable=true
        }
        if(loaded && !busy && rows.isEmpty() && error.isBlank())text(parent,"Nenhuma atividade dos seus amigos.",13,ui.muted)
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
    private fun clubsView(parent:LinearLayout){

        section(parent,"SEUS CLUBES")
        rows.forEach {entry->val identifier=id(entry.s("id")) ?: return@forEach
            val block=card(parent,"club-card:$identifier");val heading=ui.row().apply {gravity=Gravity.TOP};block.addView(heading,LinearLayout.LayoutParams(-1,-2));picture(heading,entry.o("book").s("cover"),44,66)
            val labels=ui.column();heading.addView(labels,LinearLayout.LayoutParams(0,-2,1f));title(labels,entry.s("name"),23);if(entry.o("book").s("title").isNotBlank())text(labels,entry.o("book").s("title"),12,ui.accent)
            val controls=ui.column().apply {gravity=Gravity.END};heading.addView(controls,LinearLayout.LayoutParams(-2,-2).apply {marginStart=ui.dp(8)})
            text(controls,if(entry.s("status")=="invited")"Convite" else if(entry.s("role")=="admin")"Admin" else "Membro",10,ui.muted).gravity=Gravity.END
            if(entry.s("status")=="invited"){
                val buttons=ui.row();block.addView(buttons);action(buttons,"Aceitar"){mutate("/v1/clubs/$identifier/join")}.apply {tag="club-join:$identifier";layoutParams=LinearLayout.LayoutParams(0,-2,1f)};action(buttons,"Recusar"){mutate("/v1/clubs/$identifier/leave")}.layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            }else action(controls,"Abrir clube"){club=identifier;round=null;stage=null;clubPage="home";reset();load();scrollTop()}.tag="club-open:$identifier"
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
    private fun clubNavigate(page:String){clubPage=page;stage=null;draft="";reset(preserveClub=true);load();scrollTop()}
    private fun openDiscussion(s:JsonObject){stage=id(s.s("id"));stageInfo=s;draft="";reset(preserveClub=true);load();scrollTop()}
    private fun chooseBook(){booksQuery="";clubNavigate("books")}
    private fun clubBooks(parent:LinearLayout){
        title(parent,"Livros dos participantes",23)
        val search=ui.row();parent.addView(search)
        val field=EditText(activity).apply {hint="Título ou autor";setText(booksQuery);setTextColor(ui.text);setHintTextColor(ui.muted);isSingleLine=true;filters=arrayOf(InputFilter.LengthFilter(100));tag="club-books-query"}
        search.addView(field,LinearLayout.LayoutParams(0,-2,1f))
        action(search,"Buscar"){booksQuery=field.text.toString().trim();cursor=null;load()}
        rows.forEach {book->val owner=book.o("owner");val item=card(parent,"club-candidate:${owner.s("id")}:${book.s("id")}")
            val row=ui.row().apply {gravity=Gravity.TOP};item.addView(row);picture(row,book.s("cover"),48,72)
            val labels=ui.column();row.addView(labels,LinearLayout.LayoutParams(0,-2,1f));title(labels,book.s("title"),21);text(labels,book.s("author"),11,ui.muted);text(labels,"${book.n("pages")} páginas · @${owner.s("username")}",10,ui.muted)
            action(item,"Indicar para votação"){
                mutate("/v1/clubs/$club/proposals",body=JsonObject().apply {addProperty("book_id",book.s("id"));addProperty("source_user_id",owner.s("id"))}){clubPage="home";rows=emptyList()}
            }.tag="club-nominate:${owner.s("id")}:${book.s("id")}"
        }
        if(loaded && rows.isEmpty() && !busy && error.isBlank())text(parent,"Nenhum livro encontrado nas estantes sincronizadas.",12,ui.muted)
        if(cursor!=null && rows.size<400)action(parent,"Mais livros"){load(more=true)}
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
        val parent=card(root,"club-reading-card")
        val row=ui.row().apply {gravity=Gravity.TOP};parent.addView(row);picture(row,selected.s("cover"),78,117)
        val labels=ui.column();row.addView(labels,LinearLayout.LayoutParams(0,-2,1f));text(labels,if(state=="completed")"LEITURA CONCLUÍDA" else "LEITURA ATUAL",9,ui.accent);title(labels,selected.s("title"),25);text(labels,selected.s("author"),12,ui.muted);text(labels,"${selected.n("pages")} páginas",11,ui.muted)
        line(parent)
        val deadline=ui.row().apply {gravity=Gravity.CENTER_VERTICAL};parent.addView(deadline,LinearLayout.LayoutParams(-1,-2))
        val timing=ui.column();deadline.addView(timing,LinearLayout.LayoutParams(0,-2,1f));text(timing,"PRAZO FINAL",9,ui.muted)
        val remaining=instant(r.s("due_at"))-System.currentTimeMillis();val days=kotlin.math.ceil(remaining/86400000.0).toLong()
        text(timing,if(state=="completed")"Encerrada" else if(remaining<=0)"Prazo encerrado" else if(days==1L)"Falta 1 dia" else "Faltam $days dias",12,ui.accent)
        val due=ui.column().apply {gravity=Gravity.END};deadline.addView(due,LinearLayout.LayoutParams(-2,-2))
        title(due,date(instant(r.s("due_at")),"dd/MM/yyyy"),23).apply {setTextColor(ui.accent);tag="club-final-deadline"}
        text(due,date(instant(r.s("due_at")),"HH'h'mm"),11,ui.muted).gravity=Gravity.END
        val members=data.list("members",100).filter {it.s("status")=="active"};val pages=selected.n("pages").coerceAtLeast(1)
        val average=if(members.isEmpty())0 else (members.sumOf {it.n("page").coerceAtMost(pages)}*100/(members.size*pages)).toInt()
        ui.progress(parent,average)
        val progress=ui.row();parent.addView(progress);text(progress,"$average% do grupo",12,ui.accent).layoutParams=LinearLayout.LayoutParams(0,-2,1f);text(progress,"${members.count {it.n("page")>=pages}}/${members.size} concluíram",11,ui.muted).layoutParams=LinearLayout.LayoutParams(-2,-2)
        if(state=="reading"){
            val mine=ui.row().apply {gravity=Gravity.CENTER_VERTICAL};parent.addView(mine,LinearLayout.LayoutParams(-1,-2))
            text(mine,"Página ${members.firstOrNull {it.s("username")==accounts.username}?.n("page") ?: 0}",12,ui.accent).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            action(mine,"Atualizar minha página"){
            val body=ui.column().apply {setPadding(ui.dp(20),0,ui.dp(20),0)};val current=members.firstOrNull {it.s("username")==accounts.username}?.n("page") ?: 0
            val page=input(body,"Página atual",current.toString(),numeric=true,limit=6)
            tracked(AlertDialog.Builder(activity).setTitle("Meu progresso · $pages páginas").setView(body).setPositiveButton("Salvar"){_,_->mutate("/v1/clubs/$club/progress","PATCH",JsonObject().apply {addProperty("page",page.text.toString().toIntOrNull() ?: -1)})}.setNegativeButton("Cancelar",null).create())
        }
        }
        section(root,"ETAPAS E COMENTÁRIOS")
        val stages=data.list("stages",20)
        if(stages.isEmpty())text(root,"Nenhuma etapa definida.",12,ui.muted)
        stages.forEachIndexed {index,s->
            val panel=card(root,"club-stage:${s.s("id")}");val heading=ui.row().apply {gravity=Gravity.TOP};panel.addView(heading,LinearLayout.LayoutParams(-1,-2))
            val limit=ui.column();heading.addView(limit,LinearLayout.LayoutParams(0,-2,1f));text(limit,"ETAPA ${index+1}",9,ui.accent);title(limit,"Página ${s.n("page_limit")}",23)
            val schedule=ui.column().apply {gravity=Gravity.END};heading.addView(schedule,LinearLayout.LayoutParams(-2,-2))
            text(schedule,date(instant(s.s("discuss_at")),"dd/MM · HH'h'mm"),12).gravity=Gravity.END
            text(schedule,if(s.y("is_open"))"Discussão aberta" else "Agendada",10,ui.muted).gravity=Gravity.END
            val footer=ui.row().apply {gravity=Gravity.CENTER_VERTICAL};panel.addView(footer,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(8)})
            text(footer,"${members.count {it.n("page")>=s.n("page_limit")}}/${members.size} chegaram",10,ui.muted).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            action(footer,"Comentários (${s.n("comments")})"){openDiscussion(s)}.apply {isEnabled=s.y("is_open") && !busy;tag="club-discussion:${s.s("id")}"}
        }
        section(root,"FEED DO LIVRO")
        clubFeed.forEach {event->val panel=card(root,"club-feed:${event.s("id")}");val body=event.o("data");val row=ui.row();panel.addView(row);text(row,"@${event.o("actor").s("username")}",12).layoutParams=LinearLayout.LayoutParams(0,-2,1f);text(row,date(event.n("occurred_at")),9,ui.muted).layoutParams=LinearLayout.LayoutParams(-2,-2)
            when(event.s("kind")){
                "progress"->{title(panel,"Página ${body.n("page")} de $pages",21);text(panel,if(body.n("page")>=body.n("previous_page"))"Atualizou a leitura" else "Corrigiu o progresso",10,ui.muted)}
                "comment"->{text(panel,"Comentou · até a página ${body.n("page_limit")}",10,ui.accent);text(panel,body.s("body"),13).maxLines=4;action(ui.row().apply {gravity=Gravity.END;panel.addView(this,LinearLayout.LayoutParams(-1,-2))},"Ver discussão"){data.list("stages",20).firstOrNull {it.s("id")==event.s("stage_id")}?.let(::openDiscussion)}}
                "read"->{val metrics=ui.row();panel.addView(metrics,LinearLayout.LayoutParams(-1,-2))
                    val pagesRead=ui.column();metrics.addView(pagesRead,LinearLayout.LayoutParams(0,-2,1f));title(pagesRead,"${body.n("pages")} páginas",21);text(pagesRead,"${body.n("start_page")} → ${body.n("end_page")}",10,ui.muted)
                    val time=ui.column().apply {gravity=Gravity.END};metrics.addView(time,LinearLayout.LayoutParams(-2,-2));title(time,duration(body.n("duration_ms")),21).setTextColor(ui.accent);text(time,"de leitura",10,ui.muted)
                }
                "finished"->{title(panel,"Terminou de ler",21);if(body.n("total_duration_ms")>0)text(panel,duration(body.n("total_duration_ms"))+" de leitura",11,ui.accent)}
                "rated"->{title(panel,"Avaliou a leitura",21);text(panel,"★ "+body.s("rating"),13,ui.accent)}
                "started"->title(panel,"Começou a leitura",20)
                "schedule"->{title(panel,"Prazo final atualizado",20);text(panel,date(instant(body.s("due_at")),"dd/MM/yyyy 'às' HH'h'mm"),12,ui.accent)}
                "stage"->title(panel,"Nova etapa · página ${body.n("page_limit")}",20)
                "completed"->title(panel,"Leitura concluída pelo clube",20)
            }
        }
        if(clubFeed.isEmpty() && !busy)text(root,"Nenhuma atividade nesta leitura ainda.",12,ui.muted)
        if(clubFeedCursor!=null && clubFeed.size<200)action(root,"Mais atividades"){loadClubFeed(more=true)}
    }
    private fun clubVoting(root:LinearLayout){
        section(root,"VOTAÇÃO DO CLUBE");title(root,"Escolha da próxima leitura",24)
        action(root,"Escolher nas estantes dos participantes",onClick=::chooseBook).tag="club-propose"
        data.list("proposals",30).forEach {p->val panel=card(root,"club-proposal:${p.s("id")}");val row=ui.row().apply {gravity=Gravity.TOP};panel.addView(row);picture(row,p.s("cover"),48,72);val labels=ui.column();row.addView(labels,LinearLayout.LayoutParams(0,-2,1f));title(labels,p.s("title"),21);text(labels,p.s("author"),11,ui.muted);text(labels,"${p.n("votes")} votos · ${p.n("pages")} páginas",11,ui.accent)
            action(panel,if(p.y("mine"))"Seu voto" else "Votar"){mutate("/v1/clubs/$club/vote",body=JsonObject().apply {addProperty("proposal_id",p.s("id"))})}.apply {isEnabled=!busy;tag="club-vote:${p.s("id")}"}
        }
    }
    private fun clubHistory(root:LinearLayout){
        title(root,"Histórico de leituras",24)
        val history=data.list("history",100).filter {it.s("state")=="completed"}
        history.forEach {item->val panel=card(root,"club-history:${item.s("id")}");val row=ui.row().apply {gravity=Gravity.TOP};panel.addView(row);picture(row,item.s("cover"),54,81);val labels=ui.column();row.addView(labels,LinearLayout.LayoutParams(0,-2,1f));title(labels,item.s("title"),22);text(labels,item.s("author"),11,ui.muted);text(labels,item.s("completed_at").takeIf {it.isNotBlank()}?.let {"Concluído em "+date(instant(it),"dd/MM/yyyy")} ?: "Leitura concluída",10,ui.accent)
            action(panel,"Ver leitura e discussões"){round=id(item.s("id"));clubNavigate("home")}.tag="club-history-open:${item.s("id")}"
        }
        if(history.isEmpty())text(root,"Nenhuma leitura concluída ainda.",12,ui.muted)
    }
    private fun clubMembers(root:LinearLayout){
        title(root,"Participantes",24)
        val admin=data.s("role")=="admin"
        data.list("members",100).forEach {m->val panel=card(root);val row=ui.row();panel.addView(row);val labels=ui.column();row.addView(labels,LinearLayout.LayoutParams(0,-2,1f));text(labels,"@${m.s("username")}",14);text(labels,if(m.s("status")=="invited")"Convidado" else "${if(m.s("role")=="admin")"Admin" else "Membro"} · página ${m.n("page")}",10,ui.muted)
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
        title(root,"Configurações do clube",24)
        val people=card(root);title(people,"Participantes",21)
        action(people,"Adicionar participantes"){inviteClub=club;this.people=true;invited.clear();reset(preserveClub=true);load()}.tag="club-invite"
        action(people,"Gerenciar participantes"){clubNavigate("members")}
        val r=data.o("round");val state=r.s("state")
        val reading=card(root);title(reading,if(state=="voting")"Votação" else "Leitura",21)
        if(state=="voting"){
            action(reading,"Ver votação"){clubNavigate("home")}
            val proposals=data.list("proposals",30)
            if(proposals.any {it.n("votes")>0})action(reading,"Definir livro e prazo final"){
                val most=proposals.maxOf {it.n("votes")};val winners=proposals.filter {it.n("votes")==most}
                if(winners.size==1)startReading(winners[0])else tracked(AlertDialog.Builder(activity).setTitle("Desempatar votação").setItems(winners.map {it.s("title")}.toTypedArray()){_,i->startReading(winners[i])}.setNegativeButton("Cancelar",null).create())
            }
        }else if(state=="reading"){
            text(reading,"PRAZO FINAL DA LEITURA",10,ui.muted);title(reading,date(instant(r.s("due_at")),"dd/MM/yyyy 'às' HH'h'mm"),22)
            action(reading,"Alterar prazo final"){adjustDeadline(r)}
            action(reading,"Encerrar leitura e guardar no histórico"){confirm("Concluir esta leitura do clube?"){mutate("/v1/clubs/$club/complete"){clubPage="history";round=null}}}
            val steps=card(root);title(steps,"Etapas e discussões",21);action(steps,"Criar etapa"){stageEditor(null)}
            data.list("stages",20).forEach {s->line(steps);text(steps,"Até página ${s.n("page_limit")} · ${date(instant(s.s("discuss_at")))}",12);action(steps,"Editar etapa / liberar comentários"){stageEditor(s)}}
        }else action(reading,"Abrir nova votação"){mutate("/v1/clubs/$club/rounds"){round=null;clubPage="home"}}
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
        tracked(AlertDialog.Builder(activity).setTitle(if(old==null)"Nova etapa" else "Ajustar etapa").setView(body).setPositiveButton("Salvar"){_,_->mutate("/v1/clubs/$club/stages"+(old?.let {"/${it.s("id")}"} ?: ""),if(old==null)"POST" else "PATCH",JsonObject().apply {addProperty("page_limit",page.text.toString().toIntOrNull() ?: 0);addProperty("discuss_at",iso(at));addProperty("is_open",open.isChecked)})}.setNegativeButton("Cancelar",null).create())
    }
    private var composer:EditText?=null
    private var sendComment:TextView?=null
    private fun discussionControls(){
        composer?.isEnabled=loaded && !busy && stageInfo.y("is_open")
        sendComment?.isEnabled=loaded && !busy && draft.isNotBlank() && stageInfo.y("is_open")
        liveStatus?.text=error
        liveStatus?.visibility=if(error.isBlank())View.GONE else View.VISIBLE
    }
    private fun stopLive(){
        liveEpoch++;liveTag?.let {api?.cancel(it)};liveTag=null;livePath=""
        api?.cancel(fetchTag);messagesFetching=false;eventsSeen=0;eventsApplied=0;liveRetry=1000
        handler.removeCallbacks(liveRefresh);handler.removeCallbacks(liveReconnect)
        messageList=null;composer=null;sendComment=null;liveStatus=null
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
    private fun discussionView(parent:LinearLayout){
        title(parent,"Comentários da etapa",24)
        if(stageInfo.s("id").isBlank())return
        val context=ui.row();parent.addView(context,LinearLayout.LayoutParams(-1,-2))
        text(context,"Até a página ${stageInfo.n("page_limit")}",12,ui.accent).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
        text(context,date(instant(stageInfo.s("discuss_at")),"dd/MM HH:mm"),11,ui.muted).apply {gravity=Gravity.END;layoutParams=LinearLayout.LayoutParams(-2,-2)}
        line(parent)
        val field=input(parent,"Escreva seu comentário",draft,limit=2000).apply {tag="club-message";minLines=2;maxLines=5;isEnabled=stageInfo.y("is_open")};composer=field
        val actions=ui.row().apply {gravity=Gravity.END};parent.addView(actions,LinearLayout.LayoutParams(-1,-2))
        val send=action(actions,"Publicar comentário"){mutate("/v1/clubs/$club/stages/$stage/messages",body=JsonObject().apply {addProperty("body",draft)}){draft="";messageBefore=null}}.apply {tag="club-send"};sendComment=send
        field.addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,a:Int,c:Int,f:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){draft=s.toString();editedAt=SystemClock.elapsedRealtime();discussionControls()};override fun afterTextChanged(s:Editable?){} })
        liveStatus=text(parent,error,12,ui.muted);discussionControls()
        val list=ui.column();parent.addView(list,LinearLayout.LayoutParams(-1,-2));messageList=list;renderMessages(list)
        if(loaded)startLive()
    }
    private fun renderMessages(parent:LinearLayout){
        val scroll=activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll);val position=scroll?.scrollY ?: 0
        parent.removeAllViews()
        if(messageBefore!=null)action(parent,"Mensagens recentes"){messageBefore=null;stopLive();load()}
        rows.forEach {m->val message=card(parent,"club-discussion-message:${m.n("id")}");val header=ui.row();message.addView(header,LinearLayout.LayoutParams(-1,-2))
            text(header,"@${m.s("username")}",11,ui.muted).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            text(header,date(instant(m.s("sent_at"))),10,ui.muted).apply {gravity=Gravity.END;layoutParams=LinearLayout.LayoutParams(-2,-2)}
            text(message,m.s("body"),14).apply {tag="club-message:${m.n("id")}";if(m.y("can_delete"))setOnLongClickListener {confirm("Apagar esta mensagem?"){mutate("/v1/clubs/$club/stages/$stage/messages/${m.n("id")}","DELETE")};true}}
        }
        if(cursor!=null)action(parent,"Mensagens anteriores"){val before=cursor;stopLive();messageBefore=before;load()}
        if(loaded && !busy && rows.isEmpty() && error.isBlank())text(parent,"Nenhum comentário ainda.",13,ui.muted)
        scroll?.post {if(active && messageList===parent)scroll.scrollTo(0,position)}
    }
}
