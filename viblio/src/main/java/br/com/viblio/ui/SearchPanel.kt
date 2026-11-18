package br.com.viblio.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import br.com.centralcasa.books.catalog.*
import br.com.centralcasa.ui.DisplayUi

class SearchPanel(private val activity:Activity,private val ui:DisplayUi,private val catalog:BookCatalog,private val manual:((String)->Unit)?=null, private val scan:(()->Unit)?=null,
                  private val add:(CatalogBook?)->Unit) {
    private val handler=Handler(Looper.getMainLooper())
    private val covers=SearchCoverLoader(activity)
    private var query=""
    private var kind="all"
    private var results=emptyList<CatalogBook>()
    private var resultsQuery=""
    private var generation=0
    private var active=false
    private var closed=false
    private var pending:Runnable?=null
    private var status:TextView?=null
    private var rows:LinearLayout?=null
    private var infoDialog:android.app.AlertDialog?=null
    private var statusText="Busque no catálogo pelo título, autor ou ISBN."
    fun acceptIsbn(value:String){query=value;kind="all";results=emptyList();resultsQuery=""}
    fun save(state:Bundle) { state.putString("search.query",query);state.putString("search.kind",kind) }
    fun restore(state:Bundle?) { query=state?.getString("search.query").orEmpty();kind=state?.getString("search.kind").takeIf { it in listOf("all","book","manga","comic") } ?: "all" }
    fun hide() { infoDialog?.dismiss();infoDialog=null;active=false;generation++;pending?.let(handler::removeCallbacks);pending=null;covers.detach();status=null;rows=null }
    private fun cancelImages() { covers.reset() }
    fun close() { hide();closed=true;handler.removeCallbacksAndMessages(null);covers.close() }
    fun render(parent:LinearLayout) {
        active=true;cancelImages()
        val header=ui.row();parent.addView(header)
        ui.label(header,if(ui.rectilinear)"PESQUISA" else "Pesquisa",if(ui.rectilinear)30 else 36,bold=true).apply { typeface=Typeface.create("serif",if(ui.rectilinear)Typeface.NORMAL else Typeface.BOLD);layoutParams=LinearLayout.LayoutParams(0,-2,1f) }
        ui.action(header,"+ Cadastrar") {
            if(manual==null) add(null) else android.app.AlertDialog.Builder(activity).setTitle("Cadastrar manualmente")
                .setItems(arrayOf("Livro","Mangá","Quadrinho")) { _,index->manual.invoke(listOf("book","manga","comic")[index]) }.setNegativeButton("Cancelar",null).show()
        }.apply { layoutParams=LinearLayout.LayoutParams(-2,ui.dp(44));minHeight=ui.dp(44);textSize=12f;background=ui.outlined() }
        ui.label(parent,"Encontre sua próxima leitura",13,ui.muted)
        val field=EditText(activity).apply {
            hint="Título, autor ou ISBN";setText(query);textSize=15f;setTextColor(ui.text);setHintTextColor(ui.muted)
            isSingleLine=true;imeOptions=EditorInfo.IME_ACTION_SEARCH
            setCompoundDrawables(ui.symbol("search",ui.muted,20),null,null,null);compoundDrawablePadding=ui.dp(10)
            setPadding(ui.dp(14),ui.dp(14),ui.dp(14),ui.dp(14));background=ui.outlined(0x66151515)
        }
        val queryRow=ui.row();parent.addView(queryRow,LinearLayout.LayoutParams(-1,ui.dp(56)).apply {topMargin=ui.dp(18);bottomMargin=ui.dp(12)})
        queryRow.addView(field,LinearLayout.LayoutParams(0,-1,1f))
        if(scan!=null)ui.icon(queryRow,"barcode","Ler ISBN com a câmera",52){scan.invoke()}.apply {tag="search-scan-isbn"}

        val horizontal=HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled=false }
        val chips=ui.row();horizontal.addView(chips);parent.addView(horizontal,LinearLayout.LayoutParams(-1,-2))
        val labels=linkedMapOf("all" to "Todos","book" to "Livros","manga" to "Mangás","comic" to "Quadrinhos")
        fun repaintChips() {
            chips.removeAllViews()
            labels.forEach { (key,label)->
                val change={kind=key;repaintChips();drawResults()}
                if(ui.rectilinear){
                    val tab=ui.column().apply {isSelected=key==kind;isFocusable=true;contentDescription=label;setOnClickListener {change()}}
                    chips.addView(tab,LinearLayout.LayoutParams(ui.dp(if(key=="comic")94 else 72),-2))
                    ui.label(tab,label,11,if(key==kind)ui.accent else ui.muted).apply {gravity=Gravity.CENTER;minHeight=ui.dp(42);maxLines=1;setOnClickListener {change()};(layoutParams as LinearLayout.LayoutParams).bottomMargin=0}
                    tab.addView(View(activity).apply {setBackgroundColor(if(key==kind)ui.accent else ui.lineColor)},LinearLayout.LayoutParams(-1,ui.dp(if(key==kind)2 else 1)))
                }else ui.action(chips,label,key==kind){change()}.apply {
                    layoutParams=LinearLayout.LayoutParams(-2,ui.dp(44)).apply {marginEnd=ui.dp(6)};minHeight=ui.dp(44);setPadding(ui.dp(15),0,ui.dp(15),0);if(key!=kind)background=ui.outlined(0x33151515)
                }
            }
        }
        repaintChips()
        status=ui.label(parent,"",12,ui.muted).apply { setPadding(0,ui.dp(18),0,ui.dp(10));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        rows=ui.column().also { parent.addView(it);covers.attach(it) }
        ui.action(parent,if(catalog.configured) "Configurar catálogo" else "Conectar catálogo") { catalog.configure() }.apply { background=ui.ripple(android.graphics.Color.TRANSPARENT) }
        field.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun afterTextChanged(s:Editable?) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                query=s.toString();schedule()
            }
        })
        field.setOnEditorActionListener { _,action,_ -> if(action==EditorInfo.IME_ACTION_SEARCH) { schedule(0);true } else false }
        if(query.trim()==resultsQuery && results.isNotEmpty()) drawResults() else schedule()
    }
    private fun schedule(delay:Long=400) {
        val epoch=++generation;pending?.let(handler::removeCallbacks);cancelImages();rows?.removeAllViews()
        val text=query.trim()
        results=emptyList();resultsQuery=text
        if(!catalog.configured) { statusText="Conecte o catálogo para pesquisar. Sua biblioteca local continua disponível.";status?.text=statusText;return }
        if(text.length<3) { results=emptyList();resultsQuery="";statusText="Digite pelo menos três caracteres ou um ISBN.";status?.text=statusText;return }
        statusText="Pesquisando…";status?.text=statusText
        fun valid()=active && !closed && generation==epoch
        var titles=emptyList<CatalogBook>();var authors=emptyList<CatalogBook>();var received=0;var failures=0
        fun finish(response:Result<List<CatalogBook>>,byAuthor:Boolean=false) {
            if(!valid()) return
            received++
            response.fold({ if(byAuthor) authors=it else titles=it },{ failures++ })
            val merged=CatalogSearch.merge(titles,authors)
            if(merged.isNotEmpty() || received==2) {
                val changed=merged!=results
                results=merged;resultsQuery=text
                if(merged.isEmpty() && failures>0) {
                    statusText="Não foi possível completar a pesquisa. Tente novamente.";status?.text=statusText
                    rows?.let { target->target.removeAllViews();ui.action(target,"Tentar novamente") { schedule(0) } }
                } else { statusText="";if(changed || merged.isEmpty()) drawResults() }
            }
        }
        pending=Runnable {
            if(!valid()) return@Runnable
            val isbn=CatalogSearch.isbn(text)
            if(isbn!=null) { received=1;catalog.searchIsbn(isbn) { finish(it) } }
            else {
                catalog.search(text) { finish(it) }
                if(valid()) catalog.search("",author=text) { finish(it,true) }
            }
        }.also { handler.postDelayed(it,delay) }
    }
    private fun drawResults() {
        val target=rows ?: return
        cancelImages();target.removeAllViews()
        val shown=CatalogSearch.filter(results,kind)
        if(statusText.isNotBlank()) { status?.text=statusText;return }
        status?.text=if(shown.isEmpty()) "Nenhum resultado neste filtro. Você pode cadastrar manualmente." else "${shown.size} edições encontradas · “$resultsQuery”"
        shown.forEach { book ->
            val card=ui.row().apply { gravity=Gravity.TOP;setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12));background=ui.outlined(0x99151515.toInt()) }
            target.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=ui.dp(12) })
            val cover=ImageView(activity).apply { setImageDrawable(ui.symbol("book_nav",ui.muted,40));scaleType=ImageView.ScaleType.FIT_CENTER;background=ui.shape(ui.surface,7);clipToOutline=true;contentDescription="Capa de ${book.title}" }
            card.addView(cover,LinearLayout.LayoutParams(ui.dp(74),ui.dp(110)).apply { marginEnd=ui.dp(12) })
            val text=ui.column();card.addView(text,LinearLayout.LayoutParams(0,-2,1f))
            ui.label(text,book.title,19,bold=true).apply { typeface=Typeface.create("serif",Typeface.BOLD);maxLines=3 }
            if(book.author.isNotBlank()) ui.label(text,book.author,12,ui.muted)
            ui.label(text,CatalogSearch.edition(book),11,ui.muted)
            var selected=book
            ui.action(text,"Adicionar à Estante",true) { add(selected) }.apply { textSize=11f;minHeight=ui.dp(40) }
            ui.action(text,"Ver capas") {
                val requestQuery=resultsQuery
                catalog.chooseCover(book.id,book.source) { chosen->
                    if(active && !closed && requestQuery==resultsQuery && card.parent===target) chosen.fold({ choice->
                        selected=book.copy(coverUrl=choice.url);covers.bind(cover,choice.url)
                    },{ android.widget.Toast.makeText(activity,it.message ?: "Não foi possível listar as capas.",android.widget.Toast.LENGTH_LONG).show() })
                }
            }.apply { background=ui.ripple(android.graphics.Color.TRANSPARENT);minHeight=ui.dp(36);textSize=11f }
            card.tag="search-book:${book.id}"
            card.isFocusable=true;card.contentDescription="Informações de ${book.title}"
            card.setOnClickListener {
                if(infoDialog?.isShowing==true)return@setOnClickListener
                val dialogCovers=SearchCoverLoader(activity)
                infoDialog=BookInfoDialog.show(activity,ui,BookInfoDialog.Info(selected.title,selected.author,selected.publisher,listOf(selected.genre,selected.subgenre).filter(String::isNotBlank).joinToString(" · "),selected.pages,selected.isbn),
                    bind={image,root->dialogCovers.attach(root);dialogCovers.bind(image,selected.coverUrl)},onClose={dialogCovers.close();if(infoDialog?.isShowing!=true)infoDialog=null},add={add(selected)})
            }
            covers.bind(cover,book.coverUrl)
        }
    }
}
