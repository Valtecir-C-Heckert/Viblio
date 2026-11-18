package br.com.viblio.homeassistant

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import br.com.centralcasa.books.BookImages
import br.com.centralcasa.books.LibraryRepository
import br.com.centralcasa.data.HomeAssistantCredentials
import br.com.centralcasa.ui.DisplayUi
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Optional outbound snapshot of this app's current reading; never reads the Central database. */
class HomeAssistantBridge(private val activity:Activity) {
    private val profileSuffix=br.com.centralcasa.books.LibraryProfiles.suffix(activity)
    private val secrets=HomeAssistantCredentials(activity,"viblio_ha_credentials"+profileSuffix,"viblio_ha_webhook"+profileSuffix)
    private val prefs=activity.getSharedPreferences("viblio_ha_status"+profileSuffix,0)
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private val library=LibraryRepository(activity)
    private val images=BookImages(activity)
    private var running=false
    private var closed=false
    private var lastSnapshot=""
    private var lastCover=""
    private val periodic=object:Runnable { override fun run() { if(running && !closed) { requestSync();handler.postDelayed(this,15000) } } }
    private val pending=Runnable { enqueue(false) }
    fun start() { running=true;handler.removeCallbacks(periodic);handler.post(periodic) }
    fun stop() { running=false;handler.removeCallbacks(periodic);handler.removeCallbacks(pending);enqueue(false) }
    fun changed() { if(!closed) { handler.removeCallbacks(pending);handler.postDelayed(pending,800) } }
    private fun requestSync() { if(!closed) enqueue(false) }
    private fun enqueue(force:Boolean,urlOverride:String?=null,done:((Boolean)->Unit)?=null) {
        if(closed) return
        val url=urlOverride ?: secrets.load()
        if(url.isBlank()) return
        worker.execute {
            val result=runCatching {
                val book=library.current()
                val color=book?.let { images.coverColor(it.cover) }?.let { String.format("#%06X",it and 0xffffff) }
                val data=ReadingWebhook.snapshot(book,color)
                val signature=data.toString()+":"+book?.cover.orEmpty()
                if(!force && signature==lastSnapshot) return@runCatching
                val coverKey=book?.cover.orEmpty()
                if(force || coverKey!=lastCover || signature!=lastSnapshot && book?.id==null) {
                    val bytes=book?.let { images.thumbnail(it.cover) }?.let { bitmap->ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,it) }.toByteArray() }
                    if(bytes==null) data.add("cover_base64",com.google.gson.JsonNull.INSTANCE)
                    else data.addProperty("cover_base64",Base64.encodeToString(bytes,Base64.NO_WRAP))
                }
                ReadingWebhook.send(url,data)
                lastSnapshot=signature;lastCover=coverKey
                prefs.edit().putLong("sent_at",System.currentTimeMillis()).putString("error","").apply()
            }
            result.exceptionOrNull()?.let { prefs.edit().putString("error",if(it is java.io.IOException) "Sem conexão. Tentaremos ao abrir o Viblio." else it.message ?: "Falha no envio.").apply() }
            if(done!=null) handler.post { if(!closed && !activity.isFinishing) done(result.isSuccess) }
        }
    }
    fun configure() {
        val ui=DisplayUi(activity)
        if(!secrets.supported) { Toast.makeText(activity,"A integração precisa do Android 6 ou superior.",Toast.LENGTH_LONG).show();return }
        val body=ui.column().apply { setPadding(ui.dp(18),ui.dp(12),ui.dp(18),0) }
        ui.label(body,"Livro atual no Home Assistant",18,bold=true)
        ui.label(body,"No HA, adicione a integração Viblio e copie a URL de pareamento. Somente capa, título, autor e progresso serão enviados.",13,ui.muted)
        val field=EditText(activity).apply { setText(secrets.load());hint="https://seu-ha/api/webhook/…";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;setTextColor(ui.text);setHintTextColor(ui.muted);isSingleLine=true }
        body.addView(field,LinearLayout.LayoutParams(-1,-2))
        val sent=prefs.getLong("sent_at",0)
        ui.label(body,if(sent==0L) "Ainda não conectado" else "Último envio: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(sent))}",12,ui.muted)
        prefs.getString("error","")?.takeIf { it.isNotBlank() }?.let { ui.label(body,it,12,ui.muted) }
        ui.label(body,"Atualiza ao usar o Viblio e ao sair dele. Com o app fechado, o HA mantém o último livro recebido.",12,ui.muted)
        val dialog=AlertDialog.Builder(activity).setTitle("Home Assistant").setView(body).setPositiveButton("Conectar e enviar",null).setNeutralButton("Desconectar",null).setNegativeButton("Fechar",null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { secrets.clear();lastSnapshot="";lastCover="";dialog.dismiss();Toast.makeText(activity,"Envio desativado. O HA mantém o último estado recebido.",Toast.LENGTH_LONG).show() }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url=field.text.toString().trim()
            if(!ReadingWebhook.validUrl(url)) { field.error="Use a URL completa de pareamento. HTTP somente na rede local/Tailscale.";return@setOnClickListener }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            enqueue(true,url) { success->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
                if(success) { secrets.save(url);dialog.dismiss();Toast.makeText(activity,"Livro atual enviado ao Home Assistant.",Toast.LENGTH_LONG).show() }
                else field.error=prefs.getString("error","Não foi possível conectar.")
            }
        }
    }
    fun close() { closed=true;running=false;handler.removeCallbacks(periodic);handler.removeCallbacks(pending);worker.execute { library.close() };worker.shutdown() }
}
