package br.com.viblio.ui
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.widget.*
import br.com.centralcasa.ui.DisplayUi
import br.com.centralcasa.R
import br.com.centralcasa.books.*
import br.com.viblio.account.AccountManager
class AccountPanel(private val activity:Activity,private val ui:DisplayUi,private val accounts:AccountManager,private val changed:()->Unit,private val shelf:()->Unit,private val backup:(()->Unit)?=null,private val onInstallUpdate:((br.com.viblio.account.AppUpdateRelease)->Unit)?=null) {
    private var mode="login";private var serverExpanded=false;private var bootstrapExpanded=false;private val form=mutableMapOf<String,String>()
    fun renderLogin(parent:LinearLayout,offline:()->Unit) {
        val page=object:LinearLayout(activity){
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
                val width=android.view.View.MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(ui.dp(440))
                super.onMeasure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),heightMeasureSpec)
            }
        }.apply {orientation=LinearLayout.VERTICAL}
        parent.addView(page,LinearLayout.LayoutParams(-1,-2).apply {gravity=Gravity.CENTER_HORIZONTAL})
        render(page,true,offline)
    }
    fun render(parent:LinearLayout,login:Boolean=false,offline:(()->Unit)?=null) {
        if(!login){val header=ui.row();parent.addView(header);ui.icon(header,"back","Voltar à Estante",44){shelf()};ui.label(header,if(ui.rectilinear)"CONTA" else "Conta",32,bold=true).apply {typeface=Typeface.create("serif",if(ui.rectilinear)Typeface.NORMAL else Typeface.BOLD);layoutParams=LinearLayout.LayoutParams(0,-2,1f)}}
        val brand=ui.row().apply {gravity=Gravity.CENTER_VERTICAL;setPadding(0,ui.dp(if(login)28 else 16),0,ui.dp(24))};parent.addView(brand)
        brand.addView(ImageView(activity).apply {setImageDrawable(ViblioBookMarkDrawable(ui.accent,android.graphics.Color.WHITE));contentDescription="Viblio"},LinearLayout.LayoutParams(ui.dp(52),ui.dp(52)).apply {marginEnd=ui.dp(14)})
        val name=ui.column();brand.addView(name);ui.label(name,"Viblio",34,bold=true).typeface=Typeface.create("serif",Typeface.BOLD);ui.label(name,"Sua estante, do seu jeito",13,ui.muted)
        val card=ui.column().apply {setPadding(ui.dp(18),ui.dp(18),ui.dp(18),ui.dp(18));background=ui.outlined(if(ui.rectilinear)ui.surface else 0xff141719.toInt(),if(ui.rectilinear)ui.lineColor else 0xff303639.toInt(),24)};parent.addView(card)
        if(accounts.signedIn){
            mode="login"
            form.remove("password");form.remove("invitation");form.remove("key")
            ui.label(card,accounts.username.takeIf(String::isNotBlank)?.let {"@$it"} ?: accounts.email,19,bold=true)
            if(accounts.username.isNotBlank())ui.label(card,accounts.email,12,ui.muted)
            ui.action(card,if(accounts.username.isBlank())"Definir nome de usuário" else "Editar nome de usuário",onClick=accounts::editUsername)
            ui.label(card,if(accounts.admin)"Administrador · testes por convite" else "Sua conta · estante visível a amigos aceitos",12,ui.muted)
            ui.label(card,accounts.status.ifBlank {if(accounts.linked)"Estante vinculada neste aparelho." else "Escolha como vincular sua Estante."},13,ui.muted).apply {setPadding(0,ui.dp(14),0,ui.dp(8))}
            if(!accounts.linked)ui.action(card,"Vincular minha Estante",true,accounts::link)
            else {ui.label(card,"As alterações são sincronizadas automaticamente ao usar o app conectado.",12,ui.muted);ui.action(card,"Conferir sincronização",onClick=accounts::sync);ui.action(card,"Backups e restauração",onClick=accounts::backups)}
            if(accounts.admin){ui.label(card,"ADMINISTRAÇÃO",11,ui.muted).apply {setPadding(0,ui.dp(18),0,ui.dp(6))};ui.action(card,"Gerar convite",onClick=accounts::createInvitation);ui.action(card,"Gerenciar convites",onClick=accounts::manageInvitations);ui.action(card,"Recuperar acesso de uma conta",onClick=accounts::recoverUser);backup?.let {ui.action(card,"Importar / exportar arquivo e servidor",onClick=it)}}
            else backup?.let {ui.action(card,"Catálogo do servidor",onClick=it)}
            ui.action(card,"Sair da conta",onClick=accounts::logout)
        }else if(login) {
            ui.label(card,when(mode){"create"->"Criar conta por convite";"recover"->"Recuperar acesso";else->"Sua próxima leitura começa aqui"},24,bold=true)
            ui.label(card,when(mode){"create"->"Use o convite recebido para criar sua Estante privada.";"recover"->"Vamos recuperar o acesso à sua Estante.";else->"Entre para guardar seus livros, notas e leituras entre aparelhos."},12,ui.muted)
            fun field(key:String,label:String,password:Boolean=false,default:String="") {
                ui.label(card,label,12,ui.muted).apply {setPadding(0,ui.dp(12),0,ui.dp(5))}
                val input=EditText(activity).apply {
                    hint=label;setText(form[key] ?: default);textSize=14f;setTextColor(ui.text);setHintTextColor(ui.muted)
                    inputType=InputType.TYPE_CLASS_TEXT or when {password->InputType.TYPE_TEXT_VARIATION_PASSWORD;key=="email" && mode=="create"->InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;key=="server"->InputType.TYPE_TEXT_VARIATION_URI;else->InputType.TYPE_TEXT_VARIATION_NORMAL}
                    isSingleLine=true;setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10));background=ui.outlined(0x55151515)
                }
                if(password)input.transformationMethod=android.text.method.PasswordTransformationMethod.getInstance()
                input.filters=arrayOf(android.text.InputFilter.LengthFilter(when(key){"password"->128;"username"->32;"email"->254;"server"->2048;else->512}))
                if(!form.containsKey(key))form[key]=default
                input.addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){form[key]=s.toString()};override fun afterTextChanged(s:Editable?){} })
                if(password && key=="password") {
                    val row=ui.row().apply {background=if(ui.rectilinear)ui.outlined(ui.surface) else ui.outlined(0xff1e2225.toInt(),0xff3a4145.toInt(),12)}
                    input.background=null;row.addView(input,LinearLayout.LayoutParams(0,ui.dp(54),1f))
                    ui.action(row,"Mostrar") {
                        val showing=input.transformationMethod !is android.text.method.PasswordTransformationMethod
                        input.transformationMethod=if(showing)android.text.method.PasswordTransformationMethod.getInstance() else null
                        input.setSelection(input.text.length)
                        (row.getChildAt(1) as TextView).apply {text=if(showing)"Mostrar" else "Ocultar";contentDescription=if(showing)"Mostrar senha" else "Ocultar senha"}
                    }.apply {background=null;layoutParams=LinearLayout.LayoutParams(ui.dp(76),ui.dp(54));contentDescription="Mostrar senha"}
                    card.addView(row)
                }else card.addView(input,LinearLayout.LayoutParams(-1,ui.dp(54)))
                if(android.os.Build.VERSION.SDK_INT>=26)input.setAutofillHints(when(key){"password"->if(mode=="login")android.view.View.AUTOFILL_HINT_PASSWORD else "newPassword";"email"->if(mode=="create")android.view.View.AUTOFILL_HINT_EMAIL_ADDRESS else android.view.View.AUTOFILL_HINT_USERNAME;"username"->"newUsername";else->""})
            }
            if(accounts.defaultAddress.isNotBlank())form["server"]=accounts.defaultAddress
            else if(!form.containsKey("server"))form["server"]=accounts.address
            val manualServer=accounts.defaultAddress.isBlank() || form["server"]!=accounts.defaultAddress
            if(manualServer){
                if(form["server"].isNullOrBlank() || serverExpanded)field("server","Endereço do servidor",default=accounts.address)
                else ui.action(card,"Servidor · ${runCatching {android.net.Uri.parse(form["server"]).host}.getOrNull().orEmpty()} ▾") {serverExpanded=true;changed()}.apply {background=null;gravity=Gravity.START;setTextColor(ui.muted)}
            }
            if(mode=="create"){field("username","Nome de usuário");field("email","E-mail")}
            else if(mode=="login")field("email","Usuário ou e-mail")
            field("password",if(mode=="login")"Senha" else "Senha (mínimo 12 caracteres)",password=true)
            if(mode=="create"){
                field("invitation","Convite",password=true)
                if(manualServer)ui.action(card,if(bootstrapExpanded)"Configuração da primeira conta ▴" else "Configurar primeira conta ▾") {bootstrapExpanded=!bootstrapExpanded;changed()}.apply {background=null;setTextColor(ui.muted)}
                if(bootstrapExpanded){ui.label(card,"Somente o dono de um servidor ainda sem contas precisa da chave da instalação.",12,ui.muted);field("key","Chave da instalação (somente primeira conta)",password=true)}
            }
            if(mode=="recover"){field("invitation","Código de recuperação",password=true);ui.label(card,"Peça ao administrador um código de uso único. Não depende de e-mail automático.",12,ui.muted)}
            accounts.status.takeIf(String::isNotBlank)?.let {ui.label(card,it,12,ui.muted).apply {setPadding(0,ui.dp(12),0,0)}}
            ui.action(card,when(mode){"create"->"Criar conta";"recover"->"Definir nova senha";else->"Entrar"},true){
                val password=form["password"].orEmpty();if(password.length<12){Toast.makeText(activity,"Use pelo menos 12 caracteres na senha.",Toast.LENGTH_LONG).show();return@action}
                val code=form["invitation"].orEmpty().substringAfter("Convite:",form["invitation"].orEmpty()).substringBefore('\n').trim()
                val username=form["username"].orEmpty().trim().lowercase(java.util.Locale.ROOT)
                if(mode=="create" && !username.matches(Regex("[a-z0-9][a-z0-9_.]{2,31}"))){Toast.makeText(activity,"Escolha um nome com 3 a 32 letras, números, ponto ou sublinhado.",Toast.LENGTH_LONG).show();return@action}
                accounts.authenticate(form["server"].orEmpty().trim(),form["email"].orEmpty(),password,mode,code,form["key"].orEmpty().trim(),username)
            }
            if(mode!="create")ui.action(card,"Criar conta por convite"){mode="create";changed()}.apply {background=ui.outlined(0xff141719.toInt(),0xff3a4145.toInt(),16)}
            if(mode!="recover")ui.action(card,"Esqueci minha senha"){mode="recover";changed()}.apply {background=null;setTextColor(ui.muted)}
            if(mode!="login")ui.action(card,"Já tenho conta · Entrar"){mode="login";changed()}
        }else {ui.label(card,"Estante offline",21,bold=true);ui.label(card,"Seus dados continuam neste aparelho. Para entrar em uma conta, reabra o aplicativo.",13,ui.muted)}
        if(login){
            offline?.let {ui.action(parent,"Usar Estante offline",onClick=it).apply {background=null;setTextColor(ui.muted);(layoutParams as LinearLayout.LayoutParams).topMargin=ui.dp(14)}}
            ui.label(parent,"Livros · Mangás · Quadrinhos",11,ui.muted).apply {gravity=Gravity.CENTER;setPadding(0,ui.dp(12),0,ui.dp(20))}
            return
        }
        val iconEnabled=DynamicAppIcon.isEnabled(activity)
        ui.action(parent,if(iconEnabled)"Ícone do app na cor do livro: Ativado" else "Ícone do app na cor do livro: Desativado") {
            val next=!DynamicAppIcon.isEnabled(activity)
            DynamicAppIcon.setEnabled(activity,next)
            Toast.makeText(activity,if(next)"O ícone mudará ao sair do app." else "Ícone padrão restaurado.",Toast.LENGTH_SHORT).show()
            changed()
        }.apply {background=ui.outlined();(layoutParams as LinearLayout.LayoutParams).topMargin=ui.dp(16)}
        val localSettings=br.com.centralcasa.data.LocalSettings(activity)
        val galleryOn=localSettings.galleryUiStyle()
        ui.action(parent,if(galleryOn)"Visual do app: Galeria (novo)" else "Visual do app: Clássico") {
            val next=!localSettings.galleryUiStyle()
            localSettings.setGalleryUiStyle(next)
            Toast.makeText(activity,if(next)"Novo visual Galeria ativado." else "Visual Clássico restaurado.",Toast.LENGTH_SHORT).show()
            changed()
        }.apply {background=ui.outlined();(layoutParams as LinearLayout.LayoutParams).topMargin=ui.dp(10)}
        if(accounts.signedIn){
            val socialOn=br.com.viblio.reminder.SocialNotifier.isEnabled(activity)
            ui.action(parent,if(socialOn)"Avisos sociais: Ativado" else "Avisos sociais: Desativado") {
                val next=!br.com.viblio.reminder.SocialNotifier.isEnabled(activity)
                if(next && android.os.Build.VERSION.SDK_INT>=33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
                    activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),945)
                }
                br.com.viblio.reminder.SocialNotifier.setEnabled(activity,next)
                Toast.makeText(activity,if(next)"Avisos de clubes e convites ativados." else "Avisos sociais desativados.",Toast.LENGTH_SHORT).show()
                changed()
            }.apply {background=ui.outlined();(layoutParams as LinearLayout.LayoutParams).topMargin=ui.dp(10)}
        }
        ui.action(parent,"Removidos da Estante",onClick=::removed).apply {background=ui.outlined();(layoutParams as LinearLayout.LayoutParams).topMargin=ui.dp(10)}
        ui.action(parent,"Continuar na Estante",onClick=shelf)
        val version=br.com.viblio.account.AppUpdateChecker.currentVersion(activity)
        ui.action(parent,"Viblio ${version.versionName} (${version.versionCode}) · Verificar atualização") {
            checkForUpdate(version)
        }.apply {
            tag="account-app-version"
            background=null
            setTextColor(ui.muted)
            textSize=12f
            gravity=Gravity.CENTER
            (layoutParams as LinearLayout.LayoutParams).topMargin=ui.dp(10)
            (layoutParams as LinearLayout.LayoutParams).bottomMargin=ui.dp(8)
        }
    }
    @Volatile private var checkingUpdate=false
    private fun checkForUpdate(version:br.com.viblio.account.AppVersionInfo){
        if(checkingUpdate)return
        checkingUpdate=true
        Toast.makeText(activity,"Verificando atualizações…",Toast.LENGTH_SHORT).show()
        java.util.concurrent.Executors.newSingleThreadExecutor().let { exec ->
            exec.execute {
                val result=runCatching {br.com.viblio.account.AppUpdateChecker.fetchLatestNewer(version.versionName)}
                exec.shutdown()
                activity.runOnUiThread {
                    checkingUpdate=false
                    if(activity.isFinishing || activity.isDestroyed)return@runOnUiThread
                    result.fold({release->
                        br.com.viblio.account.AppUpdateChecker.recordCheckResult(activity,release)
                        if(release==null){
                            Toast.makeText(activity,"Você já está na versão mais recente (${version.versionName}).",Toast.LENGTH_LONG).show()
                        }else {
                            AlertDialog.Builder(activity)
                                .setTitle("Nova versão ${release.version}")
                                .setMessage("Versão atual: ${version.versionName}. Baixar e instalar a ${release.version}?")
                                .setPositiveButton("Atualizar") {_,_->
                                    val installer=onInstallUpdate
                                    if(installer!=null){
                                        installer(release)
                                    }else if(br.com.viblio.account.AppUpdateChecker.isAllowedApkUrl(release.apkUrl)){
                                        runCatching {
                                            activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(release.apkUrl)))
                                        }.onFailure {
                                            Toast.makeText(activity,"Não foi possível abrir o navegador.",Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                                .setNegativeButton("Agora não",null)
                                .show()
                        }
                    },{err->
                        Toast.makeText(activity,err.message ?: "Não foi possível verificar atualizações.",Toast.LENGTH_LONG).show()
                    })
                }
            }
        }
    }
    private fun removed() {
        val repo=LibraryRepository(activity);val titles=repo.collections(true).filter {it.archived};val books=repo.books(true).filter {it.archived};repo.close()
        val rows=titles.map {Triple(it.id,it.title+" · título",true)}+books.map {Triple(it.id,it.title+(it.sequenceNumber?.let {n->" · volume $n"} ?: ""),false)}
        if(rows.isEmpty()){Toast.makeText(activity,"Nenhum item removido neste perfil.",Toast.LENGTH_LONG).show();return}
        AlertDialog.Builder(activity).setTitle("Removidos · nota e histórico preservados").setItems(rows.map {it.second}.toTypedArray()) {_,index->val row=rows[index]
            AlertDialog.Builder(activity).setTitle(row.second).setItems(arrayOf("Restaurar na Estante","Ver nota e histórico","Excluir meus dados definitivamente")) {_,choice->
                if(choice==1){
                    val local=LibraryRepository(activity);val ids=if(row.third)local.books(true).filter {it.collectionId==row.first}.map {it.id} else listOf(row.first)
                    val records=local.sessions().filter {it.bookId in ids};val days=local.dates().filter {it.bookId in ids};val rating=if(row.third)local.collections(true).firstOrNull {it.id==row.first}?.rating else local.book(row.first)?.rating
                    val text="Nota: ${rating ?: "sem nota"}\n${records.size} sessões · ${days.size} datas\n\n"+days.take(100).joinToString("\n"){it.day};local.close()
                    AlertDialog.Builder(activity).setTitle("Seu histórico").setMessage(text).setPositiveButton("Fechar",null).show()
                }else if(choice==0)changeRemoved(row.first,row.third,false)
                else AlertDialog.Builder(activity).setTitle("Excluir seus dados?").setMessage("Nota, progresso e histórico serão apagados deste acervo. A exclusão será sincronizada. Backups anteriores podem conter versões antigas. O catálogo e outras contas permanecem intactos.").setPositiveButton("Excluir meus dados") {_,_->changeRemoved(row.first,row.third,true)}.setNegativeButton("Cancelar",null).show()
            }.setNegativeButton("Fechar",null).show()
        }.setNegativeButton("Fechar",null).show()
    }
    private fun changeRemoved(id:String,collection:Boolean,delete:Boolean){
        val repo=LibraryRepository(activity);val result=runCatching {if(delete){if(collection)repo.deleteCollection(id) else repo.delete(id)}else repo.archive(id,collection,removed=false)};repo.close()
        result.fold({accounts.localChanged();changed();Toast.makeText(activity,if(delete)"Seus dados foram excluídos deste acervo." else "Item restaurado na Estante.",Toast.LENGTH_LONG).show()},{Toast.makeText(activity,it.message,Toast.LENGTH_LONG).show()})
    }
}
