package br.com.centralcasa.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import br.com.centralcasa.books.*
import br.com.centralcasa.data.LocalSettings
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

/** Private, profile-scoped goals. The entire section collapses to one row. */
class ReadingGoalsPanel(private val activity:Activity,private val ui:DisplayUi,private val repo:LibraryRepository,private val changed:()->Unit) {
    private val profile=LibraryProfiles.settings(activity)
    private val store=ReadingGoalStore(activity)
    private val images=BookImages(activity)
    private val dialogs=mutableSetOf<Dialog>()
    private val openGoals=mutableSetOf<String>()
    private val shownCredits=mutableMapOf<String,Int>()
    private var closed=false
    val isEditing get()=dialogs.any {it.isShowing}
    private val numbers=NumberFormat.getIntegerInstance(Locale("pt","BR"))
    var onRedeemPurchaseWithWishlist:((ReadingGoalStore.Entry,ReadingGoal,GoalProgress)->Unit)?=null
    private fun isGalleryMode()=ui.rectilinear && LocalSettings(activity).galleryUiStyle()
    private fun valid()=!closed && LibraryProfiles.settings(activity)==profile
    private fun tracked(dialog:AlertDialog):AlertDialog {dialogs.add(dialog);dialog.setOnDismissListener {dialogs.remove(dialog)};dialog.show();dialog.window?.setBackgroundDrawable(ui.shape(ui.surface,0));return dialog}
    fun close(){closed=true;dialogs.toList().forEach {it.dismiss()};dialogs.clear()}
    private fun mutate(action:()->Unit){if(!valid())return;try {action();changed()}catch(e:Exception){Toast.makeText(activity,e.message ?: "Não foi possível salvar a meta.",Toast.LENGTH_LONG).show()}}
    private fun line(parent:LinearLayout){parent.addView(View(activity).apply {setBackgroundColor(ui.lineColor)},LinearLayout.LayoutParams(-1,ui.dp(1)).apply {topMargin=ui.dp(10);bottomMargin=ui.dp(10)})}
    private fun title(parent:LinearLayout,value:String,size:Int=21,color:Int=ui.text)=ui.label(parent,value,size,color).apply {typeface=Typeface.create("serif",Typeface.NORMAL);includeFontPadding=true}
    private fun gText(value:CharSequence,sp:Float,color:Int,serif:Boolean=false,medium:Boolean=false)=TextView(activity).apply {
        text=value;textSize=sp;setTextColor(color);includeFontPadding=false
        typeface=when {
            serif -> Typeface.create("serif",Typeface.NORMAL)
            medium -> Typeface.create("sans-serif-medium",Typeface.NORMAL)
            else -> Typeface.create("sans-serif",Typeface.NORMAL)
        }
    }
    private fun defaultName(metric:GoalMetric)=when(metric){GoalMetric.PAGES->"Páginas lidas";GoalMetric.BOOKS->"Livros concluídos";GoalMetric.PURCHASE->"Próxima compra"}
    private fun kindName(kind:String)=mapOf("book" to "Livros","manga" to "Mangás","comic" to "Quadrinhos","all" to "Todos").getValue(kind)
    fun openCreateDialog(defaultMetric:GoalMetric?=null){edit(null,defaultMetric)}

    /** Centered editorial Galeria modal matching BookInfoDialog and Goal Config. */
    private fun showGalleryModal(
        heading:String,
        trailingLabel:String?=null,
        onTrailing:((Dialog)->Unit)?=null,
        build:(LinearLayout,Dialog)->Unit,
    ):Dialog {
        val dialog=Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val radiusPx=ui.dp(24).toFloat()
        val strokePx=ui.dp(1).coerceAtLeast(1).toFloat()
        val cardBg=object:Drawable() {
            private val fillPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=ui.surface}
            private val glowPaint=Paint(Paint.ANTI_ALIAS_FLAG)
            private val borderPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style=Paint.Style.STROKE;strokeWidth=strokePx;color=ui.galleryHairline(145)
            }
            private val clipPath=Path();private val rect=RectF();private var lastW=0;private var lastH=0
            override fun draw(canvas:Canvas){
                val b=bounds;val w=b.width();val h=b.height()
                if(w<=0 || h<=0)return
                rect.set(b.left.toFloat(),b.top.toFloat(),b.right.toFloat(),b.bottom.toFloat())
                if(w!=lastW || h!=lastH){
                    lastW=w;lastH=h;clipPath.reset();clipPath.addRoundRect(rect,radiusPx,radiusPx,Path.Direction.CW)
                    val cx=b.left+w*0.5f;val top=b.top.toFloat();val r=(w*0.72f).coerceAtLeast(ui.dp(180).toFloat())
                    glowPaint.shader=RadialGradient(cx,top,r,intArrayOf(ui.tint(ui.accent,46),ui.tint(ui.accent,16),Color.TRANSPARENT),floatArrayOf(0f,0.52f,1f),Shader.TileMode.CLAMP)
                }
                canvas.save();canvas.clipPath(clipPath)
                canvas.drawRect(rect,fillPaint)
                canvas.drawRect(rect.left,rect.top,rect.right,(rect.top+ui.dp(200)).coerceAtMost(rect.bottom),glowPaint)
                canvas.restore()
                val half=strokePx*0.5f;rect.inset(half,half)
                canvas.drawRoundRect(rect,radiusPx,radiusPx,borderPaint)
            }
            override fun setAlpha(alpha:Int){}
            override fun setColorFilter(colorFilter:ColorFilter?){}
            @Deprecated("Deprecated in Java")
            override fun getOpacity()=PixelFormat.TRANSLUCENT
        }
        val outer=FrameLayout(activity).apply {setPadding(ui.dp(14),ui.dp(20),ui.dp(14),ui.dp(20))}
        val card=ui.column().apply {background=cardBg;setPadding(ui.dp(20),ui.dp(14),ui.dp(20),ui.dp(20))}
        val topBar=ui.row().apply {gravity=Gravity.CENTER_VERTICAL}
        card.addView(topBar,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(12)})
        topBar.addView(
            gText(heading,20f,ui.text,serif=true).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END},
            LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(8)},
        )
        if(!trailingLabel.isNullOrBlank() && onTrailing!=null){
            topBar.addView(
                gText(trailingLabel,11.5f,ui.accent,medium=true).apply {
                    gravity=Gravity.CENTER;setPadding(ui.dp(11),ui.dp(6),ui.dp(11),ui.dp(6))
                    background=ui.pillRipple(ui.activeSurface,16,ui.galleryHairline(140));isFocusable=true
                    setOnClickListener {onTrailing(dialog)}
                },
                LinearLayout.LayoutParams(-2,-2).apply {marginEnd=ui.dp(6)},
            )
        }
        topBar.addView(
            gText("×",22f,ui.muted).apply {
                gravity=Gravity.CENTER;contentDescription="Fechar";isFocusable=true
                background=ui.pillRipple(Color.TRANSPARENT,18)
                setOnClickListener {dialog.dismiss()}
            },
            LinearLayout.LayoutParams(ui.dp(34),ui.dp(34)),
        )
        val content=ui.column()
        val scroll=ScrollView(activity).apply {isVerticalScrollBarEnabled=false;addView(content,FrameLayout.LayoutParams(-1,-2))}
        card.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        outer.addView(card,FrameLayout.LayoutParams(-1,-2))
        build(content,dialog)
        dialog.setContentView(outer)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setDimAmount(0.64f)
        }
        dialogs.add(dialog)
        dialog.setOnDismissListener {dialogs.remove(dialog)}
        dialog.show()
        return dialog
    }

    fun openGoalDetailDialog(entry:ReadingGoalStore.Entry){
        if(!valid())return
        val goal=entry.goal ?: return
        if(isGalleryMode() && goal.metric==GoalMetric.PURCHASE){
            openPurchaseGalleryModal(entry.id)
            return
        }
        openGoals.add(goal.id)
        val body=ui.column().apply {setPadding(ui.dp(16),ui.dp(12),ui.dp(16),ui.dp(12))}
        renderEntries(body,listOf(entry))
        var dlg:AlertDialog?=null
        dlg=tracked(AlertDialog.Builder(activity)
            .setView(ScrollView(activity).apply {addView(body)})
            .setPositiveButton("Fechar",null)
            .setNeutralButton("Editar"){_,_->dlg?.dismiss();edit(entry)}
            .create())
    }
    fun openAllGoalsDialog(){
        if(!valid())return
        val entries=store.entries()
        val body=ui.column().apply {setPadding(ui.dp(16),ui.dp(12),ui.dp(16),ui.dp(12))}
        if(entries.isEmpty()) ui.label(body,"Nenhuma meta cadastrada.",12,ui.muted)
        else renderEntries(body,entries)
        var dlg:AlertDialog?=null
        dlg=tracked(AlertDialog.Builder(activity)
            .setTitle("Todas as metas")
            .setView(ScrollView(activity).apply {addView(body)})
            .setPositiveButton("Fechar",null)
            .setNeutralButton("+ Nova meta"){_,_->dlg?.dismiss();edit(null)}
            .create())
    }
    fun renderEmbedded(parent:LinearLayout){
        if(!valid())return
        val section=ui.column().apply {tag="reading-goals-embedded";setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10));background=ui.outlined(ui.surface,ui.lineColor,0)}
        parent.addView(section,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(12)})
        val entries=store.entries()
        val heading=ui.row().apply {gravity=Gravity.CENTER_VERTICAL}
        section.addView(heading,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=if(entries.isEmpty())ui.dp(4) else ui.dp(8)})
        ui.label(heading,if(entries.isEmpty())"METAS DE LEITURA" else "SUAS METAS (${entries.size})",10,ui.muted).apply {
            letterSpacing=.09f
            layoutParams=LinearLayout.LayoutParams(0,-2,1f)
        }
        TextView(activity).apply {
            tag="goal-create"
            text="+ Meta"
            textSize=11f
            setTextColor(ui.accent)
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            background=ui.outlined(ui.activeSurface,ui.lineColor,0)
            setPadding(ui.dp(9),ui.dp(5),ui.dp(9),ui.dp(5))
            setOnClickListener {edit(null)}
        }.also {heading.addView(it,LinearLayout.LayoutParams(-2,-2))}
        if(entries.isEmpty()){
            ui.label(section,"Nenhuma meta criada. Crie metas de livros, páginas ou compra (3:1) ou use apenas a lista mensal abaixo.",11,ui.muted).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin=0
            }
            return
        }
        renderEntries(section,entries)
    }
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
        renderEntries(section,entries)
    }
    private fun renderEntries(section:LinearLayout,entries:List<ReadingGoalStore.Entry>){
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
                credits.take(shown).forEach {credit->creditRow(card,credit,goal.metric==GoalMetric.PAGES,sessions,goal,entry)}
                if(credits.size>shown)ui.action(card,"Ver mais livros"){shownCredits[goal.id]=shown+10;changed()}
                if(goal.metric==GoalMetric.PURCHASE){
                    title(card,"Histórico de ciclos",17)
                    if(goal.cycles.isEmpty())ui.label(card,"Nenhuma compra registrada",11,ui.muted)
                    goal.cycles.asReversed().forEachIndexed {index,cycle->
                        val bought=if(cycle.purchased.isNotEmpty()) cycle.purchased.joinToString(", ") else if(cycle.reward==1)"1 livro comprado" else "${cycle.reward} livros comprados"
                        ui.action(card,"Ciclo ${goal.cycles.size-index} · $bought · ${ReadingDays.label(ReadingDays.at(cycle.at))}"){
                            val body=ui.column().apply {setPadding(ui.dp(20),ui.dp(10),ui.dp(20),ui.dp(10))}
                            if(cycle.purchased.isNotEmpty()){
                                ui.label(body,"Comprado: ${cycle.purchased.joinToString(", ")}",13,ui.accent,bold=true)
                            }
                            cycle.books.forEach {creditRow(body,it,false,emptyList())}
                            tracked(AlertDialog.Builder(activity).setTitle("Livros deste ciclo").setView(ScrollView(activity).apply {addView(body)})
                                .setNeutralButton(if(cycle.purchased.isEmpty())"Informar comprado" else "Editar comprado"){_,_->openEditCyclePurchasedDialog(entry,goal,cycle)}
                                .setNegativeButton("Fechar",null).create())
                        }.apply {background=null;setTextColor(ui.muted);gravity=Gravity.START;textSize=11f}
                    }
                }
            }
        }
    }
    private fun excludeFromPurchaseGoal(entry:ReadingGoalStore.Entry,goal:ReadingGoal,bookId:String){
        if(goal.metric!=GoalMetric.PURCHASE)return
        mutate {
            val updated=goal.copy(excluded=goal.excluded+bookId).validated()
            store.save(updated,entry.raw)
            dialogs.toList().forEach {it.dismiss()}
            dialogs.clear()
        }
    }

    /** Galeria centered editorial Purchase Cycle modal: cover-tinted top glow, stacked rewards, purchased book tracking, and cycle history. */
    private fun openPurchaseGalleryModal(goalId:String){
        if(!valid())return
        val entry=store.entries().firstOrNull {it.id==goalId} ?: return
        val goal=entry.goal ?: return
        val books=repo.books(true)
        val sessions=repo.sessions()
        val now=System.currentTimeMillis()
        val progress=ReadingGoals.progress(goal,books,sessions,now)
        val unlockedCycles=ReadingGoals.unlockedCycles(goal,progress)
        val stackedRewards=ReadingGoals.stackedRewards(goal,progress)
        val currentSurplus=ReadingGoals.currentCycleProgress(goal,progress)
        val unlocked=unlockedCycles>0
        val hairline=ui.galleryHairline(105)
        val mintGreen=Color.rgb(126,224,164)

        showGalleryModal(
            heading=goal.name,
            trailingLabel="Editar",
            onTrailing={dlg->dlg.dismiss();edit(entry)},
        ){ body, dialog ->
            // 1) Cycle ribbon + stack badge
            val ribbon=ui.row().apply {gravity=Gravity.CENTER_VERTICAL}
            body.addView(ribbon,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(14)})
            ribbon.addView(
                gText("CICLO ${goal.cycles.size+1} · ${goal.target}:${goal.reward}",10f,ui.muted,medium=true).apply {letterSpacing=0.10f},
                LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(8)},
            )
            if(unlockedCycles>1){
                ribbon.addView(
                    gText("×$unlockedCycles acumulados",10.5f,mintGreen,medium=true).apply {
                        setPadding(ui.dp(9),ui.dp(4),ui.dp(9),ui.dp(4))
                        background=ui.pillShape(Color.argb(36,79,166,116),12,Color.argb(120,126,224,164))
                    },
                    LinearLayout.LayoutParams(-2,-2).apply {marginEnd=ui.dp(8)},
                )
            }
            ribbon.addView(
                gText("${minOf(progress.value,goal.target.toLong())}/${goal.target} lidos",11.5f,ui.accent,medium=true).apply {fontFeatureSettings="tnum"},
                LinearLayout.LayoutParams(-2,-2),
            )

            // 2) Mini-cover slots (left) + Editorial Reward Ticket (right)
            val heroRow=ui.row().apply {gravity=Gravity.CENTER_VERTICAL}
            body.addView(heroRow,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(14)})

            val slotsCol=ui.column()
            heroRow.addView(slotsCol,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(12)})
            val coversRow=ui.row().apply {gravity=Gravity.TOP}
            slotsCol.addView(coversRow,LinearLayout.LayoutParams(-1,-2))
            val slots=minOf(goal.target,3)
            repeat(slots){ index ->
                val credit=progress.credits.getOrNull(index)
                val item=ui.column()
                coversRow.addView(item,LinearLayout.LayoutParams(0,-2,1f).apply {if(index<slots-1) marginEnd=ui.dp(7)})
                val frame=FrameLayout(activity).apply {
                    tag="goal-purchase-book:${goal.id}:$index"
                    contentDescription=credit?.let {"${it.title} · concluído"} ?: "Livro ${index+1} · a ler"
                    background=if(credit!=null) ui.pillShape(ui.activeSurface,6,ui.tint(ui.accent,165)) else GradientDrawable().apply {
                        setColor(Color.TRANSPARENT);cornerRadius=ui.dp(6).toFloat()
                        setStroke(ui.dp(1).coerceAtLeast(1),ui.galleryHairline(160),ui.dp(3).toFloat(),ui.dp(3).toFloat())
                    }
                    clipToOutline=true
                }
                item.addView(frame,LinearLayout.LayoutParams(-1,ui.dp(78)))
                val bmp=credit?.let {images.thumbnail(it.cover)}
                if(bmp!=null){
                    frame.addView(ImageView(activity).apply {scaleType=ImageView.ScaleType.CENTER_CROP;setImageBitmap(bmp)},FrameLayout.LayoutParams(-1,-1))
                }else {
                    frame.addView(
                        gText(credit?.title?.take(1)?.uppercase() ?: "${index+1}",20f,if(credit!=null)ui.accent else ui.muted,serif=true).apply {gravity=Gravity.CENTER},
                        FrameLayout.LayoutParams(-1,-1),
                    )
                }
                if(credit!=null){
                    val checkBadge=ImageView(activity).apply {
                        background=GradientDrawable().apply {shape=GradientDrawable.OVAL;setColor(ui.accent)}
                        setImageDrawable(ui.symbol("check",ui.background,10))
                        setPadding(ui.dp(3),ui.dp(3),ui.dp(3),ui.dp(3))
                    }
                    frame.addView(checkBadge,FrameLayout.LayoutParams(ui.dp(16),ui.dp(16),Gravity.BOTTOM or Gravity.END).apply {setMargins(0,0,ui.dp(4),ui.dp(4))})
                    frame.isFocusable=true
                    frame.setOnClickListener {
                        tracked(AlertDialog.Builder(activity)
                            .setTitle(credit.title)
                            .setMessage(listOf(credit.author,"Concluído · ${ReadingDays.label(ReadingDays.at(credit.at))}").filter {it.isNotBlank()}.joinToString("\n"))
                            .setNeutralButton("Retirar desta meta"){_,_->excludeFromPurchaseGoal(entry,goal,credit.bookId)}
                            .setNegativeButton("Fechar",null).create())
                    }
                }
                item.addView(
                    gText(credit?.title ?: "A ler",9.5f,if(credit!=null)ui.text else ui.muted).apply {
                        maxLines=2;ellipsize=TextUtils.TruncateAt.END;gravity=Gravity.CENTER_HORIZONTAL
                    },
                    LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(5)},
                )
            }
            if(goal.target>slots){
                slotsCol.addView(
                    gText("+ ${goal.target-slots} no ciclo",10f,ui.muted),
                    LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(4)},
                )
            }

            // Editorial Reward Ticket (supports stacked rewards!)
            val displayCount=if(unlocked) stackedRewards else goal.reward
            val ticketBg=if(unlocked) ui.pillShape(ui.activeSurface,16,ui.tint(ui.accent,180)) else ui.pillShape(ui.background,16,ui.galleryHairline(125))
            val ticket=ui.column().apply {
                tag="goal-purchase-reward:${goal.id}"
                gravity=Gravity.CENTER
                background=ticketBg
                setPadding(ui.dp(8),ui.dp(10),ui.dp(8),ui.dp(10))
                contentDescription="$displayCount ${if(displayCount==1)"livro" else "livros"} · ${if(unlocked)"compra liberada" else "compra bloqueada"}"
            }
            heroRow.addView(ticket,LinearLayout.LayoutParams(ui.dp(94),ui.dp(118)))
            ticket.addView(
                gText(if(unlockedCycles>1)"STACK" else "RECOMPENSA",8.5f,if(unlocked)ui.accent else ui.muted,medium=true).apply {
                    letterSpacing=0.10f;gravity=Gravity.CENTER
                },
                LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(2)},
            )
            ticket.addView(
                gText(displayCount.toString(),38f,if(unlocked)ui.accent else ui.muted,serif=true).apply {
                    gravity=Gravity.CENTER;fontFeatureSettings="tnum"
                },
                LinearLayout.LayoutParams(-1,-2),
            )
            ticket.addView(
                gText(if(displayCount==1)"livro" else "livros",10.5f,if(unlocked)ui.text else ui.muted).apply {gravity=Gravity.CENTER},
                LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(6)},
            )
            val seal=ui.row().apply {gravity=Gravity.CENTER}
            ticket.addView(seal,LinearLayout.LayoutParams(-2,-2))
            seal.addView(ImageView(activity).apply {setImageDrawable(ui.symbol(if(unlocked)"check" else "lock",if(unlocked)mintGreen else ui.muted,11))},LinearLayout.LayoutParams(ui.dp(11),ui.dp(11)).apply {marginEnd=ui.dp(3)})
            seal.addView(gText(if(unlocked && unlockedCycles>1)"×$unlockedCycles LIVRES" else if(unlocked)"LIBERADA" else "BLOQUEADA",8.5f,if(unlocked)mintGreen else ui.muted,medium=true),LinearLayout.LayoutParams(-2,-2))

            // 3) Status + surplus progress toward next stack
            val statusStr=when {
                unlocked && unlockedCycles>1 -> "$unlockedCycles ciclos acumulados ($stackedRewards ${if(stackedRewards==1)"livro" else "livros"} para comprar)"
                unlocked -> "Compra liberada para ${if(goal.reward==1)"1 livro" else "${goal.reward} livros"}"
                progress.remaining(goal)==1L -> "Falta 1 leitura para liberar"
                else -> "Faltam ${progress.remaining(goal)} leituras para liberar"
            }
            body.addView(
                gText(statusStr,12f,if(unlocked)mintGreen else ui.accent,medium=true).apply {tag="goal-purchase-status:${goal.id}"},
                LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(if(unlocked && currentSurplus>0) 3 else 12)},
            )
            if(unlocked && currentSurplus>0){
                body.addView(
                    gText("+$currentSurplus/${goal.target} lidos para o próximo stack",11f,ui.muted).apply {fontFeatureSettings="tnum"},
                    LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(12)},
                )
            }

            // 4) Primary action when unlocked
            if(unlocked){
                val btn=gText("Registrar compra",13f,ui.background,medium=true).apply {
                    tag="goal-purchase:${goal.id}"
                    gravity=Gravity.CENTER;minHeight=ui.dp(44);isFocusable=true
                    setPadding(ui.dp(16),ui.dp(11),ui.dp(16),ui.dp(11))
                    background=ui.pillRipple(ui.accent,22,darkRipple=true)
                    setOnClickListener {
                        dialog.dismiss()
                        openRedeemPurchaseGalleryModal(entry,goal)
                    }
                }
                body.addView(btn,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(16)})
            }

            // 5) Current cycle credits list
            body.addView(View(activity).apply {setBackgroundColor(hairline)},LinearLayout.LayoutParams(-1,1).apply {bottomMargin=ui.dp(12)})
            val cycleBooks=progress.credits.take(goal.target)
            body.addView(
                gText("LEITURAS DO CICLO ATUAL",10f,ui.muted,medium=true).apply {letterSpacing=0.08f},
                LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(8)},
            )
            if(cycleBooks.isEmpty()){
                body.addView(gText("Nenhuma leitura contabilizada ainda",12f,ui.muted),LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(14)})
            }else {
                cycleBooks.forEach { credit ->
                    val row=ui.row().apply {
                        tag="goal-credit:${credit.bookId}";gravity=Gravity.CENTER_VERTICAL
                        setPadding(0,ui.dp(6),0,ui.dp(6));isFocusable=true
                        setOnClickListener {
                            tracked(AlertDialog.Builder(activity)
                                .setTitle(credit.title)
                                .setMessage(listOf(credit.author,"Concluído · ${ReadingDays.label(ReadingDays.at(credit.at))}").filter {it.isNotBlank()}.joinToString("\n"))
                                .setNeutralButton("Retirar desta meta"){_,_->excludeFromPurchaseGoal(entry,goal,credit.bookId)}
                                .setNegativeButton("Fechar",null).create())
                        }
                    }
                    body.addView(row,LinearLayout.LayoutParams(-1,-2))
                    val img=ImageView(activity).apply {
                        scaleType=ImageView.ScaleType.CENTER_CROP
                        background=ui.pillShape(ui.activeSurface,4);clipToOutline=true
                        setImageDrawable(br.com.viblio.ui.ViblioBookMarkDrawable(ui.accent,Color.WHITE,scale=0.52f,backgroundColor=ui.activeSurface))
                        images.thumbnail(credit.cover)?.let(::setImageBitmap)
                    }
                    row.addView(img,LinearLayout.LayoutParams(ui.dp(30),ui.dp(44)).apply {marginEnd=ui.dp(10)})
                    val col=ui.column();row.addView(col,LinearLayout.LayoutParams(0,-2,1f))
                    col.addView(gText(credit.title,13f,ui.text,medium=true).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(2)})
                    col.addView(gText("Concluído · ${ReadingDays.label(ReadingDays.at(credit.at))}",10.5f,ui.muted),LinearLayout.LayoutParams(-1,-2))
                }
                body.addView(View(activity),LinearLayout.LayoutParams(1,ui.dp(10)))
            }

            // 6) History of redeemed cycles (showing WHICH book was purchased!)
            body.addView(View(activity).apply {setBackgroundColor(hairline)},LinearLayout.LayoutParams(-1,1).apply {bottomMargin=ui.dp(12)})
            body.addView(
                gText("HISTÓRICO DE CICLOS",10f,ui.muted,medium=true).apply {letterSpacing=0.08f},
                LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(8)},
            )
            if(goal.cycles.isEmpty()){
                body.addView(gText("Nenhuma compra registrada ainda",12f,ui.muted),LinearLayout.LayoutParams(-1,-2))
            }else {
                goal.cycles.asReversed().forEachIndexed { idx, cycle ->
                    val cycleNumber=goal.cycles.size-idx
                    val boughtTitle=cycle.purchased.joinToString(", ")
                    val row=ui.row().apply {
                        gravity=Gravity.CENTER_VERTICAL
                        setPadding(0,ui.dp(9),0,ui.dp(9));isFocusable=true
                        setOnClickListener {
                            dialog.dismiss()
                            openCycleDetailGalleryModal(entry,goal,cycle,cycleNumber)
                        }
                    }
                    body.addView(row,LinearLayout.LayoutParams(-1,-2))
                    val col=ui.column();row.addView(col,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(8)})
                    val primaryText=if(boughtTitle.isNotBlank()) boughtTitle else if(cycle.reward==1)"1 livro comprado" else "${cycle.reward} livros comprados"
                    col.addView(
                        gText(primaryText,13.5f,if(boughtTitle.isNotBlank()) ui.text else ui.muted,serif=boughtTitle.isNotBlank()).apply {
                            maxLines=1;ellipsize=TextUtils.TruncateAt.END
                        },
                        LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(2)},
                    )
                    col.addView(
                        gText("Ciclo $cycleNumber · ${ReadingDays.label(ReadingDays.at(cycle.at))}",10.5f,ui.muted),
                        LinearLayout.LayoutParams(-1,-2),
                    )
                    row.addView(gText("›",17f,ui.accent),LinearLayout.LayoutParams(-2,-2))
                    if(idx<goal.cycles.lastIndex){
                        body.addView(View(activity).apply {setBackgroundColor(hairline)},LinearLayout.LayoutParams(-1,1))
                    }
                }
            }
        }
    }

    /** Centered Galeria modal to record which book was bought with the unlocked purchase reward. */
    private fun openRedeemPurchaseGalleryModal(entry:ReadingGoalStore.Entry,goal:ReadingGoal){
        if(!valid())return
        val hairline=ui.galleryHairline(110)
        val wishlistHandler=onRedeemPurchaseWithWishlist
        showGalleryModal(heading="Registrar compra"){ body, dialog ->
            val countLabel=if(goal.reward==1)"1 livro liberado neste ciclo" else "${goal.reward} livros liberados neste ciclo"
            body.addView(gText(countLabel,12f,ui.accent,medium=true),LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(14)})

            body.addView(
                gText("LIVRO COMPRADO",10f,ui.muted,medium=true).apply {letterSpacing=0.08f},
                LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(6)},
            )
            val titleInput=EditText(activity).apply {
                hint="Ex.: Duna"
                setTextColor(ui.text);setHintTextColor(ui.tint(ui.muted,160))
                textSize=15f;isSingleLine=true
                filters=arrayOf(InputFilter.LengthFilter(200))
                background=ui.pillShape(ui.background,14,ui.galleryHairline(135))
                setPadding(ui.dp(14),ui.dp(11),ui.dp(14),ui.dp(11))
            }
            body.addView(titleInput,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(10)})

            // Quick pickers: Estante & Wishlist
            val pickRow=ui.row().apply {gravity=Gravity.CENTER_VERTICAL}
            body.addView(pickRow,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(16)})
            val shelfBtn=gText("Escolher da Estante",11.5f,ui.text,medium=true).apply {
                gravity=Gravity.CENTER;setPadding(ui.dp(12),ui.dp(9),ui.dp(12),ui.dp(9))
                background=ui.pillRipple(ui.activeSurface,16,ui.galleryHairline(140));isFocusable=true
                setOnClickListener {
                    val shelfBooks=repo.books(false).sortedByDescending {it.updatedAt}
                    if(shelfBooks.isEmpty()){
                        Toast.makeText(activity,"Nenhum livro na Estante.",Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val labels=shelfBooks.map {b->if(b.author.isNotBlank())"${b.title} · ${b.author}" else b.title}.toTypedArray()
                    tracked(AlertDialog.Builder(activity).setTitle("Escolher da Estante").setItems(labels){_,idx->
                        titleInput.setText(shelfBooks[idx].title)
                        titleInput.setSelection(titleInput.text.length)
                    }.setNegativeButton("Cancelar",null).create())
                }
            }
            pickRow.addView(shelfBtn,LinearLayout.LayoutParams(0,-2,1f).apply {if(wishlistHandler!=null) marginEnd=ui.dp(8)})

            if(wishlistHandler!=null){
                val wishBtn=gText("Da Wishlist ›",11.5f,ui.accent,medium=true).apply {
                    gravity=Gravity.CENTER;setPadding(ui.dp(12),ui.dp(9),ui.dp(12),ui.dp(9))
                    background=ui.pillRipple(ui.activeSurface,16,ui.tint(ui.accent,140));isFocusable=true
                    setOnClickListener {
                        val fresh=ReadingGoals.progress(goal,repo.books(true),repo.sessions(),System.currentTimeMillis())
                        if(fresh.value>=goal.target){
                            dialog.dismiss()
                            wishlistHandler(entry,goal,fresh)
                        }else {
                            Toast.makeText(activity,"A compra ainda não foi liberada.",Toast.LENGTH_LONG).show()
                        }
                    }
                }
                pickRow.addView(wishBtn,LinearLayout.LayoutParams(0,-2,1f))
            }

            body.addView(View(activity).apply {setBackgroundColor(hairline)},LinearLayout.LayoutParams(-1,1).apply {bottomMargin=ui.dp(14)})

            val actions=ui.row().apply {gravity=Gravity.CENTER_VERTICAL}
            body.addView(actions,LinearLayout.LayoutParams(-1,-2))
            actions.addView(
                gText("Cancelar",12.5f,ui.muted,medium=true).apply {
                    gravity=Gravity.CENTER;minHeight=ui.dp(42);isFocusable=true
                    setPadding(ui.dp(14),ui.dp(10),ui.dp(14),ui.dp(10))
                    background=ui.pillRipple(ui.activeSurface,20,ui.galleryHairline(130))
                    setOnClickListener {dialog.dismiss();openPurchaseGalleryModal(entry.id)}
                },
                LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=ui.dp(8)},
            )
            actions.addView(
                gText("Confirmar compra",12.5f,ui.background,medium=true).apply {
                    gravity=Gravity.CENTER;minHeight=ui.dp(42);isFocusable=true
                    setPadding(ui.dp(14),ui.dp(10),ui.dp(14),ui.dp(10))
                    background=ui.pillRipple(ui.accent,20,darkRipple=true)
                    setOnClickListener {
                        val typed=titleInput.text.toString().trim()
                        val bought=if(typed.isNotEmpty()) listOf(typed) else emptyList()
                        mutate {
                            val fresh=ReadingGoals.progress(goal,repo.books(true),repo.sessions(),System.currentTimeMillis())
                            store.save(ReadingGoals.purchase(goal,fresh,System.currentTimeMillis(),bought),entry.raw)
                        }
                        dialog.dismiss()
                        openPurchaseGalleryModal(entry.id)
                    }
                },
                LinearLayout.LayoutParams(0,-2,1.4f),
            )
        }
    }

    /** Centered Galeria modal inspecting a completed cycle's read books + purchased book (editable). */
    private fun openCycleDetailGalleryModal(entry:ReadingGoalStore.Entry,goal:ReadingGoal,cycle:GoalCycle,cycleNumber:Int){
        if(!valid())return
        val hairline=ui.galleryHairline(105)
        showGalleryModal(
            heading="Ciclo $cycleNumber",
            trailingLabel=if(cycle.purchased.isEmpty())"Informar livro" else "Editar livro",
            onTrailing={dlg->dlg.dismiss();openEditCyclePurchasedDialog(entry,goal,cycle,reopenGalleryCycle=cycleNumber)},
        ){ body, _ ->
            body.addView(gText(ReadingDays.label(ReadingDays.at(cycle.at)),11.5f,ui.muted),LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(12)})

            body.addView(gText("LIVRO COMPRADO",10f,ui.muted,medium=true).apply {letterSpacing=0.08f},LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(4)})
            val bought=cycle.purchased.joinToString(", ")
            body.addView(
                gText(if(bought.isNotBlank()) bought else "Não informado",17f,if(bought.isNotBlank()) ui.accent else ui.muted,serif=bought.isNotBlank()),
                LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(14)},
            )

            body.addView(View(activity).apply {setBackgroundColor(hairline)},LinearLayout.LayoutParams(-1,1).apply {bottomMargin=ui.dp(12)})
            body.addView(gText("LIDOS PARA LIBERAR",10f,ui.muted,medium=true).apply {letterSpacing=0.08f},LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(8)})
            cycle.books.forEach { credit ->
                val row=ui.row().apply {gravity=Gravity.CENTER_VERTICAL;setPadding(0,ui.dp(6),0,ui.dp(6))}
                body.addView(row,LinearLayout.LayoutParams(-1,-2))
                val img=ImageView(activity).apply {
                    scaleType=ImageView.ScaleType.CENTER_CROP
                    background=ui.pillShape(ui.activeSurface,4);clipToOutline=true
                    setImageDrawable(br.com.viblio.ui.ViblioBookMarkDrawable(ui.accent,Color.WHITE,scale=0.52f,backgroundColor=ui.activeSurface))
                    images.thumbnail(credit.cover)?.let(::setImageBitmap)
                }
                row.addView(img,LinearLayout.LayoutParams(ui.dp(30),ui.dp(44)).apply {marginEnd=ui.dp(10)})
                val col=ui.column();row.addView(col,LinearLayout.LayoutParams(0,-2,1f))
                col.addView(gText(credit.title,13f,ui.text,medium=true).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(2)})
                col.addView(gText(ReadingDays.label(ReadingDays.at(credit.at)),10.5f,ui.muted),LinearLayout.LayoutParams(-1,-2))
            }
        }
    }

    private fun openEditCyclePurchasedDialog(entry:ReadingGoalStore.Entry,goal:ReadingGoal,cycle:GoalCycle,reopenGalleryCycle:Int?=null){
        val body=ui.column().apply {setPadding(ui.dp(20),ui.dp(10),ui.dp(20),ui.dp(4))}
        val input=EditText(activity).apply {
            hint="Título do livro comprado"
            setText(cycle.purchased.joinToString(", "))
            setTextColor(ui.text);setHintTextColor(ui.muted)
            isSingleLine=true;filters=arrayOf(InputFilter.LengthFilter(200))
        }
        body.addView(input,LinearLayout.LayoutParams(-1,-2))
        tracked(AlertDialog.Builder(activity)
            .setTitle("Livro comprado")
            .setView(body)
            .setNeutralButton("Escolher da Estante"){_,_->
                val shelfBooks=repo.books(false).sortedByDescending {it.updatedAt}
                if(shelfBooks.isEmpty()){Toast.makeText(activity,"Nenhum livro na Estante.",Toast.LENGTH_SHORT).show();return@setNeutralButton}
                val labels=shelfBooks.map {b->if(b.author.isNotBlank())"${b.title} · ${b.author}" else b.title}.toTypedArray()
                tracked(AlertDialog.Builder(activity).setTitle("Escolher da Estante").setItems(labels){_,idx->
                    val chosenTitle=shelfBooks[idx].title
                    mutate {store.save(ReadingGoals.updateCyclePurchased(goal,cycle.id,listOf(chosenTitle)),entry.raw)}
                    if(reopenGalleryCycle!=null) openPurchaseGalleryModal(entry.id)
                }.setNegativeButton("Cancelar",null).create())
            }
            .setPositiveButton("Salvar"){_,_->
                val raw=input.text.toString().trim()
                val list=if(raw.isEmpty()) emptyList() else listOf(raw)
                mutate {store.save(ReadingGoals.updateCyclePurchased(goal,cycle.id,list),entry.raw)}
                if(reopenGalleryCycle!=null) openPurchaseGalleryModal(entry.id)
            }
            .setNegativeButton("Cancelar",null).create())
    }

    private fun purchase(parent:LinearLayout,entry:ReadingGoalStore.Entry,goal:ReadingGoal,progress:GoalProgress):LinearLayout {
        val unlockedCycles=ReadingGoals.unlockedCycles(goal,progress)
        val stackedRewards=ReadingGoals.stackedRewards(goal,progress)
        val unlocked=unlockedCycles>0
        val ribbon=ui.row();parent.addView(ribbon)
        ui.label(ribbon,if(unlockedCycles>1)"CICLO ${goal.cycles.size+1} · ×$unlockedCycles STACK" else "CICLO ${goal.cycles.size+1}",9,ui.muted).apply {letterSpacing=.12f;layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
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
                cover.setOnClickListener {
                    tracked(AlertDialog.Builder(activity)
                        .setTitle(credit.title)
                        .setMessage(listOf(credit.author,"Concluído · ${ReadingDays.label(ReadingDays.at(credit.at))}").filter {it.isNotBlank()}.joinToString("\n"))
                        .setNeutralButton("Retirar desta meta"){_,_->excludeFromPurchaseGoal(entry,goal,credit.bookId)}
                        .setNegativeButton("Fechar",null).create())
                }
            }
            ui.label(item,credit?.title ?: "A ler",9,if(credit!=null)ui.text else ui.muted).apply {maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;(layoutParams as LinearLayout.LayoutParams).topMargin=ui.dp(4)}
        }
        if(goal.target>slots)ui.label(earned,"+ ${goal.target-slots} no ciclo",10,ui.muted)
        val displayReward=if(unlocked) stackedRewards else goal.reward
        val ticket=ui.column().apply {gravity=Gravity.CENTER;background=ui.outlined(if(unlocked)ui.activeSurface else ui.surface,if(unlocked)ui.accent else ui.lineColor,0);setPadding(ui.dp(5),ui.dp(6),ui.dp(5),ui.dp(6));tag="goal-purchase-reward:${goal.id}";contentDescription="$displayReward ${if(displayReward==1)"livro" else "livros"} · ${if(unlocked)"compra liberada" else "compra bloqueada"}"}
        body.addView(ticket,LinearLayout.LayoutParams(ui.dp(88),ui.dp(112)).apply {marginStart=ui.dp(12)})
        ui.label(ticket,if(unlockedCycles>1)"STACK" else "RECOMPENSA",8,ui.muted).apply {gravity=Gravity.CENTER;letterSpacing=.08f}
        title(ticket,displayReward.toString(),42,ui.accent).apply {gravity=Gravity.CENTER;(layoutParams as LinearLayout.LayoutParams).bottomMargin=0}
        ui.label(ticket,if(displayReward==1)"livro" else "livros",10,ui.text).gravity=Gravity.CENTER
        val seal=ui.row().apply {gravity=Gravity.CENTER};ticket.addView(seal)
        seal.addView(ImageView(activity).apply {setImageDrawable(ui.symbol(if(unlocked)"check" else "lock",if(unlocked)ui.accent else ui.muted,12))},LinearLayout.LayoutParams(ui.dp(12),ui.dp(12)).apply {marginEnd=ui.dp(3)})
        ui.label(seal,if(unlocked)"LIBERADA" else "BLOQUEADA",8,if(unlocked)ui.accent else ui.muted).layoutParams=LinearLayout.LayoutParams(-2,-2)
        ui.label(parent,if(unlockedCycles>1)"$unlockedCycles compras acumuladas ($stackedRewards livros)" else if(unlocked)"Compra liberada" else if(progress.remaining(goal)==1L)"Falta 1 leitura" else "Faltam ${progress.remaining(goal)} leituras",11,ui.accent).tag="goal-purchase-status:${goal.id}"
        if(progress.value>goal.target)ui.label(parent,"+ ${numbers.format(progress.value-goal.target)} para o próximo ciclo",10,ui.muted)
        val actions=ui.row();parent.addView(actions)
        val details=ui.column();actions.addView(details,LinearLayout.LayoutParams(0,-2,1f))
        if(unlocked)ui.action(actions,"Registrar compra",primary=true){confirmPurchase(entry)}.apply {tag="goal-purchase:${goal.id}";minHeight=ui.dp(36);textSize=11f;layoutParams=LinearLayout.LayoutParams(-2,ui.dp(36))}
        return details
    }
    private fun creditRow(parent:LinearLayout,credit:GoalCredit,pages:Boolean,sessions:List<ReadingSession>,goal:ReadingGoal?=null,entry:ReadingGoalStore.Entry?=null){
        val row=ui.row().apply {tag="goal-credit:${credit.bookId}";setPadding(0,ui.dp(6),0,ui.dp(6));isFocusable=true};parent.addView(row)
        val image=ImageView(activity).apply {scaleType=ImageView.ScaleType.FIT_CENTER;setImageDrawable(if(ui.rectilinear) br.com.viblio.ui.ViblioBookMarkDrawable(ui.accent,android.graphics.Color.WHITE,scale=0.52f,backgroundColor=ui.activeSurface) else ui.symbol("books",ui.muted,32));images.thumbnail(credit.cover)?.let(::setImageBitmap)}
        row.addView(image,LinearLayout.LayoutParams(ui.dp(34),ui.dp(50)).apply {marginEnd=ui.dp(10)})
        val labels=ui.column();row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        ui.label(labels,credit.title,13).apply {maxLines=2}
        ui.label(labels,if(pages)if(credit.pages==1L)"1 página" else "${numbers.format(credit.pages)} páginas" else "Concluído · ${ReadingDays.label(ReadingDays.at(credit.at))}",10,ui.muted)
        row.setOnClickListener {
            val records=sessions.filter {it.id in credit.sessionIds}
            val message=if(records.isEmpty())credit.author else records.sortedBy {it.finishedAt}.joinToString("\n\n"){"${ReadingDays.label(it.readingDay ?: ReadingDays.at(it.startedAt))}\nPáginas ${if(pages && goal!=null && it.startedAt<goal.fromAt)maxOf(it.startPage,goal.baseline[it.bookId] ?: 0)else it.startPage} → ${it.endPage} · ${readingTime(it.durationMs)}"}
            val builder=AlertDialog.Builder(activity).setTitle(credit.title).setMessage(message).setNegativeButton("Fechar",null)
            if(goal!=null && entry!=null && goal.metric==GoalMetric.PURCHASE){
                builder.setNeutralButton("Retirar desta meta"){_,_->excludeFromPurchaseGoal(entry,goal,credit.bookId)}
            }
            tracked(builder.create())
        }
    }
    private fun confirmPurchase(entry:ReadingGoalStore.Entry){
        val goal=requireNotNull(entry.goal)
        val wishlistHandler=onRedeemPurchaseWithWishlist
        val inputWrap=ui.column().apply {setPadding(ui.dp(20),ui.dp(6),ui.dp(20),0)}
        val bookInput=EditText(activity).apply {
            hint="Livro comprado (opcional)"
            setTextColor(ui.text);setHintTextColor(ui.muted)
            isSingleLine=true;filters=arrayOf(InputFilter.LengthFilter(200))
        }
        inputWrap.addView(bookInput,LinearLayout.LayoutParams(-1,-2))
        val builder=AlertDialog.Builder(activity)
            .setTitle("Registrar compra?")
            .setMessage(if(goal.reward==1)"Compra liberada para 1 livro." else "Compra liberada para ${goal.reward} livros.")
            .setView(inputWrap)
            .setNegativeButton("Cancelar",null)
        if(wishlistHandler!=null){
            builder.setPositiveButton("Escolher da Wishlist"){_,_->
                val progress=ReadingGoals.progress(goal,repo.books(true),repo.sessions(),System.currentTimeMillis())
                if(progress.value>=goal.target) wishlistHandler(entry,goal,progress)
                else Toast.makeText(activity,"A compra ainda não foi liberada.",Toast.LENGTH_LONG).show()
            }
            builder.setNeutralButton("Registrar manual"){_,_->mutate {
                val progress=ReadingGoals.progress(goal,repo.books(true),repo.sessions(),System.currentTimeMillis())
                val typed=bookInput.text.toString().trim()
                store.save(ReadingGoals.purchase(goal,progress,System.currentTimeMillis(),if(typed.isNotEmpty()) listOf(typed) else emptyList()),entry.raw)
            }}
        } else {
            builder.setPositiveButton("Registrar"){_,_->mutate {
                val progress=ReadingGoals.progress(goal,repo.books(true),repo.sessions(),System.currentTimeMillis())
                val typed=bookInput.text.toString().trim()
                store.save(ReadingGoals.purchase(goal,progress,System.currentTimeMillis(),if(typed.isNotEmpty()) listOf(typed) else emptyList()),entry.raw)
            }}
        }
        tracked(builder.create())
    }
    private fun remove(entry:ReadingGoalStore.Entry){tracked(AlertDialog.Builder(activity).setTitle("Remover meta?").setPositiveButton("Remover"){_,_->mutate {store.remove(entry)}}.setNegativeButton("Cancelar",null).create())}
    private fun edit(entry:ReadingGoalStore.Entry?,defaultMetric:GoalMetric?=null){
        if(!valid())return
        val old=entry?.goal
        val body=ui.column().apply {setPadding(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(8))}
        fun text(value:String)=ui.label(body,value,12,ui.muted)
        fun input(hint:String,limit:Int,numeric:Boolean=false)=EditText(activity).apply {this.hint=hint;isSingleLine=true;filters=arrayOf(android.text.InputFilter.LengthFilter(limit));inputType=if(numeric)android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT;setTextColor(ui.text);setHintTextColor(ui.muted);body.addView(this,LinearLayout.LayoutParams(-1,ui.dp(48)))}
        text("Nome da meta");val name=input("Nome",80).apply {tag="goal-name";setText(old?.name.orEmpty())}
        fun spinner(label:String,options:List<String>,position:Int)=Spinner(activity).apply {text(label);adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,options);setSelection(position);body.addView(this,LinearLayout.LayoutParams(-1,ui.dp(44)));contentDescription=label}
        val metric=spinner("Tipo",GoalMetric.entries.map {it.label},old?.metric?.ordinal ?: defaultMetric?.ordinal ?: 0).apply {isEnabled=old==null;tag="goal-metric"}
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
