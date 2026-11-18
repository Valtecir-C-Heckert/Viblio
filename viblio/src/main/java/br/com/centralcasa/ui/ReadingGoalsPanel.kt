package br.com.centralcasa.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import br.com.centralcasa.books.*
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

/** Private, profile-scoped goals. The entire section collapses to one row. */
class ReadingGoalsPanel(private val activity:Activity,private val ui:DisplayUi,private val repo:LibraryRepository,private val changed:()->Unit) {
    private val profile=LibraryProfiles.settings(activity)
    private val store=ReadingGoalStore(activity)
    private val images=BookImages(activity)
    private val dialogs=mutableSetOf<AlertDialog>()
    private val openGoals=mutableSetOf<String>()
    private val shownCredits=mutableMapOf<String,Int>()
    private var closed=false
    val isEditing get()=dialogs.any {it.isShowing}
    private val numbers=NumberFormat.getIntegerInstance(Locale("pt","BR"))
    private fun valid()=!closed && LibraryProfiles.settings(activity)==profile
    private fun tracked(dialog:AlertDialog):AlertDialog {dialogs.add(dialog);dialog.setOnDismissListener {dialogs.remove(dialog)};dialog.show();dialog.window?.setBackgroundDrawable(ui.shape(ui.surface,0));return dialog}
    fun close(){closed=true;dialogs.toList().forEach {it.dismiss()};dialogs.clear()}
    private fun mutate(action:()->Unit){if(!valid())return;try {action();changed()}catch(e:Exception){Toast.makeText(activity,e.message ?: "Não foi possível salvar a meta.",Toast.LENGTH_LONG).show()}}
    private fun line(parent:LinearLayout){parent.addView(View(activity).apply {setBackgroundColor(ui.lineColor)},LinearLayout.LayoutParams(-1,ui.dp(1)).apply {topMargin=ui.dp(10);bottomMargin=ui.dp(10)})}
    private fun title(parent:LinearLayout,value:String,size:Int=21,color:Int=ui.text)=ui.label(parent,value,size,color).apply {typeface=Typeface.create("serif",Typeface.NORMAL);includeFontPadding=true}
    private fun defaultName(metric:GoalMetric)=when(metric){GoalMetric.PAGES->"Páginas lidas";GoalMetric.BOOKS->"Livros concluídos";GoalMetric.PURCHASE->"Próxima compra"}
    private fun kindName(kind:String)=mapOf("book" to "Livros","manga" to "Mangás","comic" to "Quadrinhos","all" to "Todos").getValue(kind)
    fun render(parent:LinearLayout){
        if(!valid())return
        val section=ui.column().apply {tag="reading-goals";setPadding(ui.dp(20),0,ui.dp(20),ui.dp(12));setBackgroundColor(ui.surface)};parent.addView(section)
        val heading=ui.row().apply {tag="reading-goals-toggle";isFocusable=true;contentDescription="Metas";isSelected=store.expanded();minimumHeight=ui.dp(44);setOnClickListener {store.expanded(!store.expanded());changed()}};section.addView(heading)
        ui.label(heading,"METAS",11,ui.muted).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
        if(store.count()>0)ui.label(heading,"${store.count()}",11,ui.muted).layoutParams=LinearLayout.LayoutParams(-2,-2)
        ui.icon(heading,"plus","Criar meta",36){edit(null)}.apply {tag="goal-create";setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(8))}
        ui.icon(heading,if(store.expanded())"down" else "chevron","Expandir ou recolher metas",36){store.expanded(!store.expanded());changed()}.setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(8))
        if(!store.expanded())return
        val entries=store.entries()
        if(entries.isEmpty()){ui.action(section,"Definir meta"){edit(null)};return}
        val books=repo.books(true);val sessions=repo.sessions();val now=System.currentTimeMillis()
        entries.forEachIndexed {index,entry->
            val goal=entry.goal
            if(goal==null){ui.label(section,"Meta indisponível",14,ui.muted);ui.action(section,"Remover"){remove(entry)};return@forEachIndexed}
            val progress=ReadingGoals.progress(goal,books,sessions,now)
            if(index>0)line(section)
            val card=ui.column().apply {tag="goal:${goal.id}"};section.addView(card,LinearLayout.LayoutParams(-1,-2))
            val row=ui.row();card.addView(row)
            ui.label(row,goal.name.uppercase(Locale("pt","BR")),11,ui.muted).apply {letterSpacing=.06f;maxLines=2;layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
            ui.label(row,progress.label,10,ui.muted).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;maxWidth=ui.dp(120);layoutParams=LinearLayout.LayoutParams(-2,-2)}
            ui.icon(row,"edit","Editar meta ${goal.name}",32){edit(entry)}.apply {tag="goal-edit:${goal.id}";setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(8))}
            var detailsHost=card
            when(goal.metric){
                GoalMetric.BOOKS->{
                    val counters=ui.row();card.addView(counters)
                    val circle=android.widget.FrameLayout(activity)
                    counters.addView(circle,LinearLayout.LayoutParams(ui.dp(88),ui.dp(88)).apply {marginEnd=ui.dp(14)})
                    circle.addView(object:View(activity){val paint=Paint(Paint.ANTI_ALIAS_FLAG);override fun onDraw(canvas:Canvas){val inset=ui.dp(3).toFloat();paint.style=Paint.Style.STROKE;paint.strokeWidth=ui.dp(1).coerceAtLeast(1).toFloat();paint.color=ui.lineColor;canvas.drawOval(inset,inset,width-inset,height-inset,paint);paint.color=ui.accent;canvas.drawArc(inset,inset,width-inset,height-inset,-90f,360f*(progress.value.toFloat()/goal.target).coerceIn(0f,1f),false,paint)}},android.widget.FrameLayout.LayoutParams(-1,-1))
                    val center=ui.column().apply {gravity=Gravity.CENTER};circle.addView(center,android.widget.FrameLayout.LayoutParams(-1,-1))
                    title(center,numbers.format(progress.value),if(progress.value<1000)44 else 31,ui.accent).apply {tag="goal-count:${goal.id}";gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(-1,-2)}
                    ui.label(center,"de ${numbers.format(goal.target)} ${if(goal.kind=="book")"livros" else "volumes"}",10,ui.muted).gravity=Gravity.CENTER
                    val remaining=ui.column();counters.addView(remaining,LinearLayout.LayoutParams(0,-2,1f))
                    title(remaining,numbers.format(progress.remaining(goal)),38,ui.accent)
                    ui.label(remaining,if(progress.remaining(goal)==0L)"Meta concluída" else if(progress.remaining(goal)==1L)"leitura restante" else "leituras restantes",10,ui.muted)
                    detailsHost=remaining
                }
                GoalMetric.PAGES->{
                    val digits=ui.row().apply {tag="goal-count:${goal.id}";contentDescription="${progress.value} de ${goal.target} páginas"};card.addView(digits)
                    val value=progress.value.toString().padStart(goal.target.toString().length,'0')
                    value.forEach {digit->
                        val cell=FrameLayout(activity).apply {background=ui.shape(ui.activeSurface,0)}
                        digits.addView(cell,LinearLayout.LayoutParams(ui.dp(if(value.length>7)22 else 36),ui.dp(40)).apply {marginEnd=ui.dp(4)})
                        cell.addView(TextView(activity).apply {text=digit.toString();textSize=if(value.length>7)23f else 28f;typeface=Typeface.create("serif",Typeface.NORMAL);setTextColor(ui.accent);gravity=Gravity.CENTER;includeFontPadding=false},FrameLayout.LayoutParams(-1,-1))
                        cell.addView(View(activity).apply {setBackgroundColor(ui.lineColor);alpha=.35f},FrameLayout.LayoutParams(-1,ui.dp(1).coerceAtLeast(1),Gravity.CENTER))
                    }
                    val targetRow=ui.row();card.addView(targetRow,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(6)})
                    ui.label(targetRow,"Meta de ${numbers.format(goal.target)} páginas",10,ui.muted).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
                    ui.label(targetRow,"${(progress.value*100/goal.target).coerceAtMost(100)}%",10,ui.muted).layoutParams=LinearLayout.LayoutParams(-2,-2)
                    detailsHost=ui.row();card.addView(detailsHost)
                    ui.label(detailsHost,if(progress.remaining(goal)==0L)"Meta concluída" else if(progress.remaining(goal)==1L)"Falta 1 página" else "Faltam ${numbers.format(progress.remaining(goal))} páginas",10,ui.muted).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
                }
                GoalMetric.PURCHASE->{detailsHost=purchase(card,entry,goal,progress)}
            }
            ui.action(detailsHost,if(goal.metric==GoalMetric.PURCHASE)"Ver ciclo ›" else if(goal.metric==GoalMetric.PAGES)"Ver leituras ›" else "Ver livros ›"){
                if(!openGoals.add(goal.id))openGoals.remove(goal.id);changed()
            }.apply {tag="goal-details:${goal.id}";background=null;setTextColor(ui.accent);gravity=Gravity.START;minHeight=ui.dp(32);textSize=11f;setPadding(0,0,0,0);layoutParams=LinearLayout.LayoutParams(-2,ui.dp(32))}
            if(goal.id in openGoals){
                line(card)
                val credits=if(goal.metric==GoalMetric.PURCHASE)progress.credits.take(goal.target)else progress.credits
                if(credits.isEmpty())ui.label(card,"Nenhuma leitura contabilizada",12,ui.muted)
                val shown=shownCredits[goal.id] ?: 10
                credits.take(shown).forEach {credit->creditRow(card,credit,goal.metric==GoalMetric.PAGES,sessions,goal)}
                if(credits.size>shown)ui.action(card,"Ver mais livros"){shownCredits[goal.id]=shown+10;changed()}
                if(goal.metric==GoalMetric.PURCHASE){
                    title(card,"Histórico de ciclos",17)
                    if(goal.cycles.isEmpty())ui.label(card,"Nenhuma compra registrada",11,ui.muted)
                    goal.cycles.asReversed().forEachIndexed {index,cycle->
                        ui.action(card,"Ciclo ${goal.cycles.size-index} · ${if(cycle.reward==1)"1 livro comprado" else "${cycle.reward} livros comprados"} · ${ReadingDays.label(ReadingDays.at(cycle.at))}"){
                            val body=ui.column().apply {setPadding(ui.dp(20),ui.dp(10),ui.dp(20),ui.dp(10))}
                            cycle.books.forEach {creditRow(body,it,false,emptyList())}
                            tracked(AlertDialog.Builder(activity).setTitle("Livros deste ciclo").setView(ScrollView(activity).apply {addView(body)}).setNegativeButton("Fechar",null).create())
                        }.apply {background=null;setTextColor(ui.muted);gravity=Gravity.START;textSize=11f}
                    }
                }
            }
        }
    }
    private fun purchase(parent:LinearLayout,entry:ReadingGoalStore.Entry,goal:ReadingGoal,progress:GoalProgress):LinearLayout {
        val unlocked=progress.value>=goal.target
        val ribbon=ui.row();parent.addView(ribbon)
        ui.label(ribbon,"CICLO ${goal.cycles.size+1}",9,ui.muted).apply {letterSpacing=.12f;layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
        ui.label(ribbon,"${minOf(progress.value,goal.target.toLong())} / ${goal.target} lidos",11,ui.accent).layoutParams=LinearLayout.LayoutParams(-2,-2)
        val body=ui.row();parent.addView(body,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(8);bottomMargin=ui.dp(8)})
        val earned=ui.column();body.addView(earned,LinearLayout.LayoutParams(0,-2,1f))
        val covers=ui.row().apply {gravity=Gravity.TOP};earned.addView(covers)
        val slots=minOf(goal.target,3)
        repeat(slots){index->
            val credit=progress.credits.getOrNull(index)
            val item=ui.column();covers.addView(item,LinearLayout.LayoutParams(ui.dp(46),-2).apply {marginEnd=ui.dp(7)})
            val cover=FrameLayout(activity).apply {background=ui.outlined(ui.surface,if(credit==null)ui.lineColor else ui.accent,0);contentDescription=credit?.let {"${it.title} · concluído"} ?: "Livro ${index+1} · a ler";tag="goal-purchase-book:${goal.id}:$index"}
            item.addView(cover,LinearLayout.LayoutParams(-1,ui.dp(68)))
            val photo=credit?.let {images.thumbnail(it.cover)}
            if(photo!=null)cover.addView(ImageView(activity).apply {scaleType=ImageView.ScaleType.FIT_CENTER;setImageBitmap(photo)},FrameLayout.LayoutParams(-1,-1).apply {setMargins(ui.dp(1),ui.dp(1),ui.dp(1),ui.dp(1))})
            else cover.addView(TextView(activity).apply {text=credit?.title?.take(1)?.uppercase() ?: "${index+1}";setTextColor(if(credit!=null)ui.accent else ui.muted);typeface=Typeface.create("serif",Typeface.NORMAL);textSize=24f;gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
            if(credit!=null){
                cover.addView(ImageView(activity).apply {background=ui.shape(ui.accent,0);setImageDrawable(ui.symbol("check",ui.background,12));setPadding(ui.dp(2),ui.dp(2),ui.dp(2),ui.dp(2))},FrameLayout.LayoutParams(ui.dp(16),ui.dp(16),Gravity.BOTTOM or Gravity.END))
                cover.setOnClickListener {tracked(AlertDialog.Builder(activity).setTitle(credit.title).setMessage(listOf(credit.author,"Concluído · ${ReadingDays.label(ReadingDays.at(credit.at))}").filter {it.isNotBlank()}.joinToString("\n")).setNegativeButton("Fechar",null).create())}
            }
            ui.label(item,credit?.title ?: "A ler",9,if(credit!=null)ui.text else ui.muted).apply {maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;(layoutParams as LinearLayout.LayoutParams).topMargin=ui.dp(4)}
        }
        if(goal.target>slots)ui.label(earned,"+ ${goal.target-slots} no ciclo",10,ui.muted)
        val ticket=ui.column().apply {gravity=Gravity.CENTER;background=ui.outlined(if(unlocked)ui.activeSurface else ui.surface,if(unlocked)ui.accent else ui.lineColor,0);setPadding(ui.dp(5),ui.dp(6),ui.dp(5),ui.dp(6));tag="goal-purchase-reward:${goal.id}";contentDescription="${goal.reward} ${if(goal.reward==1)"livro" else "livros"} · ${if(unlocked)"compra liberada" else "compra bloqueada"}"}
        body.addView(ticket,LinearLayout.LayoutParams(ui.dp(88),ui.dp(112)).apply {marginStart=ui.dp(12)})
        ui.label(ticket,"RECOMPENSA",8,ui.muted).apply {gravity=Gravity.CENTER;letterSpacing=.08f}
        title(ticket,goal.reward.toString(),42,ui.accent).apply {gravity=Gravity.CENTER;(layoutParams as LinearLayout.LayoutParams).bottomMargin=0}
        ui.label(ticket,if(goal.reward==1)"livro" else "livros",10,ui.text).gravity=Gravity.CENTER
        val seal=ui.row().apply {gravity=Gravity.CENTER};ticket.addView(seal)
        seal.addView(ImageView(activity).apply {setImageDrawable(ui.symbol(if(unlocked)"check" else "lock",if(unlocked)ui.accent else ui.muted,12))},LinearLayout.LayoutParams(ui.dp(12),ui.dp(12)).apply {marginEnd=ui.dp(3)})
        ui.label(seal,if(unlocked)"LIBERADA" else "BLOQUEADA",8,if(unlocked)ui.accent else ui.muted).layoutParams=LinearLayout.LayoutParams(-2,-2)
        ui.label(parent,if(unlocked)"Compra liberada" else if(progress.remaining(goal)==1L)"Falta 1 leitura" else "Faltam ${progress.remaining(goal)} leituras",11,ui.accent).tag="goal-purchase-status:${goal.id}"
        if(progress.value>goal.target)ui.label(parent,"+ ${numbers.format(progress.value-goal.target)} para o próximo ciclo",10,ui.muted)
        val actions=ui.row();parent.addView(actions)
        val details=ui.column();actions.addView(details,LinearLayout.LayoutParams(0,-2,1f))
        if(unlocked)ui.action(actions,"Registrar compra",primary=true){confirmPurchase(entry)}.apply {tag="goal-purchase:${goal.id}";minHeight=ui.dp(36);textSize=11f;layoutParams=LinearLayout.LayoutParams(-2,ui.dp(36))}
        return details
    }
    private fun creditRow(parent:LinearLayout,credit:GoalCredit,pages:Boolean,sessions:List<ReadingSession>,goal:ReadingGoal?=null){
        val row=ui.row().apply {tag="goal-credit:${credit.bookId}";setPadding(0,ui.dp(6),0,ui.dp(6));isFocusable=true};parent.addView(row)
        val image=ImageView(activity).apply {scaleType=ImageView.ScaleType.FIT_CENTER;setImageDrawable(ui.symbol("books",ui.muted,32));images.thumbnail(credit.cover)?.let(::setImageBitmap)}
        row.addView(image,LinearLayout.LayoutParams(ui.dp(34),ui.dp(50)).apply {marginEnd=ui.dp(10)})
        val labels=ui.column();row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        ui.label(labels,credit.title,13).apply {maxLines=2}
        ui.label(labels,if(pages)if(credit.pages==1L)"1 página" else "${numbers.format(credit.pages)} páginas" else "Concluído · ${ReadingDays.label(ReadingDays.at(credit.at))}",10,ui.muted)
        row.setOnClickListener {
            val records=sessions.filter {it.id in credit.sessionIds}
            val message=if(records.isEmpty())credit.author else records.sortedBy {it.finishedAt}.joinToString("\n\n"){"${ReadingDays.label(it.readingDay ?: ReadingDays.at(it.startedAt))}\nPáginas ${if(pages && goal!=null && it.startedAt<goal.fromAt)maxOf(it.startPage,goal.baseline[it.bookId] ?: 0)else it.startPage} → ${it.endPage} · ${readingTime(it.durationMs)}"}
            tracked(AlertDialog.Builder(activity).setTitle(credit.title).setMessage(message).setNegativeButton("Fechar",null).create())
        }
    }
    private fun confirmPurchase(entry:ReadingGoalStore.Entry){
        val goal=requireNotNull(entry.goal)
        tracked(AlertDialog.Builder(activity).setTitle("Registrar compra?").setMessage(if(goal.reward==1)"Comprei 1 livro" else "Comprei ${goal.reward} livros")
            .setPositiveButton("Registrar"){_,_->mutate {
                val progress=ReadingGoals.progress(goal,repo.books(true),repo.sessions(),System.currentTimeMillis())
                store.save(ReadingGoals.purchase(goal,progress,System.currentTimeMillis()),entry.raw)
            }}.setNegativeButton("Cancelar",null).create())
    }
    private fun remove(entry:ReadingGoalStore.Entry){tracked(AlertDialog.Builder(activity).setTitle("Remover meta?").setPositiveButton("Remover"){_,_->mutate {store.remove(entry)}}.setNegativeButton("Cancelar",null).create())}
    private fun edit(entry:ReadingGoalStore.Entry?){
        if(!valid())return
        val old=entry?.goal
        val body=ui.column().apply {setPadding(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(8))}
        fun text(value:String)=ui.label(body,value,12,ui.muted)
        fun input(hint:String,limit:Int,numeric:Boolean=false)=EditText(activity).apply {this.hint=hint;isSingleLine=true;filters=arrayOf(android.text.InputFilter.LengthFilter(limit));inputType=if(numeric)android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT;setTextColor(ui.text);setHintTextColor(ui.muted);body.addView(this,LinearLayout.LayoutParams(-1,ui.dp(48)))}
        text("Nome da meta");val name=input("Nome",80).apply {tag="goal-name";setText(old?.name.orEmpty())}
        fun spinner(label:String,options:List<String>,position:Int)=Spinner(activity).apply {text(label);adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,options);setSelection(position);body.addView(this,LinearLayout.LayoutParams(-1,ui.dp(44)));contentDescription=label}
        val metric=spinner("Tipo",GoalMetric.entries.map {it.label},old?.metric?.ordinal ?: 0).apply {isEnabled=old==null;tag="goal-metric"}
        val targetLabel=text("Quantidade")
        val target=input("Quantidade",8,true).apply {tag="goal-target";setText((old?.target ?: 12).toString())}
        val rewardLabel=text("Livros para comprar");val reward=input("Quantidade",3,true).apply {tag="goal-reward";setText((old?.reward ?: 1).toString())}
        val period=spinner("Período",GoalPeriod.entries.map {it.label},old?.period?.ordinal ?: GoalPeriod.YEAR.ordinal).apply {tag="goal-period"}
        val kinds=listOf("book","manga","comic","all")
        val kind=spinner("Leituras",kinds.map(::kindName),kinds.indexOf(old?.kind ?: "book")).apply {tag="goal-kind"}
        val include=CheckBox(activity).apply {text="Incluir leituras deste período";setTextColor(ui.text);isChecked=old!=null && old.fromAt<old.createdAt;isEnabled=old==null;tag="goal-include-period"};body.addView(include)
        metric.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){
                val selected=GoalMetric.entries[position];val purchase=selected==GoalMetric.PURCHASE
                reward.visibility=if(purchase)View.VISIBLE else View.GONE;rewardLabel.visibility=reward.visibility;include.visibility=if(purchase)View.GONE else View.VISIBLE
                targetLabel.text=if(selected==GoalMetric.PAGES)"Páginas para ler" else "Livros para ler"
                if(old==null){target.setText(when(selected){GoalMetric.PAGES->"500";GoalMetric.BOOKS->"12";else->"3"});period.setSelection(when(selected){GoalMetric.PAGES->GoalPeriod.MONTH.ordinal;GoalMetric.BOOKS->GoalPeriod.YEAR.ordinal;else->GoalPeriod.CONTINUOUS.ordinal})}
            }
        }
        val builder=AlertDialog.Builder(activity).setTitle(if(old==null)"Nova meta" else "Editar meta").setView(ScrollView(activity).apply {addView(body)}).setPositiveButton("Salvar",null).setNegativeButton("Cancelar",null)
        if(entry!=null)builder.setNeutralButton("Remover"){_,_->remove(entry)}
        val dialog=builder.create()
        tracked(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(!valid())return@setOnClickListener
            try {
                val now=System.currentTimeMillis();val m=GoalMetric.entries[metric.selectedItemPosition];val p=GoalPeriod.entries[period.selectedItemPosition]
                val books=repo.books(true);val retro=old==null && m!=GoalMetric.PURCHASE && include.isChecked
                val from=if(retro){if(p==GoalPeriod.CONTINUOUS)0L else {val day=ReadingPeriods.range(ReadingDays.at(now),ReadingPeriodUnit.valueOf(p.name)).start;SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(day)!!.time}}else now
                val goal=if(old!=null)old.copy(name=name.text.toString().trim().ifBlank {defaultName(m)},target=target.text.toString().toInt(),reward=reward.text.toString().toInt(),period=p,kind=kinds[kind.selectedItemPosition]) else ReadingGoal(name=name.text.toString().trim().ifBlank {defaultName(m)},metric=m,target=target.text.toString().toInt(),reward=reward.text.toString().toInt(),period=p,kind=kinds[kind.selectedItemPosition],createdAt=now,fromAt=from.coerceAtMost(now),baseline=if(retro)emptyMap() else books.associate {it.id to it.currentPage},excluded=if(retro)emptySet() else books.filter {it.complete}.map {it.id}.toSet())
                store.save(goal,entry?.raw);store.expanded(true);dialog.dismiss();changed()
            }catch(e:Exception){target.error=e.message ?: "Confira os valores da meta."}
        }
    }
}
