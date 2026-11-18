package br.com.viblio.account

import com.google.gson.JsonObject
import java.util.UUID

/** Only this exact device preference is excluded. Book data, goals and other preferences still merge normally. */
internal object LibrarySyncPolicy {
    const val LOCAL_ZOOM="SHELF_SPINE_ZOOM"
    const val ZOOM_PATH="/appearance/SHELF_SPINE_ZOOM"
    fun shared(snapshot:JsonObject)=JsonObject().apply {
        snapshot.entrySet().forEach {(key,value)->
            add(key,if(key=="appearance" && value.isJsonObject)JsonObject().apply {
                value.asJsonObject.entrySet().filter {it.key!=LOCAL_ZOOM}.forEach {add(it.key,it.value)}
            }else value)
        }
    }
    fun localConflict(path:String)=path==ZOOM_PATH
    /** Legacy zoom removal can conflict with a changed server zoom. Retry only that field, bound to its revision. */
    fun submit(snapshot:JsonObject,base:Long,strict:Boolean=false,resolutions:JsonObject?=null,against:Long?=null,send:(JsonObject)->JsonObject):JsonObject {
        val local=shared(snapshot)
        var choices=resolutions?.deepCopy() ?: JsonObject();var revision=against
        repeat(3){attempt->
            val request=JsonObject().apply {
                addProperty("request_id",UUID.randomUUID().toString());addProperty("base_revision",base);addProperty("strict_base",strict);add("snapshot",local)
                if(choices.size()>0)add("resolutions",choices.deepCopy())
                revision?.let {addProperty("against_revision",it)}
            }
            try {return send(request)}catch(error:AccountFailure){
                val detail=error.response.get("detail")?.takeIf {it.isJsonObject}?.asJsonObject
                val conflicts=detail?.get("conflicts")?.takeIf {it.isJsonArray}?.asJsonArray
                val onlyZoom=error.code==409 && detail?.get("code")?.asString=="conflicts" && conflicts!=null && conflicts.size()>0 && conflicts.all {it.isJsonObject && it.asJsonObject.get("path")?.asString==ZOOM_PATH}
                val userChoices=choices.keySet().any {!localConflict(it)}
                val changedZoomRevision=error.code==409 && detail?.get("code")?.asString=="changed_again" && !userChoices && choices.size()>0
                if(userChoices || !onlyZoom && !changedZoomRevision)throw error
                if(attempt==2)throw AccountFailure(409,JsonObject().apply {add("detail",JsonObject().apply {addProperty("code","changed_again");addProperty("message","O servidor mudou durante a sincronização. Tente novamente.")})},"O servidor mudou durante a sincronização. Tente novamente.")
                if(onlyZoom){
                    val next=detail!!.get("revision")?.asLong ?: throw error
                    if(next<base)throw error
                    choices=JsonObject().apply {addProperty(ZOOM_PATH,"local")};revision=next
                }else {choices=JsonObject();revision=null}
            }
        }
        error("Limite de tentativas atingido.")
    }
}
