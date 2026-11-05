@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.features.media.data

import io.ktor.client.fetchOptions
import io.ktor.client.request.HttpRequestBuilder
import kotlin.js.toJsString

internal actual fun HttpRequestBuilder.configureMediaObjectRequest() {
    fetchOptions {
        credentials = "omit".toJsString()
        redirect = "error".toJsString()
        cache = "no-store".toJsString()
        referrerPolicy = "no-referrer".toJsString()
    }
}

internal actual fun mediaObjectHttpEngine(): io.ktor.client.engine.HttpClientEngine = io.ktor.client.engine.js.Js.create()

internal actual fun includeMediaObjectHeader(name: String): Boolean = name.lowercase() !in setOf("host", "content-length")
