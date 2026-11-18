package br.com.centralcasa.ui

import android.app.Dialog
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import br.com.centralcasa.books.*
import java.util.Locale

/** Manual dates are separate from measured sessions; they never create pages or time. */
class ReadingDatesEditor(private val activity: Activity, private val ui: DisplayUi, private val repository: LibraryRepository, private val changed: () -> Unit) {
    private val dialogs=mutableSetOf<Dialog>()
    val isEditing get()=dialogs.isNotEmpty()
    fun close(){dialogs.toList().forEach {it.dismiss()};dialogs.clear()}
    private fun track(dialog:Dialog){dialogs.add(dialog);dialog.setOnDismissListener {dialogs.remove(dialog)};dialog.show()}
    fun pick(initial: String = ReadingDays.at(System.currentTimeMillis()), selected: (String) -> Unit) {
        val parts = initial.split('-').map(String::toInt)
        track(DatePickerDialog(activity, { _, y, m, d -> selected(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)) }, parts[0], parts[1] - 1, parts[2]))
    }
    fun show(book: LibraryBook) {
        val content = ui.column().apply { setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(18)) }
        ui.label(content, "Adicionar datas", 22, bold = true)
        ui.label(content, "Para livros que você já leu. Você pode informar somente o dia da conclusão. As datas não alteram páginas ou tempo.", 13, ui.muted)
        var mode = 0
        val days = sortedSetOf<String>()
        var first = ReadingDays.at(System.currentTimeMillis()); var last = first
        val modes = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }; content.addView(modes)
        val fields = ui.column(); content.addView(fields)
        val feedback = ui.label(content, "", 13, ui.muted)
        val dialog = AlertDialog.Builder(activity).setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Salvar datas", null).setNegativeButton("Cancelar", null).create()
        val modeButtons = mutableListOf<android.widget.TextView>()
        fun render() {
            modeButtons.forEachIndexed { i, button -> button.text = (if (i == mode) "✓ " else "") + listOf("Um dia", "Vários dias", "Período")[i]; button.isSelected = i == mode }
            fields.removeAllViews()
            feedback.text = when (mode) { 2 -> "Todos os dias do período serão registrados."; else -> "${days.size} dia(s) selecionado(s)" }
            if (mode == 2) {
                ui.action(fields, "Início · ${ReadingDays.label(first)}") { pick(first) { first = it; render() } }
                ui.action(fields, "Fim · ${ReadingDays.label(last)}") { pick(last) { last = it; render() } }
            } else {
                ui.action(fields, if (mode == 0) "Escolher dia" else "+ Escolher outro dia") {
                    pick(days.lastOrNull() ?: first) { if (mode == 0) days.clear(); days.add(it); render() }
                }
                days.forEach { day -> ui.action(fields, "${ReadingDays.label(day)}  ×") { days.remove(day); render() } }
            }
        }
        listOf("Um dia", "Vários dias", "Período").forEachIndexed { i, name ->
            modeButtons.add(ui.action(modes, name) { mode = i; if (i == 0 && days.size > 1) { val day = days.last(); days.clear(); days.add(day) }; render() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f); textSize = 12f
            })
        }
        render(); track(dialog)
        dialog.window?.apply { setGravity(Gravity.BOTTOM); setLayout(-1, -2) }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val selected = if (mode == 2) ReadingDays.range(first, last) else days.toList()
                require(selected.isNotEmpty()) { "Escolha ao menos uma data." }
                repository.addDates(book.id, selected); dialog.dismiss(); changed()
            } catch (e: Exception) { feedback.text = e.message ?: "Não foi possível salvar as datas." }
        }
    }
    fun editSession(session:ReadingSession){
        var day=session.readingDay ?: ReadingDays.at(session.startedAt)
        val body=ui.column().apply {setPadding(ui.dp(18),ui.dp(10),ui.dp(18),ui.dp(8))}
        ui.action(body,ReadingDays.label(day)){pick(day){day=it;body.findViewWithTag<android.widget.TextView>("session-edit-day").text=ReadingDays.label(day)}}.apply {tag="session-edit-day"}
        ui.label(body,"Tempo de leitura",12,ui.muted)
        val row=ui.row();body.addView(row)
        fun field(hint:String,value:Long,tagName:String,limit:Int)=EditText(activity).apply {
            this.hint=hint;setText(value.toString());setTextColor(ui.text);setHintTextColor(ui.muted);isSingleLine=true;inputType=InputType.TYPE_CLASS_NUMBER
            filters=arrayOf(InputFilter.LengthFilter(limit));tag=tagName;contentDescription=hint
            row.addView(this,LinearLayout.LayoutParams(0,-2,1f))
        }
        val seconds=session.durationMs/1000
        val hours=field("Horas",seconds/3600,"session-edit-hours",6)
        val minutes=field("Minutos",seconds/60%60,"session-edit-minutes",2)
        val secs=field("Segundos",seconds%60,"session-edit-seconds",2)
        val feedback=ui.label(body,"",12,ui.muted)
        val dialog=AlertDialog.Builder(activity).setTitle("Editar leitura").setView(body)
            .setPositiveButton("Salvar",null).setNegativeButton("Cancelar",null).setNeutralButton("Apagar",null).create()
        track(dialog);dialog.window?.setBackgroundDrawable(ui.shape(ui.surface,0))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val h=hours.text.toString().toLongOrNull() ?: 0L;val m=minutes.text.toString().toLongOrNull() ?: 0L;val sec=secs.text.toString().toLongOrNull() ?: 0L
                require(h in 0..999999 && m in 0..59 && sec in 0..59){"Confira horas, minutos e segundos."}
                val value=(h*3600+m*60+sec)*1000
                repository.editReadingSession(session,day,if(value==session.durationMs/1000*1000)session.durationMs else value)
                dialog.dismiss();changed()
            }catch(e:Exception){feedback.text=e.message ?: "Não foi possível editar a leitura."}
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            track(AlertDialog.Builder(activity).setTitle("Apagar registro de leitura?")
                .setMessage("O registro e seu tempo serão removidos. A página atual será mantida.")
                .setPositiveButton("Apagar"){_,_->try {repository.deleteReadingSession(session);dialog.dismiss();changed()}catch(e:Exception){feedback.text=e.message ?: "Não foi possível apagar a leitura."}}
                .setNegativeButton("Cancelar",null).create())
        }
    }
    fun editDay(day: String, sessions: List<ReadingSession>, dates: List<ReadingDate>) {
        val options = sessions.map { "Editar leitura · ${it.pages} páginas · ${readingTime(it.durationMs)}" } +
            if (dates.isNotEmpty()) listOf("Corrigir data informada", "Remover data informada") else emptyList()
        track(AlertDialog.Builder(activity).setTitle(ReadingDays.label(day)).setItems(options.toTypedArray()) { _, index ->
            if (index < sessions.size) editSession(sessions[index])
            else if (options[index] == "Corrigir data informada") pick(day) { value -> dates.forEach { repository.changeDate(it, value) }; changed() }
            else track(AlertDialog.Builder(activity).setTitle("Remover esta data?")
                .setMessage("As sessões cronometradas serão mantidas.")
                .setPositiveButton("Remover") { _, _ -> dates.forEach { repository.deleteDate(it.id) }; changed() }.setNegativeButton("Cancelar", null).create())
        }.setNegativeButton("Cancelar",null).create())
    }
}
