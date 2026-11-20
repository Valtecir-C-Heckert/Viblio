package br.com.centralcasa.books.catalog

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared process-wide OkHttp base clients to avoid leaking per-call thread pools and connection pools.
 * All derived clients via [OkHttpClient.newBuilder] share this Dispatcher and ConnectionPool.
 */
object ViblioHttp {
    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val account: OkHttpClient = base.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val catalog: OkHttpClient = base.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** External cover loader: no account headers, never downgrades HTTPS -> HTTP. */
    val publicImages: OkHttpClient = base.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    val searchThumbnails: OkHttpClient = publicImages.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    val webhook: OkHttpClient = base.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}
