package br.com.viblio.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.*
import br.com.centralcasa.R
import br.com.centralcasa.books.catalog.CatalogCover
import br.com.centralcasa.books.catalog.CatalogCoverPage
import br.com.centralcasa.ui.DisplayUi

object CoverGalleryDialog {
    fun show(activity:Activity,variants:List<CatalogCover>,more:((Int,(Result<CatalogCoverPage>)->Unit)->Unit)?=null,nextCursor:Int?=null,selected:(CatalogCover)->Unit):AlertDialog {
        val ui=DisplayUi(activity)
        val loader=SearchCoverLoader(activity)
        val body=ui.column().apply {tag="catalog-cover-grid";setPadding(ui.dp(10),ui.dp(12),ui.dp(10),ui.dp(12))}
        val shell=ui.column().apply {addView(ScrollView(activity).apply {addView(body)},LinearLayout.LayoutParams(-1,ui.dp(360)))}
        val dialog=AlertDialog.Builder(activity).setTitle("Capas do catálogo").setView(shell).setNegativeButton("Fechar",null).create()
        var alternative=0
        var cursor=nextCursor
        var count=0
        var row:LinearLayout?=null
        val seen=mutableSetOf<String>()
        loader.attach(body)
        fun add(covers:List<CatalogCover>){covers.filter {seen.add(it.url)}.forEach {cover->
            if(count%3==0){
                row=ui.row().apply {tag="catalog-cover-row";gravity=android.view.Gravity.TOP}
                body.addView(row,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(12)})
                repeat(3){row!!.addView(FrameLayout(activity),LinearLayout.LayoutParams(0,-2,1f).apply {marginStart=ui.dp(3);marginEnd=ui.dp(3)})}
            }
            val slot=row!!.getChildAt(count++%3) as FrameLayout
            val label=if(cover.primary)"Desta edição" else "Opção ${++alternative}"
            val metadata=listOf(cover.publisher,cover.sequence?.let {"Volume $it"}.orEmpty(),cover.pages?.let {"$it páginas"}.orEmpty(),cover.isbn.takeIf(String::isNotBlank)?.let {"ISBN $it"}.orEmpty()).filter(String::isNotBlank)
            val cell=ui.column().apply {
                tag="catalog-cover:${cover.url}";setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(6));background=ui.outlined();isFocusable=true
                contentDescription=(listOf("Usar esta capa",label)+metadata).joinToString(" · ")
                setOnClickListener {dialog.dismiss();selected(cover)}
            }
            slot.addView(cell,FrameLayout.LayoutParams(-1,-2))
            val image=object:ImageView(activity){override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
                super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec((View.MeasureSpec.getSize(widthMeasureSpec)*1.5f).toInt().coerceAtLeast(1),View.MeasureSpec.EXACTLY))
            }}.apply {setImageResource(R.drawable.ic_books_nav);scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription=if(cover.primary)"Capa principal" else "Capa alternativa"}
            cell.addView(image,LinearLayout.LayoutParams(-1,-2))
            ui.label(cell,label,11,bold=true).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;gravity=android.view.Gravity.CENTER}
            if(metadata.isNotEmpty())ui.label(cell,metadata.joinToString(" · "),9,ui.muted).apply {maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END;gravity=android.view.Gravity.CENTER}
            loader.bind(image,cover.url)
        }}
        add(variants)
        ui.action(shell,"Carregar mais capas") {
            val offset=cursor
            if(offset!=null && more!=null){
                shell.getChildAt(1).isEnabled=false
                more(offset){result->if(dialog.isShowing)result.fold({page->
                    add(page.items);cursor=page.nextCursor
                    shell.getChildAt(1).apply {isEnabled=true;visibility=if(cursor==null)View.GONE else View.VISIBLE}
                },{shell.getChildAt(1).isEnabled=true;Toast.makeText(activity,it.message ?: "Não foi possível carregar mais capas.",Toast.LENGTH_LONG).show()})}
            }
        }.apply {visibility=if(cursor==null || more==null)View.GONE else View.VISIBLE}
        dialog.setOnDismissListener {loader.close()}
        dialog.show();dialog.window?.setBackgroundDrawable(ui.shape(ui.background,18))
        return dialog
    }
}
