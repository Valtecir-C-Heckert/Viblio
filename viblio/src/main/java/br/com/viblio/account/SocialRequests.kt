package br.com.viblio.account

import com.google.gson.JsonObject
import java.io.IOException
import java.net.URI
import java.net.SocketTimeoutException

/** Small social responses must not inherit the private backup's two-minute budget. */
object SocialRequests {
    fun call(api:AccountApi,path:String,method:String="GET",body:JsonObject?=null,connection:AccountConnection?=null):JsonObject {
        val response=api.call(path,method,body,responseLimit=1024*1024,timeoutSeconds=if(method=="GET")12 else 30,tag=connection,connection=connection)
        val route=path.substringBefore('?')
        val listing=method=="GET" && (route in listOf("/v1/friends","/v1/friends/feed","/v1/friends/people","/v1/clubs") || route.endsWith("/messages") || route.endsWith("/books") || route.endsWith("/library") || route.endsWith("/feed"))
        if(listing && response.get("items")?.isJsonArray!=true)throw IOException("Resposta inválida do servidor. Tente novamente.")
        return response
    }
    fun message(failure:Throwable,address:String):String {
        if(failure is AccountFailure)return failure.message ?: "Não foi possível carregar. Tente novamente."
        val host=runCatching {URI(address).host}.getOrNull().orEmpty().ifBlank {"servidor"}
        return when {
            failure is SocketTimeoutException || failure is java.io.InterruptedIOException -> "O servidor $host demorou para responder. Tente novamente."
            failure is IOException && failure.message=="Resposta inválida do servidor. Tente novamente." -> failure.message!!
            failure is IOException -> "Não foi possível acessar $host. Tente novamente."
            else -> failure.message ?: "Não foi possível carregar. Tente novamente."
        }
    }
}
