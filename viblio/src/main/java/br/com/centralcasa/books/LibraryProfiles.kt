package br.com.centralcasa.books
import android.content.Context
import java.security.MessageDigest
/** Separate Viblio profiles; the Central always keeps its original storage. */
object LibraryProfiles {
    private fun prefs(c:Context)=c.getSharedPreferences("viblio_profiles",0)
    fun key(server:String,user:String)=MessageDigest.getInstance("SHA-256").digest("$server:$user".toByteArray()).joinToString("") {"%02x".format(it)}
    fun database(c:Context):String {
        if(c.packageName!="br.com.viblio")return "library.db"
        return prefs(c).getString("active_db","library.db").orEmpty().takeIf {it=="library.db" || it=="guest-library.db" || it.matches(Regex("account-[a-f0-9]{64}\\.db"))} ?: "library.db"
    }
    fun suffix(c:Context)=database(c).takeUnless {it=="library.db"}?.removeSuffix(".db")?.let {"_$it"}.orEmpty()
    fun settings(c:Context)="panel_settings"+if(c.packageName=="br.com.viblio")suffix(c) else ""
    fun owner(c:Context)=prefs(c).getString("owner:${database(c)}","").orEmpty()
    fun mapped(c:Context,key:String)=prefs(c).getString("map:$key",null)
    fun anonymous(c:Context) {val p=prefs(c);check(p.edit().putString("active_db",if(p.getString("owner:library.db","").isNullOrBlank()) "library.db" else "guest-library.db").commit())}
    fun select(c:Context,key:String,claim:Boolean) {
        val p=prefs(c);val target=mapped(c,key) ?: if(claim && owner(c).isBlank())database(c) else "account-$key.db"
        check(p.getString("owner:$target",key)==key)
        check(p.edit().putString("map:$key",target).putString("owner:$target",key).putString("active_db",target).commit())
    }
}
